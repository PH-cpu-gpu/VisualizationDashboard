package com.gjzg.service.impl;

import com.alibaba.fastjson.JSONObject;
import com.gjdf.daocloud.common.base.api.pojo.JsonResult;
import com.gjzg.api.MonitorService;
import com.gjzg.api.entity.LinkMetricVO;
import com.gjzg.api.entity.MetricHistoryVO;
import com.gjzg.api.entity.MetricValueDTO;
import com.gjzg.api.entity.NodeMetricVO;
import com.gjzg.entity.AlertRecord;
import com.gjzg.entity.AlertRule;
import com.gjzg.entity.LinkInfo;
import com.gjzg.entity.NodeInfo;
import com.gjzg.mapper.AlertMapper;
import com.gjzg.mapper.LinkInfoMapper;
import com.gjzg.mapper.MonitorMapper;
import com.gjzg.mapper.NodeInfoMapper;
import com.gjzg.service.AlertNotifyService;
import com.gjzg.service.AlertStreakState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;

/**
 * 监控大屏数据服务实现类（纯Spring本地服务）
 */
@Component
public class MonitorServiceImpl implements MonitorService {

    private static final Logger log = LoggerFactory.getLogger(MonitorServiceImpl.class);

    @Autowired
    private MonitorMapper monitorMapper;

    @Autowired
    private AlertMapper alertMapper;

    @Autowired
    private NodeInfoMapper nodeInfoMapper;

    @Autowired
    private LinkInfoMapper linkInfoMapper;

    @Autowired
    private AlertStreakState streakState;

    @Autowired
    private AlertNotifyService alertNotifyService;

    @Override
    public JsonResult queryAllNodeMetrics(JSONObject requestParam) {
        try {
            String serverIp = requestParam != null ? requestParam.getString("serverIp") : null;
            List<NodeMetricVO> list = monitorMapper.selectAllNodeMetrics(serverIp);
            return JsonResult.success("查询成功", list);
        } catch (Exception e) {
            return JsonResult.fail("查询节点指标失败：" + e.getMessage());
        }
    }

    @Override
    public JsonResult queryNodeMetricsById(JSONObject requestParam) {
        try {
            String nodeId = requestParam.getString("nodeId");
            if (nodeId == null || nodeId.trim().isEmpty()) {
                return JsonResult.fail("nodeId不能为空");
            }
            String serverIp = requestParam.getString("serverIp");
            List<NodeMetricVO> list = monitorMapper.selectNodeMetricsByNodeId(nodeId, serverIp);
            return JsonResult.success("查询成功", list);
        } catch (Exception e) {
            return JsonResult.fail("查询节点指标失败：" + e.getMessage());
        }
    }

    @Override
    public JsonResult queryNodeServerIps(JSONObject requestParam) {
        try {
            String nodeId = requestParam.getString("nodeId");
            if (nodeId == null || nodeId.trim().isEmpty()) {
                return JsonResult.fail("nodeId不能为空");
            }
            List<String> list = monitorMapper.selectDistinctServerIps(nodeId);
            return JsonResult.success("查询成功", list);
        } catch (Exception e) {
            return JsonResult.fail("查询节点实例列表失败：" + e.getMessage());
        }
    }

    @Override
    public JsonResult queryAllLinkMetrics(JSONObject requestParam) {
        try {
            List<LinkMetricVO> list = monitorMapper.selectAllLinkMetrics();
            return JsonResult.success("查询成功", list);
        } catch (Exception e) {
            return JsonResult.fail("查询链路指标失败：" + e.getMessage());
        }
    }

    @Override
    public JsonResult queryLinkMetricsById(JSONObject requestParam) {
        try {
            String linkId = requestParam.getString("linkId");
            if (linkId == null || linkId.trim().isEmpty()) {
                return JsonResult.fail("linkId不能为空");
            }
            List<LinkMetricVO> list = monitorMapper.selectLinkMetricsByLinkId(linkId);
            return JsonResult.success("查询成功", list);
        } catch (Exception e) {
            return JsonResult.fail("查询链路指标失败：" + e.getMessage());
        }
    }

