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

import javax.annotation.PostConstruct;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.SimpleDateFormat;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Nginx 访问日志指标定时采集器
 * <p>
 * 数据源：远程 DAOCLOUD_PLATFORM.T_MT_NGINX_ACCESS_LOG
 */
@Component
public class NginxLogMetricCollector {

    @Autowired
    @Qualifier("nginxLogJdbcTemplate")
    private JdbcTemplate nginxLogJdbcTemplate;

    @Autowired
    private MonitorMapper monitorMapper;

    @Autowired
    private MonitorServiceImpl monitorService;

    /**
     * 节点绑定关系提供者：采样节点 IP、客户端节点、客户端->nginx 链路均由库中节点/链路构建
     */
    @Autowired
    private TopologyBindingProvider bindingProvider;

    // 数值字段校验正则：仅允许整数/小数（如 0.133、.133、1.192）
    private static final String NUMERIC_REGEX = "^[0-9]*\\.?[0-9]+$";

    // ============================================================
    // 指标名称常量（必须与 T_MT_NODE_METRIC_DEF / T_MT_LINK_METRIC_DEF 中的 METRIC_NAME 完全一致）
    // ============================================================

    // nginx 节点指标（14 个）
    private static final String METRIC_QPS = "每秒请求数(QPS)";
    private static final String METRIC_ERROR_RATE = "请求错误率";
    private static final String METRIC_AVG_RESPONSE_TIME = "平均响应时间";
    private static final String METRIC_P95_RESPONSE_TIME = "P95请求耗时";
    private static final String METRIC_P99_RESPONSE_TIME = "P99请求耗时";
    private static final String METRIC_SLOW_REQUEST_RATE = "慢请求占比";
    private static final String METRIC_5XX_ERROR_RATE = "5xx错误率";
    private static final String METRIC_4XX_ERROR_RATE = "4xx错误率";
    private static final String METRIC_UPSTREAM_RESPONSE_TIME = "上游响应耗时";
    private static final String METRIC_UPSTREAM_TIME_RATIO = "上游耗时占比";
    private static final String METRIC_NGINX_SELF_TIME = "Nginx自身处理耗时";
    private static final String METRIC_UPSTREAM_ERROR_RATE = "上游错误率";
    private static final String METRIC_UPSTREAM_STATUS_DIST = "上游状态码分布";
    private static final String METRIC_NGINX_ALIVE = "Nginx进程存活";

    // 客户端节点指标（6 个）
    private static final String METRIC_TOTAL_IP = "总IP数";
    private static final String METRIC_ACTIVE_IP = "IP活跃数";
    private static final String METRIC_TOP_IP = "TOP访问IP";
    private static final String METRIC_CONCURRENT_CONN = "并发连接数";
    private static final String METRIC_TERMINAL_DIST = "终端分布";
    private static final String METRIC_UA_TYPE = "UA类型";

    // 客户端 -> nginx 链路指标（5 个）
    private static final String LINK_METRIC_REQUEST_PATH = "TOP请求路径";
    private static final String LINK_METRIC_REQUEST_TOTAL = "请求总数";
    private static final String LINK_METRIC_REQUEST_FAIL = "请求失败次数";
    private static final String LINK_METRIC_REQUEST_AVG_TIME = "请求平均耗时";
    private static final String LINK_METRIC_REQUEST_SUCCESS_RATE = "请求成功率";

    // 节点ID -> (指标名称 -> METRIC_ID) 映射缓存
    private final Map<String, Map<String, String>> nodeMetricIdMap = new ConcurrentHashMap<>();

    // 节点ID -> (指标名称 -> TIME_WINDOW分钟) 映射缓存
    private final Map<String, Map<String, Integer>> nodeMetricWindowMap = new ConcurrentHashMap<>();

    // 链路ID -> (指标名称 -> METRIC_ID) 映射缓存
    private final Map<String, Map<String, String>> linkMetricIdMap = new ConcurrentHashMap<>();

    // 链路ID -> (指标名称 -> TIME_WINDOW分钟) 映射缓存
    private final Map<String, Map<String, Integer>> linkMetricWindowMap = new ConcurrentHashMap<>();

    /**
     * 定时采集任务：每2分钟执行一次
     */
    @Scheduled(fixedRate = 120000)
    public void collect() {
        try {
            // 1. 加载绑定快照并刷新指标ID映射（绑定变更最多一个采集周期生效）
            TopologyBindingProvider.BindingSnapshot bindings = bindingProvider.load();
            refreshMetricIdMap(bindings);

            // 2. 查询远程日志表最新入库时间
            String maxCreateTime = queryMaxCreateTime();
            if (maxCreateTime == null) {
                return; // 没有日志数据
            }

            String sourceTimeStr = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
            List<MetricValueDTO> nodeMetrics = new ArrayList<>();
            List<MetricValueDTO> linkMetrics = new ArrayList<>();

            // 3. 客户端节点（节点列表来自库中 styleClass=client）
            // 口径与 nginx 集群指标一致：按全部 nginx 实例 IP 过滤，排除不属于本拓扑的日志
            collectClient(nodeMetrics, bindings.getClientNodeIds(), collectNginxIps(bindings),
                    maxCreateTime, sourceTimeStr);

            // 4. nginx 集群节点（汇总 + 实例维度）：节点与实例 IP 均来自绑定表
            // 未绑定实例 IP 的节点跳过：buildIpFilter 在空列表时会退化为全量统计
            for (Map.Entry<String, List<String>> entry : bindings.getNginxNodeIps().entrySet()) {
                if (entry.getValue() == null || entry.getValue().isEmpty()) {
                    continue;
                }
                collectNginxCluster(nodeMetrics, entry.getKey(), entry.getValue(), maxCreateTime, sourceTimeStr);
            }

            // 5. 客户端 -> nginx 链路指标：链路与目标 nginx 实例 IP 均来自库（源 client、目标 nginx）
            for (Map.Entry<String, List<String>> entry : bindings.getClientNginxLinks().entrySet()) {
                collectClientToNginxLink(linkMetrics, entry.getKey(), entry.getValue(),
                        maxCreateTime, sourceTimeStr);
            }

            // 6. 写入数据库（history + latest）
            String collectId = generateCollectId();
            if (!nodeMetrics.isEmpty()) {
                monitorMapper.batchInsertNodeHistory(nodeMetrics, collectId);
                monitorMapper.mergeNodeLatest(nodeMetrics, collectId);
            }
            if (!linkMetrics.isEmpty()) {
                monitorMapper.batchInsertLinkHistory(linkMetrics, collectId);
                monitorMapper.mergeLinkLatest(linkMetrics, collectId);
            }

            // 7. 告警评估（未配置规则时无副作用，失败不影响采集）
            try {
                if (!nodeMetrics.isEmpty()) {
                    monitorService.evaluateNodeAlerts(nodeMetrics);
                }
                if (!linkMetrics.isEmpty()) {
                    monitorService.evaluateLinkAlerts(linkMetrics);
                }
            } catch (Exception ex) {
                System.err.println("Nginx指标告警评估失败：" + ex.getMessage());
            }
        } catch (Exception e) {
            // 采集异常不中断主流程，记录日志即可
            System.err.println("Nginx日志指标采集失败：" + e.getMessage());
            e.printStackTrace();
        }
    }

