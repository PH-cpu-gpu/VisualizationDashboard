package com.gjzg.controller;

import com.alibaba.fastjson.JSONObject;
import com.gjdf.daocloud.common.base.api.pojo.JsonResult;
import com.gjzg.api.MonitorService;
import com.gjzg.api.entity.MetricValueDTO;
import com.gjzg.entity.AlertRecord;
import com.gjzg.entity.AlertRule;
import com.gjzg.entity.LinkInfo;
import com.gjzg.mapper.AlertMapper;
import com.gjzg.mapper.LinkInfoMapper;
import com.gjzg.service.AlertStreakState;
import com.gjzg.service.TopologyAdminService;
import com.gjzg.service.impl.MonitorServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 运维监控HTTP接口控制器
 */
@RestController
@RequestMapping("/api/monitor")
@CrossOrigin(origins = "*")
public class MonitorController {

    @Autowired
    private MonitorServiceImpl monitorService;

    @Autowired
    private LinkInfoMapper linkInfoMapper;

    @Autowired
    private AlertMapper alertMapper;

    @Autowired
    private TopologyAdminService topologyAdminService;

    @Autowired
    private AlertStreakState streakState;

    /**
     * 健康检查测试
     */
    @GetMapping("/test")
    public String test() {
        return "ok";
    }

    /**
     * 查询拓扑图所有节点（含坐标、样式、绑定类型）
     * bindType 来自 T_MT_NODE_INFO 的绑定属性列（IP / LOG_FILTER），前端据此识别 1.0 分站等虚拟节点
     */
    @GetMapping("/topology/nodes")
    public JsonResult queryTopologyNodes() {
        return JsonResult.success("查询成功", topologyAdminService.listNodesWithBinding());
    }

    /**
     * 查询拓扑图所有链路关系
     */
    @GetMapping("/topology/links")
    public JsonResult queryTopologyLinks() {
        List<LinkInfo> list = linkInfoMapper.selectAll();
        return JsonResult.success("查询成功", list);
    }

    /**
     * 查询所有节点的最新指标值（可选参数 serverIp，不传则返回全部实例汇总）
     */
    @GetMapping("/display/nodeMetrics")
    public JsonResult queryAllNodeMetrics(@RequestParam(required = false) String serverIp) {
        JSONObject params = new JSONObject();
        params.put("serverIp", serverIp);
        return monitorService.queryAllNodeMetrics(params);
    }

    /**
     * 根据节点ID查询该节点的所有最新指标值（可选参数 serverIp，不传则返回汇总）
     */
    @GetMapping("/display/nodeMetrics/{nodeId}")
    public JsonResult queryNodeMetricsById(@PathVariable String nodeId,
                                           @RequestParam(required = false) String serverIp) {
        JSONObject params = new JSONObject();
        params.put("nodeId", nodeId);
        params.put("serverIp", serverIp);
        return monitorService.queryNodeMetricsById(params);
    }

    /**
     * 查询指定节点的所有实例IP列表（用于前端实例下拉选择）
     */
    @GetMapping("/display/nodeMetrics/{nodeId}/instances")
    public JsonResult queryNodeServerIps(@PathVariable String nodeId) {
        JSONObject params = new JSONObject();
        params.put("nodeId", nodeId);
        return monitorService.queryNodeServerIps(params);
    }

    /**
     * 查询所有链路的最新指标值
     */
    @GetMapping("/display/linkMetrics")
    public JsonResult queryAllLinkMetrics() {
        return monitorService.queryAllLinkMetrics(new JSONObject());
    }

    /**
     * 根据链路ID查询该链路的所有最新指标值
     */
    @GetMapping("/display/linkMetrics/{linkId}")
    public JsonResult queryLinkMetricsById(@PathVariable String linkId) {
        JSONObject params = new JSONObject();
        params.put("linkId", linkId);
        return monitorService.queryLinkMetricsById(params);
    }

    /**
     * 批量采集节点指标（同时写入history和latest）
     */
    @PostMapping("/collect/nodeMetrics")
    public JsonResult collectNodeMetrics(@RequestBody List<MetricValueDTO> list) {
        return monitorService.collectNodeMetrics(list);
    }

    /**
     * 批量采集链路指标（同时写入history和latest）
     */
    @PostMapping("/collect/linkMetrics")
    public JsonResult collectLinkMetrics(@RequestBody List<MetricValueDTO> list) {
        return monitorService.collectLinkMetrics(list);
    }
    // ========== 指标历史趋势图接口 ==========

    /**
     * 查询节点某指标在某日期的历史数据（用于趋势图）
     * serverIp 可选：不传查汇总维度，传入查指定实例维度
     */
    @GetMapping("/history/node")
    public JsonResult queryNodeMetricHistory(@RequestParam String nodeId, @RequestParam String metricName,
                                             @RequestParam String date,
                                             @RequestParam(required = false) String serverIp) {
        JSONObject params = new JSONObject();
        params.put("nodeId", nodeId);
        params.put("metricName", metricName);
        params.put("date", date);
        params.put("serverIp", serverIp);
        return monitorService.queryNodeMetricHistory(params);
    }

    /**
     * 查询链路某指标在某日期的历史数据（用于趋势图）
     */
    @GetMapping("/history/link")
    public JsonResult queryLinkMetricHistory(@RequestParam String linkId, @RequestParam String metricName, @RequestParam String date) {
        JSONObject params = new JSONObject();
        params.put("linkId", linkId);
        params.put("metricName", metricName);
        params.put("date", date);
        return monitorService.queryLinkMetricHistory(params);
    }