    @Override
    public JsonResult collectNodeMetrics(List<MetricValueDTO> list) {
        try {
            if (list == null || list.isEmpty()) {
                return JsonResult.fail("上报数据不能为空");
            }
            // 生成统一采集批次ID
            String collectId = generateCollectId();
            // 1. 写入历史表（追加）
            monitorMapper.batchInsertNodeHistory(list, collectId);
            // 2. 写入最新表（MERGE，单语句完成更新/插入，无空窗期）
            monitorMapper.mergeNodeLatest(list, collectId);
            // 3. 告警评估（评估失败不影响采集，仅记录日志）
            try { evaluateNodeAlerts(list); } catch (Exception ex) { log.error("节点指标告警评估失败", ex); }
            return JsonResult.success("节点指标采集成功", collectId);
        } catch (Exception e) {
            return JsonResult.fail("节点指标采集失败：" + e.getMessage());
        }
    }

    @Override
    public JsonResult collectLinkMetrics(List<MetricValueDTO> list) {
        try {
            if (list == null || list.isEmpty()) {
                return JsonResult.fail("上报数据不能为空");
            }
            // 生成统一采集批次ID
            String collectId = generateCollectId();
            // 1. 写入历史表（追加）
            monitorMapper.batchInsertLinkHistory(list, collectId);
            // 2. 写入最新表（MERGE，单语句完成更新/插入，无空窗期）
            monitorMapper.mergeLinkLatest(list, collectId);
            // 3. 告警评估（评估失败不影响采集，仅记录日志）
            try { evaluateLinkAlerts(list); } catch (Exception ex) { log.error("链路指标告警评估失败", ex); }
            return JsonResult.success("链路指标采集成功", collectId);
        } catch (Exception e) {
            return JsonResult.fail("链路指标采集失败：" + e.getMessage());
        }
    }

    @Override
    public JsonResult queryNodeMetricHistory(JSONObject requestParam) {
        try {
            String nodeId = requestParam.getString("nodeId");
            String metricName = requestParam.getString("metricName");
            String date = requestParam.getString("date");
            if (nodeId == null || nodeId.trim().isEmpty()) {
                return JsonResult.fail("nodeId不能为空");
            }
            if (metricName == null || metricName.trim().isEmpty()) {
                return JsonResult.fail("metricName不能为空");
            }
            if (date == null || date.trim().isEmpty()) {
                return JsonResult.fail("date不能为空");
            }
            String startTime = date + " 00:00:00";
            String endTime = date + " 23:59:59";
            // serverIp 可选：为空时查汇总维度，非空时查指定实例
            String serverIp = requestParam.getString("serverIp");
            List<MetricHistoryVO> list = monitorMapper.selectNodeMetricHistory(nodeId, metricName, startTime, endTime, serverIp);
            return JsonResult.success("查询成功", list);
        } catch (Exception e) {
            return JsonResult.fail("查询节点指标历史失败：" + e.getMessage());
        }
    }

    @Override
    public JsonResult queryLinkMetricHistory(JSONObject requestParam) {
        try {
            String linkId = requestParam.getString("linkId");
            String metricName = requestParam.getString("metricName");
            String date = requestParam.getString("date");
            if (linkId == null || linkId.trim().isEmpty()) {
                return JsonResult.fail("linkId不能为空");
            }
            if (metricName == null || metricName.trim().isEmpty()) {
                return JsonResult.fail("metricName不能为空");
            }
            if (date == null || date.trim().isEmpty()) {
                return JsonResult.fail("date不能为空");
            }
            String startTime = date + " 00:00:00";
            String endTime = date + " 23:59:59";
            List<MetricHistoryVO> list = monitorMapper.selectLinkMetricHistory(linkId, metricName, startTime, endTime);
            return JsonResult.success("查询成功", list);
        } catch (Exception e) {
            return JsonResult.fail("查询链路指标历史失败：" + e.getMessage());
        }
    }

    @Override
    public JsonResult queryNodeMetricsTrend(JSONObject requestParam) {
        try {
            String nodeId = requestParam.getString("nodeId");
            int hours = requestParam.getIntValue("hours");
            if (nodeId == null || nodeId.trim().isEmpty()) {
                return JsonResult.fail("nodeId不能为空");
            }
            if (hours <= 0) hours = 1;
            // serverIp 可选：为空时查汇总维度，非空时查指定实例
            String serverIp = requestParam.getString("serverIp");
            List<MetricHistoryVO> list = monitorMapper.selectNodeMetricsTrend(nodeId, hours, serverIp);
            return JsonResult.success("查询成功", list);
        } catch (Exception e) {
            return JsonResult.fail("查询节点指标趋势失败：" + e.getMessage());
        }
    }

