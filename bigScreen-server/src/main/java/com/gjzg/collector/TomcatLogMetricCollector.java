package com.gjzg.collector;

import com.gjzg.api.entity.MetricValueDTO;
import com.gjzg.mapper.MonitorMapper;
import com.gjzg.service.TopologyBindingProvider;
import com.gjzg.service.impl.MonitorServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.SimpleDateFormat;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tomcat访问日志指标定时采集器
 * <p>
 * 每2分钟从远程 DAOCLOUD_PLATFORM.T_MT_TOMCAT_ACCESS_LOG 查询日志，计算指标并写入监控表。
 */
@Component
public class TomcatLogMetricCollector {

    /**
     * Tomcat 远程日志库数据源（见 TomcatLogDataSourceConfig，配置项 spring.datasource.tomcat-log）
     */
    @Autowired
    @Qualifier("tomcatLogJdbcTemplate")
    private JdbcTemplate tomcatLogJdbcTemplate;

    @Autowired
    private MonitorMapper monitorMapper;

    @Autowired
    private MonitorServiceImpl monitorService;

    /**
     * 节点绑定关系提供者：采样节点 IP、分站过滤规则、兜底节点均按库中节点构建
     */
    @Autowired
    private TopologyBindingProvider bindingProvider;

    // 指标名称常量（必须与 T_MT_NODE_METRIC_DEF 表中的 METRIC_NAME 完全一致；
    // 展示层不再带 Tomcat 前缀，应用服务节点统一用 QPS / 请求失败率 / 5xx错误率 等）
    private static final String METRIC_TOMCAT_QPS = "QPS";
    private static final String METRIC_TOMCAT_ERROR_RATE = "请求失败率";
    private static final String METRIC_TOMCAT_5XX_RATE = "5xx错误率";
    private static final String METRIC_TOMCAT_REQUEST_ERROR_RATE = "请求错误率";
    private static final String METRIC_TOMCAT_4XX_RATE = "4xx错误率";
    private static final String METRIC_TOMCAT_ALIVE = "进程存活";
    private static final String METRIC_TOMCAT_TOP_INTERFACE = "接口请求量TOP";

    // TOP接口指标保留的接口个数
    private static final int TOP_INTERFACE_LIMIT = 5;

    // 指标值字段长度上限（METRIC_VALUE 为 VARCHAR2(500)），预留余量防止 ORA-12899
    private static final int METRIC_VALUE_MAX_LEN = 480;

    // STATUS 在日志表中为 VARCHAR2，先用正则过滤非数字值再转换，避免 TO_NUMBER 触发 ORA-01722
    private static final String STATUS_NUMBER =
            "(CASE WHEN REGEXP_LIKE(STATUS, '^[0-9]+$') THEN TO_NUMBER(STATUS) END)";

    // 条件聚合用的状态码区间条件
    private static final String STATUS_AT_LEAST_400 = STATUS_NUMBER + " >= 400";
    private static final String STATUS_AT_LEAST_500 = STATUS_NUMBER + " >= 500";
    private static final String STATUS_4XX = STATUS_NUMBER + " >= 400 AND " + STATUS_NUMBER + " < 500";

    // 指标名称 -> METRIC_ID 映射缓存
    private final Map<String, Map<String, String>> nodeMetricIdMap = new ConcurrentHashMap<>();

    // 指标名称 -> TIME_WINDOW分钟 映射缓存
    private final Map<String, Map<String, Integer>> nodeMetricWindowMap = new ConcurrentHashMap<>();