    // ========== 详情面板批量趋势图接口 ==========

    /**
     * 查询节点所有指标在最近N小时的历史数据（用于详情面板）
     * serverIp 可选：不传查汇总维度，传入查指定实例维度
     */
    @GetMapping("/trend/node")
    public JsonResult queryNodeMetricsTrend(@RequestParam String nodeId,
                                            @RequestParam(defaultValue = "1") int hours,
                                            @RequestParam(required = false) String serverIp) {
        JSONObject params = new JSONObject();
        params.put("nodeId", nodeId);
        params.put("hours", hours);
        params.put("serverIp", serverIp);
        return monitorService.queryNodeMetricsTrend(params);
    }

    /**
     * 查询链路所有指标在最近N小时的历史数据（用于详情面板）
     */
    @GetMapping("/trend/link")
    public JsonResult queryLinkMetricsTrend(@RequestParam String linkId, @RequestParam(defaultValue = "1") int hours) {
        JSONObject params = new JSONObject();
        params.put("linkId", linkId);
        params.put("hours", hours);
        return monitorService.queryLinkMetricsTrend(params);
    }

    // ========== 告警规则接口 ==========

    /**
     * 查询所有告警规则
     */
    @GetMapping("/alert/rules")
    public JsonResult queryAlertRules() {
        try {
            List<AlertRule> list = alertMapper.selectAllRules();
            return JsonResult.success("查询成功", list);
        } catch (Exception e) {
            return JsonResult.fail("查询告警规则失败：" + e.getMessage());
        }
    }

    /**
     * 查询某目标的告警规则
     */
    @GetMapping("/alert/rules/target")
    public JsonResult queryAlertRulesByTarget(@RequestParam String targetType, @RequestParam String targetId) {
        try {
            // 查询全部规则（包含禁用的），过滤出该目标的规则，便于前端展示启用状态
            List<AlertRule> allRules = alertMapper.selectAllRules();
            // 过滤出该目标的规则
            java.util.List<AlertRule> filtered = new java.util.ArrayList<>();
            for (AlertRule r : allRules) {
                if (targetType.equals(r.getTargetType()) && targetId.equals(r.getTargetId())) {
                    filtered.add(r);
                }
            }
            return JsonResult.success("查询成功", filtered);
        } catch (Exception e) {
            return JsonResult.fail("查询告警规则失败：" + e.getMessage());
        }
    }

    /**
     * 新增告警规则
     */
    @PostMapping("/alert/rules")
    public JsonResult createAlertRule(@RequestBody AlertRule rule) {
        try {
            applyPeriodDefaults(rule);
            alertMapper.insertRule(rule);
            return JsonResult.success("创建成功", rule.getId());
        } catch (Exception e) {
            return JsonResult.fail("创建告警规则失败：" + e.getMessage());
        }
    }

    /**
     * 修改告警规则
     */
    @PutMapping("/alert/rules/{id}")
    public JsonResult updateAlertRule(@PathVariable String id, @RequestBody AlertRule rule) {
        try {
            rule.setId(id);
            applyPeriodDefaults(rule);
            alertMapper.updateRule(rule);
            // 阈值/周期/指标可能被改，旧的连续周期计数失去意义，必须清零
            streakState.clearByRule(id);
            return JsonResult.success("更新成功", id);
        } catch (Exception e) {
            return JsonResult.fail("更新告警规则失败：" + e.getMessage());
        }
    }

    /**
     * 删除告警规则
     */
    @DeleteMapping("/alert/rules/{id}")
    public JsonResult deleteAlertRule(@PathVariable String id) {
        try {
            // 先解除告警记录对该规则的外键引用（保留历史记录），否则会报 ORA-02292 违反完整约束条件
            alertMapper.detachAlertRecordsByRuleId(id);
            alertMapper.deleteRule(id);
            // 规则已删，清掉其连续周期计数，避免内存态残留
            streakState.clearByRule(id);
            return JsonResult.success("删除成功", id);
        } catch (Exception e) {
            return JsonResult.fail("删除告警规则失败：" + e.getMessage());
        }
    }

    // ========== 告警记录接口 ==========

    /**
     * 查询当前活跃告警
     */
    @GetMapping("/alert/active")
    public JsonResult queryActiveAlerts() {
        try {
            List<AlertRecord> list = alertMapper.selectAllActiveAlerts();
            return JsonResult.success("查询成功", list);
        } catch (Exception e) {
            return JsonResult.fail("查询活跃告警失败：" + e.getMessage());
        }
    }

    /**
     * 查询告警历史
     */
    @GetMapping("/alert/history")
    public JsonResult queryAlertHistory(@RequestParam(defaultValue = "100") int limit) {
        try {
            List<AlertRecord> list = alertMapper.selectAlertHistory(limit);
            return JsonResult.success("查询成功", list);
        } catch (Exception e) {
            return JsonResult.fail("查询告警历史失败：" + e.getMessage());
        }
    }

    /**
     * 归一化连续周期数：null/非法值按 1 处理（兼容存量调用方），上限 MAX_PERIODS
     */
    private void applyPeriodDefaults(AlertRule rule) {
        Integer trigger = rule.getTriggerPeriods();
        Integer recover = rule.getRecoverPeriods();
        if (trigger == null || trigger < 1) trigger = 1;
        if (recover == null || recover < 1) recover = 1;
        rule.setTriggerPeriods(Math.min(trigger, AlertStreakState.MAX_PERIODS));
        rule.setRecoverPeriods(Math.min(recover, AlertStreakState.MAX_PERIODS));
    }

}