    @Override
    public JsonResult queryLinkMetricsTrend(JSONObject requestParam) {
        try {
            String linkId = requestParam.getString("linkId");
            int hours = requestParam.getIntValue("hours");
            if (linkId == null || linkId.trim().isEmpty()) {
                return JsonResult.fail("linkId不能为空");
            }
            if (hours <= 0) hours = 1;
            List<MetricHistoryVO> list = monitorMapper.selectLinkMetricsTrend(linkId, hours);
            return JsonResult.success("查询成功", list);
        } catch (Exception e) {
            return JsonResult.fail("查询链路指标趋势失败：" + e.getMessage());
        }
    }

    // ========== 告警评估逻辑 ==========

    /**
     * 评估节点指标是否触发告警（供采集流程与上报接口复用）
     */
    public void evaluateNodeAlerts(List<MetricValueDTO> metrics) {
        // 按 nodeId 分组
        java.util.Map<String, java.util.List<MetricValueDTO>> grouped = new java.util.HashMap<>();
        for (MetricValueDTO dto : metrics) {
            grouped.computeIfAbsent(dto.getNodeId(), k -> new java.util.ArrayList<>()).add(dto);
        }
        for (java.util.Map.Entry<String, java.util.List<MetricValueDTO>> entry : grouped.entrySet()) {
            String nodeId = entry.getKey();
            // 查询节点名称作为告警目标名（查询失败时回退为节点ID）
            String targetName = nodeId;
            try {
                NodeInfo node = nodeInfoMapper.selectById(nodeId);
                if (node != null && node.getNodeName() != null && !node.getNodeName().isEmpty()) {
                    targetName = node.getNodeName();
                }
            } catch (Exception e) {
                log.warn("查询节点名称失败，告警目标名使用节点ID: " + nodeId);
            }
            // 查询该节点的指标定义映射 metricId -> metricName
            java.util.Map<String, String> metricNameMap = new java.util.HashMap<>();
            java.util.Set<String> evaluated = new java.util.HashSet<>();
            List<java.util.Map<String, Object>> defList = monitorMapper.selectNodeMetricDefMap(nodeId);
            if (defList != null) {
                for (java.util.Map<String, Object> def : defList) {
                    String mid = String.valueOf(def.get("METRIC_ID"));
                    String mname = String.valueOf(def.get("METRIC_NAME"));
                    metricNameMap.put(mid, mname);
                }
            }
            for (MetricValueDTO dto : entry.getValue()) {
                String metricName = metricNameMap.get(dto.getMetricId());
                if (metricName == null) continue;
                // 同一采集批次内同一指标 + 实例只评估一次，防止重复上报把"连续周期"计数刷高
                if (!evaluated.add(metricName + "|" + (dto.getServerIp() == null ? "#" : dto.getServerIp().trim()))) continue;
                evaluateSingleMetric("node", nodeId, metricName, dto.getMetricValue(), targetName, dto.getServerIp());
            }
        }
    }

    /**
     * 评估链路指标是否触发告警（供采集流程与上报接口复用）
     */
    public void evaluateLinkAlerts(List<MetricValueDTO> metrics) {
        java.util.Map<String, java.util.List<MetricValueDTO>> grouped = new java.util.HashMap<>();
        for (MetricValueDTO dto : metrics) {
            grouped.computeIfAbsent(dto.getLinkId(), k -> new java.util.ArrayList<>()).add(dto);
        }
        for (java.util.Map.Entry<String, java.util.List<MetricValueDTO>> entry : grouped.entrySet()) {
            String linkId = entry.getKey();
            // 查询链路名称作为告警目标名（查询失败时回退为链路ID）
            String targetName = linkId;
            try {
                LinkInfo link = linkInfoMapper.selectById(linkId);
                if (link != null && link.getLinkName() != null && !link.getLinkName().isEmpty()) {
                    targetName = link.getLinkName();
                }
            } catch (Exception e) {
                log.warn("查询链路名称失败，告警目标名使用链路ID: " + linkId);
            }
            java.util.Map<String, String> metricNameMap = new java.util.HashMap<>();
            java.util.Set<String> evaluated = new java.util.HashSet<>();
            List<java.util.Map<String, Object>> defList = monitorMapper.selectLinkMetricDefMap(linkId);
            if (defList != null) {
                for (java.util.Map<String, Object> def : defList) {
                    String mid = String.valueOf(def.get("METRIC_ID"));
                    String mname = String.valueOf(def.get("METRIC_NAME"));
                    metricNameMap.put(mid, mname);
                }
            }
            for (MetricValueDTO dto : entry.getValue()) {
                String metricName = metricNameMap.get(dto.getMetricId());
                if (metricName == null) continue;
                // 同一采集批次内同一指标只评估一次，防止重复上报把"连续周期"计数刷高
                if (!evaluated.add(metricName)) continue;
                evaluateSingleMetric("link", linkId, metricName, dto.getMetricValue(), targetName, null);
            }
        }
    }