    /**
     * 定时采集任务：每2分钟执行一次
     */
    @Scheduled(fixedRate = 120000)
    public void collect() {
        try {
            // 1. 加载绑定快照并刷新指标ID映射（绑定变更最多一个采集周期生效）
            TopologyBindingProvider.BindingSnapshot bindings = bindingProvider.load();
            refreshNodeMetricMap(bindings.getMetricNodeIds());

            // 2. 查询远程日志表最新入库时间
            String maxCreateTime = queryMaxCreateTime();
            if (maxCreateTime == null) {
                return; // 没有日志数据
            }

            String sourceTimeStr = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
            List<MetricValueDTO> metricsList = new ArrayList<>();

            // 兜底节点按 styleClass=tomcat 识别（库中无该节点时为 null，兜底逻辑整体跳过）
            String fallbackNodeId = bindings.getTomcatFallbackNodeId();

            // 3. 实例维度指标：按 SERVER_IP 分组计算一次，后续分发给各归属节点
            //    同机多应用无法在日志层区分，同一 IP 的指标会写入其归属的所有节点
            Map<String, TomcatMetrics> instanceMap = calculateTomcatMetricsByInstance(
                    maxCreateTime,
                    fallbackNodeId == null ? null : nodeMetricWindowMap.get(fallbackNodeId),
                    new ArrayList<>(bindings.getIpNodes().keySet()),
                    fallbackNodeId);

            // 4. 各应用节点：汇总维度（按节点归属 IP 过滤）+ 实例维度
            //    同机多应用的节点过滤条件相同，汇总结果按 IP 列表缓存复用，避免重复查询
            Map<String, TomcatMetrics> summaryCache = new HashMap<>();
            for (Map.Entry<String, List<String>> entry : bindings.getTomcatNodeIps().entrySet()) {
                String nodeId = entry.getKey();
                Map<String, String> defMap = nodeMetricIdMap.get(nodeId);
                if (defMap == null || defMap.isEmpty()) {
                    continue; // 指标未定义（未执行 init_tomcat_metrics.sql），跳过该节点
                }
                List<String> nodeIps = entry.getValue();

                String cacheKey = String.join(",", nodeIps);
                TomcatMetrics summary = summaryCache.get(cacheKey);
                if (summary == null) {
                    summary = calculateNodeSummary(maxCreateTime, nodeMetricWindowMap.get(nodeId), nodeIps);
                    summaryCache.put(cacheKey, summary);
                }
                addSummaryMetrics(metricsList, nodeId, defMap, summary, sourceTimeStr);

                // 实例维度：该节点归属的每个 IP 各写一份（同机多应用展示同一份数据）
                for (String serverIp : nodeIps) {
                    TomcatMetrics instanceMetrics = instanceMap.get(serverIp);
                    if (instanceMetrics != null) {
                        addInstanceMetrics(metricsList, nodeId, defMap, serverIp, instanceMetrics, sourceTimeStr);
                    }
                }
            }

            // 4.1 1.0 分站：无独立实例 IP，按 REMOTE_ADDR（nginx 转发地址）+
            //     REQUEST 前缀定位（/pposgjdf 分站、/pposfa 估值分站）
            for (Map.Entry<String, TopologyBindingProvider.SubSiteFilter> entry : bindings.getSubSiteFilters().entrySet()) {
                String nodeId = entry.getKey();
                Map<String, String> defMap = nodeMetricIdMap.get(nodeId);
                if (defMap == null || defMap.isEmpty()) {
                    continue; // 指标未定义（未执行 init_tomcat_metrics.sql 第 3 段），跳过分站
                }
                TopologyBindingProvider.SubSiteFilter filter = entry.getValue();
                TomcatMetrics summary = calculateSubSiteSummary(maxCreateTime,
                        nodeMetricWindowMap.get(nodeId), filter);
                addNodeMetric(metricsList, nodeId, defMap, METRIC_TOMCAT_QPS,
                        summary.qps, sourceTimeStr, null);
                addNodeMetric(metricsList, nodeId, defMap, METRIC_TOMCAT_ERROR_RATE,
                        summary.errorRate, sourceTimeStr, null);
                addNodeMetric(metricsList, nodeId, defMap, METRIC_TOMCAT_5XX_RATE,
                        summary.error5xxRate, sourceTimeStr, null);
                // 分站是同一 Tomcat 上的不同应用路径，4xx 占比较高，汇总维度也写入；
                // 分站不再计算接口请求量TOP（指标定义与前端展示同步下线）
                addNodeMetric(metricsList, nodeId, defMap, METRIC_TOMCAT_4XX_RATE,
                        summary.error4xxRate, sourceTimeStr, null);
            }

            // 5. 兜底节点（styleClass=tomcat）：全部日志汇总 + 未映射实例，保证新增机器接入前数据不丢
            if (fallbackNodeId != null) {
                Map<String, String> fallbackDefMap = nodeMetricIdMap.get(fallbackNodeId);
                if (fallbackDefMap != null && !fallbackDefMap.isEmpty()) {
                    TomcatMetrics allSummary = calculateNodeSummary(maxCreateTime,
                            nodeMetricWindowMap.get(fallbackNodeId), null);
                    addSummaryMetrics(metricsList, fallbackNodeId, fallbackDefMap, allSummary, sourceTimeStr);
                    for (Map.Entry<String, TomcatMetrics> entry : instanceMap.entrySet()) {
                        if (!bindings.getIpNodes().containsKey(entry.getKey())) {
                            addInstanceMetrics(metricsList, fallbackNodeId, fallbackDefMap,
                                    entry.getKey(), entry.getValue(), sourceTimeStr);
                        }
                    }
                }
            }

            if (metricsList.isEmpty()) {
                return; // 无指标定义或无可写入数据
            }

            // 6. 写入数据库（history + latest）
            String collectId = "TC" + new SimpleDateFormat("yyyyMMddHHmmss").format(new Date());
            monitorMapper.batchInsertNodeHistory(metricsList, collectId);
            monitorMapper.mergeNodeLatest(metricsList, collectId);

            // 7. 告警评估（未配置规则时无副作用，失败不影响采集）
            //    注意：同机多节点共享同一份指标，告警规则仅在同机多节点的首个节点配置，避免重复告警
            try {
                monitorService.evaluateNodeAlerts(metricsList);
            } catch (Exception ex) {
                System.err.println("Tomcat指标告警评估失败：" + ex.getMessage());
            }
        } catch (Exception e) {
            // 采集异常不中断主流程，记录日志即可
            System.err.println("Tomcat日志指标采集失败：" + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 刷新节点指标定义映射（METRIC_NAME -> METRIC_ID / TIME_WINDOW）
     * 遍历应用节点 + 日志过滤节点 + 兜底节点，未定义指标的节点映射为空，采集时自动跳过
     */
    private void refreshNodeMetricMap(List<String> nodeIds) {
        for (String nodeId : nodeIds) {
            List<Map<String, Object>> list = monitorMapper.selectNodeMetricDefMap(nodeId);
            Map<String, String> idMap = new ConcurrentHashMap<>();
            Map<String, Integer> windowMap = new ConcurrentHashMap<>();
            for (Map<String, Object> row : list) {
                String name = (String) row.get("METRIC_NAME");
                String id = (String) row.get("METRIC_ID");
                Number window = (Number) row.get("TIME_WINDOW");
                if (name != null && id != null) {
                    idMap.put(name, id);
                    windowMap.put(name, window != null ? window.intValue() : 3);
                }
            }
            nodeMetricIdMap.put(nodeId, idMap);
            nodeMetricWindowMap.put(nodeId, windowMap);
        }
    }

    /**
     * 查询远程日志表最大入库时间
     */
    private String queryMaxCreateTime() {
        String sql = "SELECT MAX(CREATE_TIME) FROM DAOCLOUD_PLATFORM.T_MT_TOMCAT_ACCESS_LOG";
        try {
            return tomcatLogJdbcTemplate.queryForObject(sql, String.class);
        } catch (Exception e) {
            System.err.println("查询Tomcat日志最大时间失败：" + e.getMessage());
            return null;
        }
    }

    /**
     * 计算起始时间：基于日志时间字符串减指定分钟数
     * 数据库 CREATE_TIME 为 VARCHAR2，格式：2026-10-16 09:02:58
     */
    private String calcStartTime(String maxCreateTime, int minutes) {
        try {
            // 截取前19位作为日期时间部分：2026-10-16 09:02:58
            String dateTimePart = maxCreateTime.substring(0, 19);
            DateTimeFormatter formatter;
            // 根据第11位判断是空格格式还是T格式
            if (dateTimePart.charAt(10) == ' ') {
                formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
            } else {
                formatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
            }
            LocalDateTime dt = LocalDateTime.parse(dateTimePart, formatter);
            LocalDateTime startDt = dt.minusMinutes(minutes);
            // 返回与数据库格式一致的字符串（yyyy-MM-dd HH:mm:ss）
            return startDt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        } catch (Exception e) {
            // 解析失败则直接返回原值（保险做法）
            System.err.println("Tomcat calcStartTime 解析失败，原值=" + maxCreateTime + "，异常=" + e.getMessage());
            return maxCreateTime;
        }
    }

    /**
     * 节点指标添加：根据指标名称从映射中查找 METRIC_ID，构建DTO并加入列表
     * 指标未在 T_MT_NODE_METRIC_DEF 中定义时跳过
     *
     * @param nodeId   指标归属节点ID（应用节点或兜底节点）
     * @param serverIp 实例IP；为空表示节点汇总维度
     */
    private void addNodeMetric(List<MetricValueDTO> list, String nodeId, Map<String, String> metricMap,
                               String metricName, String value, String sourceTime, String serverIp) {
        String metricId = metricMap.get(metricName);
        if (metricId == null) {
            return;
        }
        MetricValueDTO dto = new MetricValueDTO();
        dto.setNodeId(nodeId);
        dto.setMetricId(metricId);
        dto.setMetricValue(value);
        dto.setSourceTime(sourceTime);
        dto.setServerIp(serverIp);
        list.add(dto);
    }

    /**
     * 节点汇总维度指标写入（4xx错误率不写汇总：汇总值容易被整体流量稀释，诊断价值低）
     */
    private void addSummaryMetrics(List<MetricValueDTO> list, String nodeId, Map<String, String> defMap,
                                   TomcatMetrics summary, String sourceTime) {
        addNodeMetric(list, nodeId, defMap, METRIC_TOMCAT_QPS, summary.qps, sourceTime, null);
        addNodeMetric(list, nodeId, defMap, METRIC_TOMCAT_ERROR_RATE, summary.errorRate, sourceTime, null);
        addNodeMetric(list, nodeId, defMap, METRIC_TOMCAT_5XX_RATE, summary.error5xxRate, sourceTime, null);
        addNodeMetric(list, nodeId, defMap, METRIC_TOMCAT_REQUEST_ERROR_RATE, summary.requestErrorRate, sourceTime, null);
        addNodeMetric(list, nodeId, defMap, METRIC_TOMCAT_TOP_INTERFACE, summary.topInterface, sourceTime, null);
    }

    /**
     * 实例维度指标写入（同机多应用时，同一份数据写入其归属的每个节点）
     */
    private void addInstanceMetrics(List<MetricValueDTO> list, String nodeId, Map<String, String> defMap,
                                    String serverIp, TomcatMetrics metrics, String sourceTime) {
        addNodeMetric(list, nodeId, defMap, METRIC_TOMCAT_QPS, metrics.qps, sourceTime, serverIp);
        addNodeMetric(list, nodeId, defMap, METRIC_TOMCAT_ERROR_RATE, metrics.errorRate, sourceTime, serverIp);
        addNodeMetric(list, nodeId, defMap, METRIC_TOMCAT_5XX_RATE, metrics.error5xxRate, sourceTime, serverIp);
        addNodeMetric(list, nodeId, defMap, METRIC_TOMCAT_REQUEST_ERROR_RATE, metrics.requestErrorRate, sourceTime, serverIp);
        addNodeMetric(list, nodeId, defMap, METRIC_TOMCAT_4XX_RATE, metrics.error4xxRate, sourceTime, serverIp);
        addNodeMetric(list, nodeId, defMap, METRIC_TOMCAT_ALIVE, metrics.alive, sourceTime, serverIp);
    }

    /**
     * 计算节点汇总维度指标：按节点归属的实例 IP 过滤日志
     * <p>
     * QPS / 请求失败率 / 5xx错误率 / 请求错误率 由一次窗口扫描 + 条件聚合得出（原为 4 次查询），
     * 接口请求量TOP 需要按接口路径分组，仍单独一次查询。
     *
     * @param ips 节点归属的实例 IP 列表；为空表示不过滤（兜底节点统计全部日志）
     */
    private TomcatMetrics calculateNodeSummary(String endTime, Map<String, Integer> windowMap, List<String> ips) {
        TomcatMetrics summary = new TomcatMetrics();
        int qpsWindow = getWindow(windowMap, METRIC_TOMCAT_QPS, 3);
        int errorRateWindow = getWindow(windowMap, METRIC_TOMCAT_ERROR_RATE, 3);
        int error5xxWindow = getWindow(windowMap, METRIC_TOMCAT_5XX_RATE, 3);
        int requestErrorRateWindow = getWindow(windowMap, METRIC_TOMCAT_REQUEST_ERROR_RATE, 3);
        int topInterfaceWindow = getWindow(windowMap, METRIC_TOMCAT_TOP_INTERFACE, 3);

        // 外层扫描下界取最宽窗口：取最小值会按最小窗口截断行集，
        // 使配置成更大窗口的指标（如 QPS 10 分钟）只统计到最小窗口内的数据
        int widestWindow = Math.max(Math.max(qpsWindow, errorRateWindow),
                Math.max(error5xxWindow, Math.max(requestErrorRateWindow, topInterfaceWindow)));
        String startTime = calcStartTime(endTime, widestWindow);

        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT ");
        appendCountColumn(sql, args, true, endTime, qpsWindow, "QPS_CNT", null);
        appendCountColumn(sql, args, false, endTime, errorRateWindow, "ERR_TOT", null);
        appendCountColumn(sql, args, false, endTime, errorRateWindow, "ERR_CNT", STATUS_AT_LEAST_400);
        appendCountColumn(sql, args, false, endTime, error5xxWindow, "E5_TOT", null);
        appendCountColumn(sql, args, false, endTime, error5xxWindow, "E5_CNT", STATUS_AT_LEAST_500);
        appendCountColumn(sql, args, false, endTime, requestErrorRateWindow, "REQERR_TOT", null);
        appendCountColumn(sql, args, false, endTime, requestErrorRateWindow, "REQERR_CNT", STATUS_AT_LEAST_400);
        sql.append(" FROM DAOCLOUD_PLATFORM.T_MT_TOMCAT_ACCESS_LOG")
                .append(" WHERE CREATE_TIME >= ? AND CREATE_TIME <= ?");
        args.add(startTime);
        args.add(endTime);
        sql.append(buildIpFilter(ips, args));

        try {
            Map<String, Object> row = tomcatLogJdbcTemplate.queryForMap(sql.toString(), args.toArray());
            long qpsSeconds = calcWindowSeconds(calcStartTime(endTime, qpsWindow), endTime);
            summary.qps = formatQps(longOf(row, "QPS_CNT"), qpsSeconds);
            summary.errorRate = formatPercent(longOf(row, "ERR_CNT"), longOf(row, "ERR_TOT"));
            summary.error5xxRate = formatPercent(longOf(row, "E5_CNT"), longOf(row, "E5_TOT"));
            summary.requestErrorRate = formatPercent(longOf(row, "REQERR_CNT"), longOf(row, "REQERR_TOT"));
        } catch (Exception e) {
            System.err.println("计算Tomcat节点汇总指标失败（条件聚合）：" + e.getMessage());
        }

        // 接口请求量TOP：需按接口路径分组，单独一次查询
        summary.topInterface = calculateTopInterfaceRequests(
                calcStartTime(endTime, topInterfaceWindow), endTime, ips);
        return summary;
    }

    /**
     * 构建实例 IP 过滤条件，参数按占位符顺序追加到 args
     *
     * @param ips  实例 IP 列表；为空表示不过滤
     * @param args 已收集的查询参数（原地追加）
     * @return SQL 片段（以 AND 开头）
     */
    private String buildIpFilter(List<String> ips, List<Object> args) {
        if (ips == null || ips.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(" AND TRIM(SERVER_IP) IN (");
        for (int i = 0; i < ips.size(); i++) {
            sb.append(i == 0 ? "?" : ", ?");
            args.add(ips.get(i));
        }
        return sb.append(")").toString();
    }

    /**
     * 构建 REMOTE_ADDR 过滤条件，参数按占位符顺序追加到 args
     *
     * @param remoteIps REMOTE_ADDR 列表；为空表示不过滤
     * @param args      已收集的查询参数（原地追加）
     * @return SQL 片段（以 AND 开头）
     */
    private String buildRemoteAddrFilter(List<String> remoteIps, List<Object> args) {
        if (remoteIps == null || remoteIps.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(" AND TRIM(REMOTE_ADDR) IN (");
        for (int i = 0; i < remoteIps.size(); i++) {
            sb.append(i == 0 ? "?" : ", ?");
            args.add(remoteIps.get(i));
        }
        return sb.append(")").toString();
    }

    /**
     * 计算 1.0 分站可采集指标（汇总维度：QPS / 请求失败率 / 5xx错误率 / 4xx错误率）
     * 各指标使用独立时间窗口，由一次窗口扫描 + 条件聚合得出（原为 4 次查询）；
     * 分站是同一 Tomcat 上的应用路径，不计算进程存活与接口请求量TOP
     */
    private TomcatMetrics calculateSubSiteSummary(String endTime, Map<String, Integer> windowMap,
                                                  TopologyBindingProvider.SubSiteFilter filter) {
        TomcatMetrics summary = new TomcatMetrics();
        int qpsWindow = getWindow(windowMap, METRIC_TOMCAT_QPS, 3);
        int errorRateWindow = getWindow(windowMap, METRIC_TOMCAT_ERROR_RATE, 3);
        int error5xxWindow = getWindow(windowMap, METRIC_TOMCAT_5XX_RATE, 3);
        int error4xxWindow = getWindow(windowMap, METRIC_TOMCAT_4XX_RATE, 3);

        // 外层扫描下界取最宽窗口，理由同 {@link #calculateNodeSummary}
        int widestWindow = Math.max(Math.max(qpsWindow, errorRateWindow),
                Math.max(error5xxWindow, error4xxWindow));
        String startTime = calcStartTime(endTime, widestWindow);

        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT ");
        appendCountColumn(sql, args, true, endTime, qpsWindow, "QPS_CNT", null);
        appendCountColumn(sql, args, false, endTime, errorRateWindow, "ERR_TOT", null);
        appendCountColumn(sql, args, false, endTime, errorRateWindow, "ERR_CNT", STATUS_AT_LEAST_400);
        appendCountColumn(sql, args, false, endTime, error5xxWindow, "E5_TOT", null);
        appendCountColumn(sql, args, false, endTime, error5xxWindow, "E5_CNT", STATUS_AT_LEAST_500);
        appendCountColumn(sql, args, false, endTime, error4xxWindow, "E4_TOT", null);
        appendCountColumn(sql, args, false, endTime, error4xxWindow, "E4_CNT", STATUS_4XX);
        sql.append(" FROM DAOCLOUD_PLATFORM.T_MT_TOMCAT_ACCESS_LOG")
                .append(" WHERE CREATE_TIME >= ? AND CREATE_TIME <= ?");
        args.add(startTime);
        args.add(endTime);
        sql.append(buildSubSiteFilter(filter, args));

        try {
            Map<String, Object> row = tomcatLogJdbcTemplate.queryForMap(sql.toString(), args.toArray());
            long qpsSeconds = calcWindowSeconds(calcStartTime(endTime, qpsWindow), endTime);
            summary.qps = formatQps(longOf(row, "QPS_CNT"), qpsSeconds);
            summary.errorRate = formatPercent(longOf(row, "ERR_CNT"), longOf(row, "ERR_TOT"));
            summary.error5xxRate = formatPercent(longOf(row, "E5_CNT"), longOf(row, "E5_TOT"));
            summary.error4xxRate = formatPercent(longOf(row, "E4_CNT"), longOf(row, "E4_TOT"));
        } catch (Exception e) {
            System.err.println("计算1.0分站指标失败（" + filter.getRequestPrefix() + "）：" + e.getMessage());
        }
        return summary;
    }

    /**
     * 构建 1.0 分站日志过滤条件（REMOTE_ADDR 列表 + REQUEST 前缀），参数按占位符顺序追加到 args
     */
    private String buildSubSiteFilter(TopologyBindingProvider.SubSiteFilter filter, List<Object> args) {
        StringBuilder sb = new StringBuilder(buildRemoteAddrFilter(filter.getRemoteIps(), args));
        sb.append(" AND REQUEST LIKE ?");
        args.add(filter.getRequestPrefix() + "%");
        return sb.toString();
    }

    /**
     * 计算 Tomcat 各接口请求量 TOP N
     * 按接口路径（REQUEST 去掉查询参数部分）聚合请求数，按次数降序取前 N（并列时按路径升序，保证结果稳定）；
     * 值为 "path(count次) | path(count次) | ..."，无数据时返回 "--"
     * 注意：接口路径可能较长（REQUEST 字段 VARCHAR2(2200)），拼接时限制总长度避免超出 METRIC_VALUE(500)
     *
     * @param ips 实例 IP 过滤条件，为空表示不过滤（全部日志）
     */
    private String calculateTopInterfaceRequests(String startTime, String endTime, List<String> ips) {
        List<Object> args = new ArrayList<>();
        args.add(startTime);
        args.add(endTime);
        String ipFilter = buildIpFilter(ips, args);
        args.add(TOP_INTERFACE_LIMIT);
        String sql = "SELECT * FROM ("
                + "SELECT CASE WHEN INSTR(REQUEST, '?') > 0 "
                + "THEN SUBSTR(REQUEST, 1, INSTR(REQUEST, '?') - 1) ELSE REQUEST END AS REQ_PATH, "
                + "COUNT(*) AS CNT "
                + "FROM DAOCLOUD_PLATFORM.T_MT_TOMCAT_ACCESS_LOG "
                + "WHERE CREATE_TIME >= ? AND CREATE_TIME <= ? AND REQUEST IS NOT NULL" + ipFilter
                + " GROUP BY CASE WHEN INSTR(REQUEST, '?') > 0 "
                + "THEN SUBSTR(REQUEST, 1, INSTR(REQUEST, '?') - 1) ELSE REQUEST END "
                + "ORDER BY CNT DESC, REQ_PATH ASC) WHERE ROWNUM <= ?";
        try {
            List<Map<String, Object>> rows = tomcatLogJdbcTemplate.queryForList(sql, args.toArray());
            StringBuilder sb = new StringBuilder();
            for (Map<String, Object> row : rows) {
                String path = (String) row.get("REQ_PATH");
                Number cnt = (Number) row.get("CNT");
                if (path == null || path.trim().isEmpty() || cnt == null) continue;
                String item = path.trim() + "(" + cnt.intValue() + "次)";
                // 超出字段长度上限时停止追加，保证已拼接的接口条目完整
                int extraLen = (sb.length() > 0 ? 3 : 0) + item.length();
                if (sb.length() + extraLen > METRIC_VALUE_MAX_LEN) break;
                if (sb.length() > 0) sb.append(" | ");
                sb.append(item);
            }
            return sb.length() > 0 ? sb.toString() : "--";
        } catch (Exception e) {
            System.err.println("计算Tomcat接口请求量TOP失败：" + e.getMessage());
            return "--";
        }
    }

    /**
     * 按实例计算 Tomcat 指标（按 SERVER_IP 分组，一次扫描后分发给各归属节点）
     * <p>
     * 各指标使用独立时间窗口，由一次窗口扫描 + 条件聚合得出（原为 6 次查询）；
     * 已知实例（映射表 IP + 兜底节点历史 IP）窗口内无日志时仍输出 0 值记录，保证静默下线可见
     * 注意：日志表 SERVER_IP 为空时不会产生实例维度数据，仅保留节点汇总维度
     *
     * @return Map<SERVER_IP, 该实例的Tomcat指标>
     */
    private Map<String, TomcatMetrics> calculateTomcatMetricsByInstance(String endTime, Map<String, Integer> windowMap,
                                                                       List<String> mappedIps,
                                                                       String fallbackNodeId) {
        Map<String, TomcatMetrics> result = new HashMap<>();
        try {
            int qpsWindow = getWindow(windowMap, METRIC_TOMCAT_QPS, 3);
            int errorRateWindow = getWindow(windowMap, METRIC_TOMCAT_ERROR_RATE, 3);
            int error5xxWindow = getWindow(windowMap, METRIC_TOMCAT_5XX_RATE, 3);
            int requestErrorRateWindow = getWindow(windowMap, METRIC_TOMCAT_REQUEST_ERROR_RATE, 3);
            int error4xxWindow = getWindow(windowMap, METRIC_TOMCAT_4XX_RATE, 3);

            // 外层扫描下界取最宽窗口，避免取最小值把大窗口指标截断；固定 3 分钟的存活指标不参与下界
            int widestWindow = Math.max(Math.max(qpsWindow, errorRateWindow),
                    Math.max(error5xxWindow, Math.max(requestErrorRateWindow, Math.max(error4xxWindow, 3))));
            String startTime = calcStartTime(endTime, widestWindow);

            List<Object> args = new ArrayList<>();
            StringBuilder sql = new StringBuilder("SELECT TRIM(SERVER_IP) AS SERVER_IP");
            appendCountColumn(sql, args, false, endTime, qpsWindow, "QPS_CNT", null);
            appendCountColumn(sql, args, false, endTime, errorRateWindow, "ERR_TOT", null);
            appendCountColumn(sql, args, false, endTime, errorRateWindow, "ERR_CNT", STATUS_AT_LEAST_400);
            appendCountColumn(sql, args, false, endTime, error5xxWindow, "E5_TOT", null);
            appendCountColumn(sql, args, false, endTime, error5xxWindow, "E5_CNT", STATUS_AT_LEAST_500);
            appendCountColumn(sql, args, false, endTime, requestErrorRateWindow, "REQERR_TOT", null);
            appendCountColumn(sql, args, false, endTime, requestErrorRateWindow, "REQERR_CNT", STATUS_AT_LEAST_400);
            appendCountColumn(sql, args, false, endTime, error4xxWindow, "E4_TOT", null);
            appendCountColumn(sql, args, false, endTime, error4xxWindow, "E4_CNT", STATUS_4XX);
            // 进程存活：固定 3 分钟窗口内有日志即为 1
            appendCountColumn(sql, args, false, endTime, 3, "ALIVE_CNT", null);
            // SERVER_IP 可能为 NULL/空串/空格，统一用 TRIM 过滤（Oracle 中空串即 NULL）
            sql.append(" FROM DAOCLOUD_PLATFORM.T_MT_TOMCAT_ACCESS_LOG")
                    .append(" WHERE CREATE_TIME >= ? AND CREATE_TIME <= ?")
                    .append(" AND TRIM(SERVER_IP) IS NOT NULL")
                    .append(" GROUP BY TRIM(SERVER_IP)");
            args.add(startTime);
            args.add(endTime);

            long qpsSeconds = calcWindowSeconds(calcStartTime(endTime, qpsWindow), endTime);
            List<Map<String, Object>> rows = tomcatLogJdbcTemplate.queryForList(sql.toString(), args.toArray());
            for (Map<String, Object> row : rows) {
                String serverIp = (String) row.get("SERVER_IP");
                if (serverIp == null) {
                    continue;
                }
                TomcatMetrics metrics = getOrCreateInstanceMetrics(result, serverIp);
                metrics.qps = formatQps(longOf(row, "QPS_CNT"), qpsSeconds);
                metrics.errorRate = formatPercent(longOf(row, "ERR_CNT"), longOf(row, "ERR_TOT"));
                metrics.error5xxRate = formatPercent(longOf(row, "E5_CNT"), longOf(row, "E5_TOT"));
                metrics.requestErrorRate = formatPercent(longOf(row, "REQERR_CNT"), longOf(row, "REQERR_TOT"));
                metrics.error4xxRate = formatPercent(longOf(row, "E4_CNT"), longOf(row, "E4_TOT"));
                metrics.alive = longOf(row, "ALIVE_CNT") > 0 ? "1" : "0";
            }

            // 合并已知实例：已映射节点的 IP + 兜底节点历史 IP，窗口内无日志的实例补默认值 0.00，保证静默下线可见
            try {
                List<String> knownIps = new ArrayList<>(mappedIps);
                if (fallbackNodeId != null) {
                    knownIps.addAll(monitorMapper.selectDistinctServerIps(fallbackNodeId));
                }
                if (knownIps != null) {
                    for (String ip : knownIps) {
                        if (ip != null && !ip.trim().isEmpty()) {
                            getOrCreateInstanceMetrics(result, ip);
                        }
                    }
                }
            } catch (Exception e) {
                System.err.println("查询已知Tomcat实例列表失败：" + e.getMessage());
            }
        } catch (Exception e) {
            System.err.println("计算单实例Tomcat指标失败（条件聚合）：" + e.getMessage());
        }
        return result;
    }

    /**
     * 获取或创建实例指标对象（无日志时保持默认值：各项指标 0）
     */
    private TomcatMetrics getOrCreateInstanceMetrics(Map<String, TomcatMetrics> map, String serverIp) {
        TomcatMetrics metrics = map.get(serverIp);
        if (metrics == null) {
            metrics = new TomcatMetrics();
            map.put(serverIp, metrics);
        }
        return metrics;
    }

    /**
     * 计算时间窗口秒数
     */
    private long calcWindowSeconds(String startTime, String endTime) {
        try {
            LocalDateTime start = LocalDateTime.parse(startTime, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            LocalDateTime end = LocalDateTime.parse(endTime, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            return Duration.between(start, end).getSeconds();
        } catch (Exception e) {
            System.err.println("计算时间窗口秒数失败：" + e.getMessage());
            return 0;
        }
    }

    /**
     * 获取指标时间窗口（分钟），未配置或配置非法时取默认值
     */
    private int getWindow(Map<String, Integer> windowMap, String metricName, int defaultWindow) {
        if (windowMap == null) return defaultWindow;
        Integer w = windowMap.get(metricName);
        return w != null && w > 0 ? w : defaultWindow;
    }

    // ============================================================
    // 条件聚合 SQL 构建与结果取值
    // ============================================================

    /**
     * 追加一个「窗口内计数」条件聚合列，各指标用自己的时间窗口作为 CASE 条件
     *
     * @param first     是否为 SELECT 后的第一列（决定是否需要前导逗号）
     * @param window    该指标的时间窗口（分钟）
     * @param alias     列别名
     * @param extraCond 额外的行过滤条件（不带 AND，如 STATUS >= 400）；为空表示不限制
     */
    private void appendCountColumn(StringBuilder sql, List<Object> args, boolean first, String endTime,
                                   int window, String alias, String extraCond) {
        if (!first) {
            sql.append(", ");
        }
        sql.append("COUNT(CASE WHEN CREATE_TIME >= ?");
        if (extraCond != null && !extraCond.isEmpty()) {
            sql.append(" AND ").append(extraCond);
        }
        sql.append(" THEN 1 END) AS ").append(alias);
        args.add(calcStartTime(endTime, window));
    }

    private long longOf(Map<String, Object> row, String column) {
        Object value = row.get(column);
        return value instanceof Number ? ((Number) value).longValue() : 0L;
    }

    /**
     * QPS 格式化：窗口内请求数 / 窗口秒数，保留两位小数
     */
    private String formatQps(long count, long seconds) {
        if (count <= 0 || seconds <= 0) {
            return "0.00";
        }
        return String.format("%.2f", new BigDecimal(count)
                .divide(new BigDecimal(seconds), 2, RoundingMode.HALF_UP).doubleValue());
    }

    /**
     * 占比指标：err / total * 100，保留两位小数；total 为 0 时返回 0.00
     */
    private String formatPercent(long errCount, long totalCount) {
        if (totalCount <= 0) {
            return "0.00";
        }
        return String.format("%.2f", new BigDecimal(errCount)
                .multiply(new BigDecimal(100))
                .divide(new BigDecimal(totalCount), 2, RoundingMode.HALF_UP).doubleValue());
    }

    /**
     * Tomcat 指标数据对象（同时用于节点汇总与实例维度）
     */
    private static class TomcatMetrics {
        /** 每秒请求数 */
        String qps = "0.00";
        /** 请求失败率（STATUS>=400 占比） */
        String errorRate = "0.00";
        /** 5xx错误率 */
        String error5xxRate = "0.00";
        /** 请求错误率（(4xx+5xx) 占比） */
        String requestErrorRate = "0.00";
        /** 4xx错误率（仅实例维度） */
        String error4xxRate = "0.00";
        /** 进程存活（1存活 / 0宕机，仅实例维度） */
        String alive = "0";
        /** 接口请求量TOP（仅应用节点汇总维度；1.0 分站不采集） */
        String topInterface = "--";
    }
}