    @PostConstruct
    public void init() {
        refreshMetricIdMap(bindingProvider.load());
    }

    /**
     * 刷新指标ID映射（客户端节点 + 全部 nginx 节点 + 客户端->nginx 链路）
     */
    private void refreshMetricIdMap(TopologyBindingProvider.BindingSnapshot bindings) {
        for (String nodeId : bindings.getClientNodeIds()) {
            refreshNodeMetricMap(nodeId);
        }
        for (String nodeId : bindings.getNginxNodeIps().keySet()) {
            refreshNodeMetricMap(nodeId);
        }
        for (String linkId : bindings.getClientNginxLinks().keySet()) {
            refreshLinkMetricMap(linkId);
        }
    }

    private void refreshNodeMetricMap(String nodeId) {
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

    private void refreshLinkMetricMap(String linkId) {
        List<Map<String, Object>> list = monitorMapper.selectLinkMetricDefMap(linkId);
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
        linkMetricIdMap.put(linkId, idMap);
        linkMetricWindowMap.put(linkId, windowMap);
    }

    // ============================================================
    // 节点采集
    // ============================================================

    /**
     * 客户端节点指标采集（日志口径与 nginx 集群指标一致，按 nginx 实例 IP 过滤）
     * <p>
     * 客户端指标与具体实例无关，取全部已绑定的 nginx 实例日志；按节点分别计算（各节点指标窗口可能不同），
     * 未套用指标模板的节点跳过。新建的 client 节点无需改代码即可参与采集。
     *
     * @param nginxIps 全部 nginx 实例 IP 的并集；为空表示库中尚无 nginx 绑定，退化为不过滤
     */
    private void collectClient(List<MetricValueDTO> list, List<String> clientNodeIds, List<String> nginxIps,
                               String endTime, String sourceTime) {
        String ipFilter = buildIpFilter(nginxIps);
        for (String nodeId : clientNodeIds) {
            Map<String, String> defMap = nodeMetricIdMap.get(nodeId);
            if (defMap == null || defMap.isEmpty()) {
                continue; // 指标未定义（模板未套用），跳过该节点
            }
            Map<String, Integer> windowMap = nodeMetricWindowMap.get(nodeId);
            ClientMetrics metrics = calculateClientMetrics(endTime, windowMap, ipFilter);

            addNodeMetric(list, defMap, nodeId, METRIC_TOTAL_IP, metrics.totalIp, sourceTime);
            addNodeMetric(list, defMap, nodeId, METRIC_ACTIVE_IP, metrics.activeIp, sourceTime);
            addNodeMetric(list, defMap, nodeId, METRIC_TOP_IP, metrics.topIp, sourceTime);
            addNodeMetric(list, defMap, nodeId, METRIC_CONCURRENT_CONN, metrics.concurrentConn, sourceTime);
            addNodeMetric(list, defMap, nodeId, METRIC_TERMINAL_DIST, metrics.terminalDist, sourceTime);
            addNodeMetric(list, defMap, nodeId, METRIC_UA_TYPE, metrics.uaType, sourceTime);
        }
    }

    /**
     * 收集全部 nginx 节点绑定的实例 IP 并去重（客户端指标沿用集群口径）
     */
    private List<String> collectNginxIps(TopologyBindingProvider.BindingSnapshot bindings) {
        List<String> ips = new ArrayList<>();
        for (List<String> nodeIps : bindings.getNginxNodeIps().values()) {
            if (nodeIps == null) {
                continue;
            }
            for (String ip : nodeIps) {
                if (!ips.contains(ip)) {
                    ips.add(ip);
                }
            }
        }
        return ips;
    }

    /**
     * nginx 集群节点采集：汇总维度（14 指标）+ 实例维度（8 指标）
     *
     * @param nodeId    集群节点ID
     * @param clusterIps 该集群的实例 IP 列表（用于日志过滤与实例预置）
     */
    private void collectNginxCluster(List<MetricValueDTO> list, String nodeId,
                                     List<String> clusterIps, String endTime, String sourceTime) {
        Map<String, String> defMap = nodeMetricIdMap.get(nodeId);
        if (defMap == null || defMap.isEmpty()) {
            return; // 指标未定义，跳过
        }
        Map<String, Integer> windowMap = nodeMetricWindowMap.get(nodeId);

        // 一次窗口扫描 + 条件聚合：汇总维度与实例维度来自同一批聚合行
        NginxSnapshot snapshot = calculateClusterSnapshot(endTime, windowMap, clusterIps);
        NginxMetrics summary = snapshot.summary;
        addNodeMetric(list, defMap, nodeId, METRIC_QPS, summary.qps, sourceTime);
        addNodeMetric(list, defMap, nodeId, METRIC_ERROR_RATE, summary.errorRate, sourceTime);
        addNodeMetric(list, defMap, nodeId, METRIC_AVG_RESPONSE_TIME, summary.avgResponseTime, sourceTime);
        addNodeMetric(list, defMap, nodeId, METRIC_P95_RESPONSE_TIME, summary.p95ResponseTime, sourceTime);
        addNodeMetric(list, defMap, nodeId, METRIC_P99_RESPONSE_TIME, summary.p99ResponseTime, sourceTime);
        addNodeMetric(list, defMap, nodeId, METRIC_SLOW_REQUEST_RATE, summary.slowRequestRate, sourceTime);
        addNodeMetric(list, defMap, nodeId, METRIC_5XX_ERROR_RATE, summary.error5xxRate, sourceTime);
        addNodeMetric(list, defMap, nodeId, METRIC_4XX_ERROR_RATE, summary.error4xxRate, sourceTime);
        addNodeMetric(list, defMap, nodeId, METRIC_UPSTREAM_RESPONSE_TIME, summary.upstreamRespTime, sourceTime);
        addNodeMetric(list, defMap, nodeId, METRIC_UPSTREAM_TIME_RATIO, summary.upstreamTimeRatio, sourceTime);
        addNodeMetric(list, defMap, nodeId, METRIC_NGINX_SELF_TIME, summary.nginxSelfTime, sourceTime);
        addNodeMetric(list, defMap, nodeId, METRIC_UPSTREAM_ERROR_RATE, summary.upstreamErrorRate, sourceTime);
        addNodeMetric(list, defMap, nodeId, METRIC_UPSTREAM_STATUS_DIST, summary.upstreamStatusDist, sourceTime);
        addNodeMetric(list, defMap, nodeId, METRIC_NGINX_ALIVE, summary.nginxAlive, sourceTime);

        // 2. 实例维度（每个实例一组，serverIp=实例IP）
        //    实例指标集：QPS/请求错误率/平均响应时间/P95/P99/慢请求占比/5xx/存活
        //    4xx 与上游耗时类指标不单实例采集
        for (Map.Entry<String, NginxMetrics> entry : snapshot.instances.entrySet()) {
            String serverIp = entry.getKey();
            NginxMetrics m = entry.getValue();
            addNodeMetric(list, defMap, nodeId, METRIC_QPS, m.qps, sourceTime, serverIp);
            addNodeMetric(list, defMap, nodeId, METRIC_ERROR_RATE, m.errorRate, sourceTime, serverIp);
            addNodeMetric(list, defMap, nodeId, METRIC_AVG_RESPONSE_TIME, m.avgResponseTime, sourceTime, serverIp);
            addNodeMetric(list, defMap, nodeId, METRIC_P95_RESPONSE_TIME, m.p95ResponseTime, sourceTime, serverIp);
            addNodeMetric(list, defMap, nodeId, METRIC_P99_RESPONSE_TIME, m.p99ResponseTime, sourceTime, serverIp);
            addNodeMetric(list, defMap, nodeId, METRIC_SLOW_REQUEST_RATE, m.slowRequestRate, sourceTime, serverIp);
            addNodeMetric(list, defMap, nodeId, METRIC_5XX_ERROR_RATE, m.error5xxRate, sourceTime, serverIp);
            addNodeMetric(list, defMap, nodeId, METRIC_NGINX_ALIVE, m.nginxAlive, sourceTime, serverIp);
        }
    }

    /**
     * 客户端 -> nginx 链路指标采集（按集群实例 IP 过滤，口径与该集群全量一致）
     */
    private void collectClientToNginxLink(List<MetricValueDTO> list, String linkId,
                                          List<String> clusterIps, String endTime, String sourceTime) {
        Map<String, String> defMap = linkMetricIdMap.get(linkId);
        if (defMap == null || defMap.isEmpty()) {
            return; // 指标未定义，跳过
        }
        Map<String, Integer> windowMap = linkMetricWindowMap.get(linkId);
        LinkMetrics metrics = calculateLinkMetrics(endTime, windowMap, buildIpFilter(clusterIps));

        addLinkMetric(list, defMap, linkId, LINK_METRIC_REQUEST_PATH, metrics.requestPath, sourceTime);
        addLinkMetric(list, defMap, linkId, LINK_METRIC_REQUEST_TOTAL, metrics.requestTotal, sourceTime);
        addLinkMetric(list, defMap, linkId, LINK_METRIC_REQUEST_FAIL, metrics.requestFail, sourceTime);
        addLinkMetric(list, defMap, linkId, LINK_METRIC_REQUEST_AVG_TIME, metrics.requestAvgTime, sourceTime);
        addLinkMetric(list, defMap, linkId, LINK_METRIC_REQUEST_SUCCESS_RATE, metrics.requestSuccessRate, sourceTime);
    }

    // ============================================================
    // nginx 指标计算
    // ============================================================

    /**
     * 集群指标快照：一次窗口扫描同时得到汇总维度与实例维度
     */
    private static class NginxSnapshot {
        /** 汇总维度（SERVER_IP 为空，覆盖整个集群） */
        private final NginxMetrics summary = emptyMetrics();
        /** 实例维度（key = SERVER_IP），已预置本集群预期实例 */
        private final Map<String, NginxMetrics> instances = new HashMap<>();
    }

    /**
     * 一次窗口扫描 + 条件聚合计算集群指标（汇总维度 + 实例维度）
     */
    private NginxSnapshot calculateClusterSnapshot(String endTime, Map<String, Integer> windowMap,
                                                    List<String> clusterIps) {
        NginxSnapshot snapshot = new NginxSnapshot();
        String ipFilter = buildIpFilter(clusterIps);
        // 预置预期实例：窗口内无日志时仍输出 0 值（存活=0），保证静默下线可见
        for (String ip : clusterIps) {
            getOrCreateInstanceMetrics(snapshot.instances, ip);
        }

        // 各指标独立时间窗口（分钟）
        int qpsWindow = getWindow(windowMap, METRIC_QPS, 3);
        int errorRateWindow = getWindow(windowMap, METRIC_ERROR_RATE, 3);
        int avgTimeWindow = getWindow(windowMap, METRIC_AVG_RESPONSE_TIME, 3);
        int slowRateWindow = getWindow(windowMap, METRIC_SLOW_REQUEST_RATE, 3);
        int error5xxWindow = getWindow(windowMap, METRIC_5XX_ERROR_RATE, 3);
        int error4xxWindow = getWindow(windowMap, METRIC_4XX_ERROR_RATE, 3);
        int upstreamWindow = getWindow(windowMap, METRIC_UPSTREAM_RESPONSE_TIME, 3);
        int upstreamRatioWindow = getWindow(windowMap, METRIC_UPSTREAM_TIME_RATIO, 3);
        int selfTimeWindow = getWindow(windowMap, METRIC_NGINX_SELF_TIME, 3);
        int upstreamErrWindow = getWindow(windowMap, METRIC_UPSTREAM_ERROR_RATE, 3);
        int upstreamStatusWindow = getWindow(windowMap, METRIC_UPSTREAM_STATUS_DIST, 3);

        // 扫描窗口下界取所有指标中最宽的窗口：取最小值会按最小窗口截断行集，
        // 使配置成更大窗口的指标丢失数据；存活指标固定 3 分钟只影响 CASE 条件，不参与下界
        int widestWindow = Math.max(qpsWindow, Math.max(errorRateWindow, Math.max(avgTimeWindow,
                Math.max(slowRateWindow, Math.max(error5xxWindow, Math.max(error4xxWindow,
                        Math.max(upstreamWindow, Math.max(upstreamRatioWindow, Math.max(selfTimeWindow,
                                Math.max(upstreamErrWindow, Math.max(upstreamStatusWindow, 3)))))))))));
        String startTime = calcStartTime(endTime, widestWindow);

        // 数值字段先正则校验再转换，避免 TO_NUMBER 触发 ORA-01722
        String reqSeconds = "(CASE WHEN REGEXP_LIKE(REQUEST_TIME, '" + NUMERIC_REGEX + "') THEN TO_NUMBER(REQUEST_TIME) END)";
        String upSeconds = "(CASE WHEN REGEXP_LIKE(UP_RESPONSE_TIME, '" + NUMERIC_REGEX + "') THEN TO_NUMBER(UP_RESPONSE_TIME) END)";

        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT GROUPING(SERVER_IP) AS IS_SUMMARY, SERVER_IP");
        // QPS：窗口内请求数 / 窗口秒数
        sql.append(", COUNT(CASE WHEN CREATE_TIME >= ? THEN 1 END) AS QPS_CNT");
        args.add(calcStartTime(endTime, qpsWindow));
        // 请求错误率：(4xx + 5xx) / 总请求数
        sql.append(", COUNT(CASE WHEN CREATE_TIME >= ? THEN 1 END) AS ERR_TOT");
        args.add(calcStartTime(endTime, errorRateWindow));
        sql.append(", COUNT(CASE WHEN CREATE_TIME >= ? AND STATUS >= 400 THEN 1 END) AS ERR_CNT");
        args.add(calcStartTime(endTime, errorRateWindow));
        // 平均响应时间：AVG(REQUEST_TIME) 秒 → 毫秒
        sql.append(", AVG(CASE WHEN CREATE_TIME >= ? THEN ").append(reqSeconds).append(" END) AS AVG_TIME");
        args.add(calcStartTime(endTime, avgTimeWindow));
        // 慢请求占比：REQUEST_TIME > 1 秒的请求 / 总请求数
        sql.append(", COUNT(CASE WHEN CREATE_TIME >= ? THEN 1 END) AS SLOW_TOT");
        args.add(calcStartTime(endTime, slowRateWindow));
        sql.append(", COUNT(CASE WHEN CREATE_TIME >= ? AND ").append(reqSeconds).append(" > 1 THEN 1 END) AS SLOW_CNT");
        args.add(calcStartTime(endTime, slowRateWindow));
        // 5xx / 4xx 错误率
        sql.append(", COUNT(CASE WHEN CREATE_TIME >= ? THEN 1 END) AS E5_TOT");
        args.add(calcStartTime(endTime, error5xxWindow));
        sql.append(", COUNT(CASE WHEN CREATE_TIME >= ? AND STATUS >= 500 THEN 1 END) AS E5_CNT");
        args.add(calcStartTime(endTime, error5xxWindow));
        sql.append(", COUNT(CASE WHEN CREATE_TIME >= ? THEN 1 END) AS E4_TOT");
        args.add(calcStartTime(endTime, error4xxWindow));
        sql.append(", COUNT(CASE WHEN CREATE_TIME >= ? AND STATUS >= 400 AND STATUS < 500 THEN 1 END) AS E4_CNT");
        args.add(calcStartTime(endTime, error4xxWindow));
        // 上游响应耗时 / 上游耗时占比 / Nginx 自身处理耗时
        sql.append(", AVG(CASE WHEN CREATE_TIME >= ? AND UP_RESPONSE_TIME IS NOT NULL THEN ")
                .append(upSeconds).append(" END) AS UP_AVG");
        args.add(calcStartTime(endTime, upstreamWindow));
        sql.append(", SUM(CASE WHEN CREATE_TIME >= ? THEN ").append(upSeconds).append(" END) AS UP_SUM");
        args.add(calcStartTime(endTime, upstreamRatioWindow));
        sql.append(", SUM(CASE WHEN CREATE_TIME >= ? THEN ").append(reqSeconds).append(" END) AS REQ_SUM");
        args.add(calcStartTime(endTime, upstreamRatioWindow));
        sql.append(", AVG(CASE WHEN CREATE_TIME >= ? AND UP_RESPONSE_TIME IS NOT NULL THEN GREATEST(")
                .append(reqSeconds).append(" - ").append(upSeconds).append(", 0) END) AS SELF_AVG");
        args.add(calcStartTime(endTime, selfTimeWindow));
        // 上游错误率：UP_STATUS 为 502/503/504 的请求 / UP_STATUS 非空请求数
        sql.append(", COUNT(CASE WHEN CREATE_TIME >= ? AND TRIM(UP_STATUS) IS NOT NULL THEN 1 END) AS UPE_TOT");
        args.add(calcStartTime(endTime, upstreamErrWindow));
        sql.append(", COUNT(CASE WHEN CREATE_TIME >= ? AND TRIM(UP_STATUS) IN ('502', '503', '504') THEN 1 END) AS UPE_CNT");
        args.add(calcStartTime(endTime, upstreamErrWindow));
        // 上游状态码分布：仅统计 200/502/503/504 四类
        for (String status : new String[]{"200", "502", "503", "504"}) {
            sql.append(", COUNT(CASE WHEN CREATE_TIME >= ? AND TRIM(UP_STATUS) = '").append(status)
                    .append("' THEN 1 END) AS UP_").append(status);
            args.add(calcStartTime(endTime, upstreamStatusWindow));
        }
        // 进程存活：固定 3 分钟窗口内有日志即为 1
        sql.append(", COUNT(CASE WHEN CREATE_TIME >= ? THEN 1 END) AS ALIVE_CNT");
        args.add(calcStartTime(endTime, 3));

        sql.append(" FROM DAOCLOUD_PLATFORM.T_MT_NGINX_ACCESS_LOG")
                .append(" WHERE CREATE_TIME >= ? AND CREATE_TIME <= ?").append(ipFilter)
                .append(" GROUP BY GROUPING SETS ((), (SERVER_IP))");
        args.add(startTime);
        args.add(endTime);

        long qpsSeconds = calcWindowSeconds(calcStartTime(endTime, qpsWindow), endTime);
        try {
            List<Map<String, Object>> rows = nginxLogJdbcTemplate.queryForList(sql.toString(), args.toArray());
            for (Map<String, Object> row : rows) {
                if (isSummaryRow(row)) {
                    applyClusterAggregates(row, snapshot.summary, qpsSeconds);
                    continue;
                }
                String serverIp = (String) row.get("SERVER_IP");
                if (serverIp != null) {
                    applyClusterAggregates(row, getOrCreateInstanceMetrics(snapshot.instances, serverIp), qpsSeconds);
                }
            }
        } catch (Exception e) {
            System.err.println("计算Nginx集群指标失败（条件聚合）：" + e.getMessage());
        }

        applyPercentiles(snapshot, endTime, windowMap, ipFilter);
        return snapshot;
    }

    /**
     * 将一行条件聚合结果映射为指标值（汇总行与实例行共用）
     */
    private void applyClusterAggregates(Map<String, Object> row, NginxMetrics metrics, long qpsSeconds) {
        long qpsCount = longOf(row, "QPS_CNT");
        metrics.qps = qpsSeconds > 0
                ? String.format("%.2f", new BigDecimal(qpsCount)
                .divide(new BigDecimal(qpsSeconds), 2, RoundingMode.HALF_UP).doubleValue())
                : "0.00";
        metrics.errorRate = formatPercent(longOf(row, "ERR_CNT"), longOf(row, "ERR_TOT"));
        metrics.avgResponseTime = formatMillis(doubleOf(row, "AVG_TIME"));
        metrics.slowRequestRate = formatPercent(longOf(row, "SLOW_CNT"), longOf(row, "SLOW_TOT"));
        metrics.error5xxRate = formatPercent(longOf(row, "E5_CNT"), longOf(row, "E5_TOT"));
        metrics.error4xxRate = formatPercent(longOf(row, "E4_CNT"), longOf(row, "E4_TOT"));
        metrics.upstreamRespTime = formatMillis(doubleOf(row, "UP_AVG"));
        metrics.upstreamTimeRatio = formatSumRatio(doubleOf(row, "UP_SUM"), doubleOf(row, "REQ_SUM"));
        metrics.nginxSelfTime = formatMillis(doubleOf(row, "SELF_AVG"));
        metrics.upstreamErrorRate = formatPercent(longOf(row, "UPE_CNT"), longOf(row, "UPE_TOT"));
        metrics.upstreamStatusDist = formatUpstreamStatusDist(row);
        metrics.nginxAlive = longOf(row, "ALIVE_CNT") > 0 ? "1" : "0";
    }

    /**
     * 百分位聚合（P95 / P99）
     * <p>
     * PERCENTILE_CONT 是排序聚合，无法与条件聚合放在同一条语句里，因此单独扫描日志表；
     * 按时间窗口分组，同一窗口下的多个百分位共用同一 ORDER BY 表达式放在同一次查询（默认配置下仅 1 次）。
     */
    private void applyPercentiles(NginxSnapshot snapshot, String endTime, Map<String, Integer> windowMap,
                                  String ipFilter) {
        Map<Integer, List<Double>> windowPercentiles = new LinkedHashMap<>();
        windowPercentiles.computeIfAbsent(getWindow(windowMap, METRIC_P95_RESPONSE_TIME, 3),
                key -> new ArrayList<>()).add(0.95);
        windowPercentiles.computeIfAbsent(getWindow(windowMap, METRIC_P99_RESPONSE_TIME, 3),
                key -> new ArrayList<>()).add(0.99);

        String reqSeconds = "(CASE WHEN REGEXP_LIKE(REQUEST_TIME, '" + NUMERIC_REGEX + "') THEN TO_NUMBER(REQUEST_TIME) END)";
        for (Map.Entry<Integer, List<Double>> entry : windowPercentiles.entrySet()) {
            StringBuilder sql = new StringBuilder("SELECT GROUPING(SERVER_IP) AS IS_SUMMARY, SERVER_IP");
            for (int i = 0; i < entry.getValue().size(); i++) {
                sql.append(", PERCENTILE_CONT(").append(entry.getValue().get(i))
                        .append(") WITHIN GROUP (ORDER BY ").append(reqSeconds).append(") AS P").append(i);
            }
            sql.append(" FROM DAOCLOUD_PLATFORM.T_MT_NGINX_ACCESS_LOG")
                    .append(" WHERE CREATE_TIME >= ? AND CREATE_TIME <= ?").append(ipFilter)
                    .append(" GROUP BY GROUPING SETS ((), (SERVER_IP))");
            try {
                List<Map<String, Object>> rows = nginxLogJdbcTemplate.queryForList(sql.toString(),
                        calcStartTime(endTime, entry.getKey()), endTime);
                for (Map<String, Object> row : rows) {
                    if (isSummaryRow(row)) {
                        applyPercentileColumns(row, entry.getValue(), snapshot.summary);
                        continue;
                    }
                    String serverIp = (String) row.get("SERVER_IP");
                    if (serverIp != null) {
                        applyPercentileColumns(row, entry.getValue(),
                                getOrCreateInstanceMetrics(snapshot.instances, serverIp));
                    }
                }
            } catch (Exception e) {
                System.err.println("计算Nginx百分位指标失败：" + e.getMessage());
            }
        }
    }

    /**
     * 将一行百分位结果写入指标对象（P0 / P1 列与请求的百分位顺序一一对应）
     */
    private void applyPercentileColumns(Map<String, Object> row, List<Double> percentiles, NginxMetrics metrics) {
        for (int i = 0; i < percentiles.size(); i++) {
            String value = formatMillis(doubleOf(row, "P" + i));
            if (percentiles.get(i) == 0.95) {
                metrics.p95ResponseTime = value;
            } else {
                metrics.p99ResponseTime = value;
            }
        }
    }

    /**
     * 获取或创建实例指标对象（默认值：流量指标 0.00，存活 0）
     */
    private NginxMetrics getOrCreateInstanceMetrics(Map<String, NginxMetrics> map, String serverIp) {
        NginxMetrics metrics = map.get(serverIp);
        if (metrics == null) {
            metrics = emptyMetrics();
            map.put(serverIp, metrics);
        }
        return metrics;
    }

    /**
     * 指标对象默认值（窗口内无日志时的落地值）
     */
    private static NginxMetrics emptyMetrics() {
        NginxMetrics metrics = new NginxMetrics();
        metrics.qps = "0.00";
        metrics.errorRate = "0.00";
        metrics.avgResponseTime = "0.00";
        metrics.p95ResponseTime = "0.00";
        metrics.p99ResponseTime = "0.00";
        metrics.slowRequestRate = "0.00";
        metrics.error5xxRate = "0.00";
        metrics.error4xxRate = "0.00";
        metrics.upstreamRespTime = "0.00";
        metrics.upstreamTimeRatio = "0.00";
        metrics.nginxSelfTime = "0.00";
        metrics.upstreamErrorRate = "0.00";
        metrics.upstreamStatusDist = "--";
        metrics.nginxAlive = "0";
        return metrics;
    }

    // ============================================================
    // 聚合结果取值与格式化
    // ============================================================

    /**
     * 汇总行判定：GROUPING(SERVER_IP) = 1 表示该行由 GROUPING SETS 的空维度集合产生
     */
    private boolean isSummaryRow(Map<String, Object> row) {
        Object grouping = row.get("IS_SUMMARY");
        return grouping instanceof Number && ((Number) grouping).intValue() == 1;
    }

    private long longOf(Map<String, Object> row, String column) {
        Object value = row.get(column);
        return value instanceof Number ? ((Number) value).longValue() : 0L;
    }

    private Double doubleOf(Map<String, Object> row, String column) {
        Object value = row.get(column);
        return value instanceof Number ? ((Number) value).doubleValue() : null;
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
     * 秒 → 毫秒（数据库 REQUEST_TIME 单位为秒），保留两位小数
     */
    private String formatMillis(Double seconds) {
        if (seconds == null) {
            return "0.00";
        }
        return String.format("%.2f", new BigDecimal(seconds * 1000)
                .setScale(2, RoundingMode.HALF_UP).doubleValue());
    }

    /**
     * 求和占比：part / whole * 100，保留两位小数；whole 无有效值时返回 0.00
     */
    private String formatSumRatio(Double part, Double whole) {
        if (part == null || whole == null || whole <= 0) {
            return "0.00";
        }
        return String.format("%.2f", BigDecimal.valueOf(part)
                .multiply(new BigDecimal(100))
                .divide(BigDecimal.valueOf(whole), 2, RoundingMode.HALF_UP).doubleValue());
    }

    /**
     * 上游状态码分布：仅统计 200/502/503/504 四类，输出固定顺序与全部四项
     * 格式如：200(96.5%) | 502(2.1%) | 503(0.0%) | 504(1.4%)，无数据返回 "--"
     */
    private String formatUpstreamStatusDist(Map<String, Object> row) {
        String[] statuses = {"200", "502", "503", "504"};
        long[] counts = new long[statuses.length];
        long total = 0;
        for (int i = 0; i < statuses.length; i++) {
            counts[i] = longOf(row, "UP_" + statuses[i]);
            total += counts[i];
        }
        if (total == 0) {
            return "--";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < statuses.length; i++) {
            if (i > 0) sb.append(" | ");
            double pct = new BigDecimal(counts[i]).multiply(new BigDecimal(100))
                    .divide(new BigDecimal(total), 1, RoundingMode.HALF_UP).doubleValue();
            sb.append(statuses[i]).append("(").append(pct).append("%)");
        }
        return sb.toString();
    }

    // ============================================================
    // 客户端指标计算
    // ============================================================

    /**
     * 计算客户端指标
     * <p>
     * 总IP数 / IP活跃数 / 并发连接数 各有自己的时间窗口（默认 1440 / 3 / 1 分钟），
     * 必须按窗口分别聚合：假设用外层 WHERE 取最小窗口后靠 CASE WHEN 区分，
     * 三个指标会被压成同一口径（最小窗口）而失去区分意义。
     *
     * @param ipFilter 与其他集群指标一致的实例 IP 过滤片段（以 AND 开头）；为空表示不过滤
     */
    private ClientMetrics calculateClientMetrics(String endTime, Map<String, Integer> windowMap, String ipFilter) {
        ClientMetrics result = new ClientMetrics();
        try {
            int totalIpWindow = getWindow(windowMap, METRIC_TOTAL_IP, 1440);
            int activeIpWindow = getWindow(windowMap, METRIC_ACTIVE_IP, 3);
            int topIpWindow = getWindow(windowMap, METRIC_TOP_IP, 1440);
            int connWindow = getWindow(windowMap, METRIC_CONCURRENT_CONN, 1);
            int uaWindow = getWindow(windowMap, METRIC_TERMINAL_DIST, 1440);

            // 1. 总IP数：按自身窗口（默认 24 小时）去重计数
            result.totalIp = String.valueOf(countDistinctClientIp(endTime, totalIpWindow, ipFilter));

            // 2. IP活跃数：按自身窗口（默认 3 分钟）去重计数
            result.activeIp = String.valueOf(countDistinctClientIp(endTime, activeIpWindow, ipFilter));

            // 3. 并发连接数：窗口内请求数 / 窗口秒数
            long connCount = countClientRequests(endTime, connWindow, ipFilter);
            int connSeconds = connWindow * 60;
            result.concurrentConn = connCount > 0 && connSeconds > 0
                    ? String.format("%.2f", new BigDecimal(connCount)
                    .divide(new BigDecimal(connSeconds), 2, RoundingMode.HALF_UP).doubleValue())
                    : "0.00";

            // 4. TOP访问IP（按配置窗口，需按 REMOTE_ADDR 分组，单独一次查询）
            String topIpStart = calcStartTime(endTime, topIpWindow);
            String topIpSql = "SELECT * FROM (SELECT REMOTE_ADDR, COUNT(*) AS CNT FROM DAOCLOUD_PLATFORM.T_MT_NGINX_ACCESS_LOG"
                    + " WHERE CREATE_TIME >= ? AND CREATE_TIME <= ?" + ipFilter
                    + " GROUP BY REMOTE_ADDR ORDER BY COUNT(*) DESC, REMOTE_ADDR ASC) WHERE ROWNUM = 1";
            // 窗口内无数据时返回空列表（queryForMap 会抛异常，导致整组客户端指标进入兜底分支）
            List<Map<String, Object>> topIpRows = nginxLogJdbcTemplate.queryForList(topIpSql, topIpStart, endTime);
            if (!topIpRows.isEmpty() && topIpRows.get(0).get("REMOTE_ADDR") != null) {
                Map<String, Object> topIpRow = topIpRows.get(0);
                Object cnt = topIpRow.get("CNT");
                result.topIp = topIpRow.get("REMOTE_ADDR") + "(" + (cnt != null ? cnt : "0") + "次)";
            } else {
                result.topIp = "--";
            }

            // 5. 终端分布 & UA类型（按配置窗口从HTTP_USER_AGENT解析）
            String uaStart = calcStartTime(endTime, uaWindow);
            String uaSql = "SELECT HTTP_USER_AGENT, COUNT(*) AS CNT FROM DAOCLOUD_PLATFORM.T_MT_NGINX_ACCESS_LOG"
                    + " WHERE CREATE_TIME >= ? AND CREATE_TIME <= ? AND HTTP_USER_AGENT IS NOT NULL" + ipFilter
                    + " GROUP BY HTTP_USER_AGENT";
            List<Map<String, Object>> uaList = nginxLogJdbcTemplate.queryForList(uaSql, uaStart, endTime);
            result.terminalDist = calcTerminalDist(uaList);
            result.uaType = calcUaType(uaList);
        } catch (Exception e) {
            System.err.println("计算客户端指标失败：" + e.getMessage());
            result.totalIp = "0";
            result.activeIp = "0";
            result.topIp = "--";
            result.concurrentConn = "0.00";
            result.terminalDist = "--";
            result.uaType = "--";
        }
        return result;
    }

    /**
     * 窗口内去重客户端 IP 数（每个指标使用自己的窗口，不受其他指标影响）
     */
    private long countDistinctClientIp(String endTime, int windowMinutes, String ipFilter) {
        String sql = "SELECT COUNT(DISTINCT REMOTE_ADDR) AS IP_CNT"
                + " FROM DAOCLOUD_PLATFORM.T_MT_NGINX_ACCESS_LOG"
                + " WHERE CREATE_TIME >= ? AND CREATE_TIME <= ?" + ipFilter;
        Map<String, Object> row = nginxLogJdbcTemplate.queryForMap(sql,
                calcStartTime(endTime, windowMinutes), endTime);
        return longOf(row, "IP_CNT");
    }

    /**
     * 窗口内请求数
     */
    private long countClientRequests(String endTime, int windowMinutes, String ipFilter) {
        String sql = "SELECT COUNT(*) AS REQ_CNT"
                + " FROM DAOCLOUD_PLATFORM.T_MT_NGINX_ACCESS_LOG"
                + " WHERE CREATE_TIME >= ? AND CREATE_TIME <= ?" + ipFilter;
        Map<String, Object> row = nginxLogJdbcTemplate.queryForMap(sql,
                calcStartTime(endTime, windowMinutes), endTime);
        return longOf(row, "REQ_CNT");
    }

    // ============================================================
    // 链路上报指标计算（客户端 -> nginx）
    // ============================================================

    /**
     * 计算客户端 -> nginx 链路指标（按 ipFilter 限定集群）
     * 计数与均值指标合并为一次窗口扫描 + 条件聚合，TOP请求路径需分组单独一次查询
     */
    private LinkMetrics calculateLinkMetrics(String endTime, Map<String, Integer> windowMap, String ipFilter) {
        LinkMetrics result = new LinkMetrics();
        try {
            // 1. 请求总数 / 请求失败次数 / 平均耗时 / 成功率：一次窗口扫描 + 条件聚合
            int totalWindow = getWindow(windowMap, LINK_METRIC_REQUEST_TOTAL, 3);
            int failWindow = getWindow(windowMap, LINK_METRIC_REQUEST_FAIL, 3);
            int avgWindow = getWindow(windowMap, LINK_METRIC_REQUEST_AVG_TIME, 3);
            int successWindow = getWindow(windowMap, LINK_METRIC_REQUEST_SUCCESS_RATE, 3);
            // 外层扫描下界取最宽窗口：取最小值会按最小窗口截断行集，使大窗口指标丢数据
            String startTime = calcStartTime(endTime, Math.max(Math.max(totalWindow, failWindow),
                    Math.max(avgWindow, successWindow)));
            String reqSeconds = "(CASE WHEN REGEXP_LIKE(REQUEST_TIME, '" + NUMERIC_REGEX + "') THEN TO_NUMBER(REQUEST_TIME) END)";
            String aggSql = "SELECT "
                    + "COUNT(CASE WHEN CREATE_TIME >= ? THEN 1 END) AS REQ_TOTAL, "
                    + "COUNT(CASE WHEN CREATE_TIME >= ? AND STATUS >= 400 THEN 1 END) AS REQ_FAIL, "
                    + "AVG(CASE WHEN CREATE_TIME >= ? THEN " + reqSeconds + " END) AS AVG_TIME, "
                    + "COUNT(CASE WHEN CREATE_TIME >= ? THEN 1 END) AS SUC_TOTAL, "
                    + "COUNT(CASE WHEN CREATE_TIME >= ? AND STATUS >= 400 THEN 1 END) AS SUC_FAIL "
                    + "FROM DAOCLOUD_PLATFORM.T_MT_NGINX_ACCESS_LOG "
                    + "WHERE CREATE_TIME >= ? AND CREATE_TIME <= ?" + ipFilter;
            Map<String, Object> aggRow = nginxLogJdbcTemplate.queryForMap(aggSql,
                    calcStartTime(endTime, totalWindow), calcStartTime(endTime, failWindow),
                    calcStartTime(endTime, avgWindow), calcStartTime(endTime, successWindow),
                    calcStartTime(endTime, successWindow), startTime, endTime);
            result.requestTotal = String.valueOf(longOf(aggRow, "REQ_TOTAL"));
            result.requestFail = String.valueOf(longOf(aggRow, "REQ_FAIL"));
            result.requestAvgTime = formatMillis(doubleOf(aggRow, "AVG_TIME"));
            long successTotal = longOf(aggRow, "SUC_TOTAL");
            long successFail = longOf(aggRow, "SUC_FAIL");
            result.requestSuccessRate = successTotal > 0
                    ? formatPercent(successTotal - successFail, successTotal)
                    : "100.00";

            // 2. TOP请求路径（去掉查询参数，需按 REQUEST 分组，单独一次查询）
            String pathStart = calcStartTime(endTime, getWindow(windowMap, LINK_METRIC_REQUEST_PATH, 3));
            String topPathSql = "SELECT * FROM (SELECT REQUEST, COUNT(*) AS CNT FROM DAOCLOUD_PLATFORM.T_MT_NGINX_ACCESS_LOG WHERE CREATE_TIME >= ? AND CREATE_TIME <= ?" + ipFilter
                    + " GROUP BY REQUEST ORDER BY COUNT(*) DESC, REQUEST ASC) WHERE ROWNUM = 1";
            Map<String, Object> topPathRow = nginxLogJdbcTemplate.queryForMap(topPathSql, pathStart, endTime);
            if (topPathRow != null && topPathRow.get("REQUEST") != null) {
                String request = (String) topPathRow.get("REQUEST");
                Object cnt = topPathRow.get("CNT");
                result.requestPath = extractPath(request) + "(" + (cnt != null ? cnt : "0") + "次)";
            } else {
                result.requestPath = "--";
            }
        } catch (Exception e) {
            System.err.println("计算链路指标失败：" + e.getMessage());
            result.requestPath = "--";
            result.requestTotal = "0";
            result.requestFail = "0";
            result.requestAvgTime = "0.00";
            result.requestSuccessRate = "100.00";
        }
        return result;
    }

    // ============================================================
    // 通用 SQL 指标计算（均支持 ipFilter 集群过滤）
    // ============================================================

    /**
     * 查询远程日志表最大入库时间（全表）
     */
    private String queryMaxCreateTime() {
        String sql = "SELECT MAX(CREATE_TIME) FROM DAOCLOUD_PLATFORM.T_MT_NGINX_ACCESS_LOG";
        try {
            return nginxLogJdbcTemplate.queryForObject(sql, String.class);
        } catch (Exception e) {
            System.err.println("查询远程日志最大时间失败：" + e.getMessage());
            return null;
        }
    }

    /**
     * 计算起始时间：基于日志时间字符串减指定分钟数
     * 数据库 CREATE_TIME 为 VARCHAR2，格式：2026-07-08 13:54:37
     */
    private String calcStartTime(String maxCreateTime, int minutes) {
        try {
            // 截取前19位作为日期时间部分：2026-07-08 13:54:37
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
            System.err.println("calcStartTime 解析失败，原值=" + maxCreateTime + "，异常=" + e.getMessage());
            return maxCreateTime;
        }
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

    // ============================================================
    // 工具方法
    // ============================================================

    /**
     * 构建集群实例 IP 过滤条件（IP 来自常量，无注入风险）
     */
    private String buildIpFilter(List<String> ips) {
        if (ips == null || ips.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(" AND SERVER_IP IN (");
        for (int i = 0; i < ips.size(); i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append("'").append(ips.get(i).replace("'", "")).append("'");
        }
        sb.append(")");
        return sb.toString();
    }

    private int getWindow(Map<String, Integer> windowMap, String metricName, int defaultWindow) {
        if (windowMap == null) return defaultWindow;
        Integer w = windowMap.get(metricName);
        return w != null && w > 0 ? w : defaultWindow;
    }

    /**
     * 节点指标添加（汇总维度，serverIp 为 null）
     */
    private void addNodeMetric(List<MetricValueDTO> list, Map<String, String> metricMap,
                               String nodeId, String metricName, String value, String sourceTime) {
        addNodeMetric(list, metricMap, nodeId, metricName, value, sourceTime, null);
    }

    /**
     * 节点指标添加（含实例IP维度）
     */
    private void addNodeMetric(List<MetricValueDTO> list, Map<String, String> metricMap,
                               String nodeId, String metricName, String value, String sourceTime, String serverIp) {
        String metricId = metricMap.get(metricName);
        if (metricId != null) {
            MetricValueDTO dto = new MetricValueDTO();
            dto.setNodeId(nodeId);
            dto.setMetricId(metricId);
            dto.setMetricValue(value);
            dto.setSourceTime(sourceTime);
            dto.setServerIp(serverIp);
            list.add(dto);
        }
    }

    /**
     * 链路指标添加
     */
    private void addLinkMetric(List<MetricValueDTO> list, Map<String, String> metricMap,
                               String linkId, String metricName, String value, String sourceTime) {
        String metricId = metricMap.get(metricName);
        if (metricId != null) {
            MetricValueDTO dto = new MetricValueDTO();
            dto.setLinkId(linkId);
            dto.setMetricId(metricId);
            dto.setMetricValue(value);
            dto.setSourceTime(sourceTime);
            list.add(dto);
        }
    }

    private String extractPath(String request) {
        if (request == null) return "";
        int qIdx = request.indexOf('?');
        return qIdx > 0 ? request.substring(0, qIdx) : request;
    }

    /**
     * 生成采集批次ID：COLLECT + 年月日时分秒
     */
    private String generateCollectId() {
        return "COLLECT" + new SimpleDateFormat("yyyyMMddHHmmss").format(new Date());
    }

    /**
     * 从UA列表计算终端分布（TOP3占比）
     */
    private String calcTerminalDist(List<Map<String, Object>> uaList) {
        if (uaList == null || uaList.isEmpty()) return "--";
        Map<String, Integer> osMap = new HashMap<>();
        int total = 0;
        for (Map<String, Object> row : uaList) {
            String ua = (String) row.get("HTTP_USER_AGENT");
            Number cntObj = (Number) row.get("CNT");
            int cnt = cntObj != null ? cntObj.intValue() : 0;
            if (ua == null || cnt <= 0) continue;
            total += cnt;
            String os = parseOsType(ua);
            osMap.put(os, osMap.getOrDefault(os, 0) + cnt);
        }
        if (total == 0) return "--";
        return formatTop3(osMap, total);
    }

    /**
     * 从UA列表计算浏览器类型分布（TOP3占比）
     */
    private String calcUaType(List<Map<String, Object>> uaList) {
        if (uaList == null || uaList.isEmpty()) return "--";
        Map<String, Integer> browserMap = new HashMap<>();
        int total = 0;
        for (Map<String, Object> row : uaList) {
            String ua = (String) row.get("HTTP_USER_AGENT");
            Number cntObj = (Number) row.get("CNT");
            int cnt = cntObj != null ? cntObj.intValue() : 0;
            if (ua == null || cnt <= 0) continue;
            total += cnt;
            String browser = parseBrowserType(ua);
            browserMap.put(browser, browserMap.getOrDefault(browser, 0) + cnt);
        }
        if (total == 0) return "--";
        return formatTop3(browserMap, total);
    }

    private String parseOsType(String ua) {
        String u = ua.toLowerCase();
        if (u.contains("iphone") || u.contains("ipad") || u.contains("ipod")) return "iOS";
        if (u.contains("android")) return "Android";
        if (u.contains("windows")) return "Windows";
        if (u.contains("macintosh") || u.contains("mac os")) return "Mac";
        if (u.contains("linux")) return "Linux";
        return "Other";
    }

    private String parseBrowserType(String ua) {
        String u = ua.toLowerCase();
        if (u.contains("edg/")) return "Edge";
        if (u.contains("firefox")) return "Firefox";
        if (u.contains("chrome")) return "Chrome";
        if (u.contains("safari")) return "Safari";
        return "Other";
    }

    private String formatTop3(Map<String, Integer> map, int total) {
        List<Map.Entry<String, Integer>> list = new ArrayList<>(map.entrySet());
        // 次数降序；并列时按名称升序，保证每轮采集的展示顺序稳定
        list.sort((a, b) -> {
            int cmp = b.getValue().compareTo(a.getValue());
            return cmp != 0 ? cmp : a.getKey().compareTo(b.getKey());
        });
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(3, list.size()); i++) {
            if (i > 0) sb.append(" | ");
            Map.Entry<String, Integer> e = list.get(i);
            double pct = new BigDecimal(e.getValue()).multiply(new BigDecimal(100))
                    .divide(new BigDecimal(total), 1, RoundingMode.HALF_UP).doubleValue();
            sb.append(e.getKey()).append("(").append(pct).append("%)");
        }
        return sb.toString();
    }

    // ============================================================
    // 指标数据对象
    // ============================================================

    /**
     * Nginx指标数据对象
     */
    private static class NginxMetrics {
        String qps;
        String errorRate;
        String avgResponseTime;
        String p95ResponseTime;
        String p99ResponseTime;
        String slowRequestRate;
        String error5xxRate;
        String error4xxRate;
        String upstreamRespTime;
        String upstreamTimeRatio;
        String nginxSelfTime;
        String upstreamErrorRate;
        String upstreamStatusDist;
        String nginxAlive;
    }

    /**
     * 客户端指标数据对象
     */
    private static class ClientMetrics {
        String totalIp;
        String activeIp;
        String topIp;
        String concurrentConn;
        String terminalDist;
        String uaType;
    }

    /**
     * 链路指标数据对象（客户端 -> nginx）
     */
    private static class LinkMetrics {
        String requestPath;
        String requestTotal;
        String requestFail;
        String requestAvgTime;
        String requestSuccessRate;
    }
}