    /**
     * 评估单个指标是否触发告警
     * serverIp 为空表示节点/链路汇总维度，非空表示具体实例维度
     */
    private void evaluateSingleMetric(String targetType, String targetId, String metricName, String metricValue, String targetName, String serverIp) {
        List<AlertRule> rules = alertMapper.selectEnabledRules(targetType, targetId);
        for (AlertRule rule : rules) {
            if (!rule.getMetricName().equals(metricName)) continue;
            // 持续时间抑制：连续 N 个周期超阈才触发；恢复抑制：连续 N 个周期正常才解除
            int needPeriods = normalizePeriods(rule.getTriggerPeriods());
            int recoverPeriods = normalizePeriods(rule.getRecoverPeriods());
            boolean triggered = compareValue(metricValue, rule.getOperator(), rule.getThresholdValue());
            // 按规则 + 实例维度去重：汇总与各实例、同一指标的多个级别互不影响
            List<AlertRecord> activeAlerts = alertMapper.selectActiveAlertsByRule(rule.getId(), serverIp);
            // 连续周期计数键与去重维度一致（规则 + 实例）
            String streakKey = streakState.keyOf(targetType, targetId, rule.getId(), serverIp);
            if (triggered) {
                // 连续超阈计数 +1（同时清零恢复计数）；未连续满 N 个周期不告警
                int streak = streakState.incTrigger(streakKey, needPeriods);
                if (streak < needPeriods) continue;
                // 如果已有活跃告警则不重复创建
                if (activeAlerts.isEmpty()) {
                    AlertRecord record = new AlertRecord();
                    record.setRuleId(rule.getId());
                    record.setTargetType(targetType);
                    record.setTargetId(targetId);
                    record.setTargetName(targetName != null ? targetName : targetId);
                    record.setServerIp(serverIp);
                    record.setMetricName(metricName);
                    record.setMetricValue(metricValue);
                    record.setThresholdValue(rule.getThresholdValue());
                    record.setOperator(rule.getOperator());
                    record.setAlertLevel(rule.getAlertLevel());
                    // 快照本次触发所需的连续周期数，便于告警记录自证“连续 N 周期才报”
                    record.setTriggerPeriods(needPeriods);
                    alertMapper.insertAlertRecord(record);
                    // 异步推送告警通知（队列 + 后台线程，失败不影响采集）
                    alertNotifyService.notifyTriggered(record);
                }
            } else {
                // 连续正常计数 +1（同时清零超阈计数）；未连续满 N 个周期则维持告警
                int streak = streakState.incRecover(streakKey, recoverPeriods);
                if (streak < recoverPeriods) continue;
                // 指标恢复正常，自动关闭活跃告警（告警只由指标恢复来自动解除，无人工恢复）
                for (AlertRecord active : activeAlerts) {
                    // 先推送恢复通知，再关闭记录（此时记录仍带触发时的完整信息）
                    alertNotifyService.notifyRecovered(active);
                    alertMapper.resolveAlert(active.getId());
                }
            }
        }
    }

    /**
     * 比较指标值与阈值
     */
    private boolean compareValue(String metricValue, String operator, String threshold) {
        try {
            double val = Double.parseDouble(metricValue);
            double thd = Double.parseDouble(threshold);
            switch (operator) {
                case ">":  return val > thd;
                case ">=": return val >= thd;
                case "<":  return val < thd;
                case "<=": return val <= thd;
                case "==": return Math.abs(val - thd) < 0.0001;
                case "!=": return Math.abs(val - thd) >= 0.0001;
                default: return false;
            }
        } catch (NumberFormatException e) {
            // 非数值型指标，做字符串比较
            switch (operator) {
                case "==": return metricValue != null && metricValue.equals(threshold);
                case "!=": return metricValue != null && !metricValue.equals(threshold);
                default: return false;
            }
        }
    }

    /**
     * 归一化连续周期数：null 或非法值按 1 处理（兼容存量规则），上限 1440 个周期
     */
    private int normalizePeriods(Integer periods) {
        if (periods == null || periods < 1) return 1;
        return Math.min(periods, AlertStreakState.MAX_PERIODS);
    }

    /**
     * 生成采集批次ID：COLLECT + 年月日时分秒
     */
    private String generateCollectId() {
        return "COLLECT" + new SimpleDateFormat("yyyyMMddHHmmss").format(new Date());
    }
}
