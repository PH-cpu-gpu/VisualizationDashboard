package com.gjzg.entity;

import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * 告警记录实体
 */
public class AlertRecord {

    private String id;
    private String ruleId;
    private String targetType;   // node / link
    private String targetId;
    private String targetName;
    private String serverIp;     // 触发告警的实例IP；为空表示节点/链路汇总维度
    private String metricName;
    private String metricValue;
    private String thresholdValue;
    private String operator;
    private String alertLevel;   // warning / critical
    private String status;       // active / resolved

    /** 触发时生效的连续超阈周期数快照（1 表示单周期即触发） */
    private Integer triggerPeriods;
    // 时间统一为 yyyy-MM-dd HH:mm:ss 字符串（由 SQL TO_CHAR 输出），与历史/趋势接口格式一致
    private String alertTime;
    private String resolveTime;

    /** 恢复方式：MANUAL=人工点击恢复，AUTO=指标自然恢复；人工恢复后指标未正常前不再重复告警 */
    private String resolveType;

    public AlertRecord() {
        this.id = generateUuid();
        this.alertTime = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
        this.status = "active";
    }

    private static String generateUuid() {
        return java.util.UUID.randomUUID().toString().replace("-", "");
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getRuleId() { return ruleId; }
    public void setRuleId(String ruleId) { this.ruleId = ruleId; }

    public String getTargetType() { return targetType; }
    public void setTargetType(String targetType) { this.targetType = targetType; }

    public String getTargetId() { return targetId; }
    public void setTargetId(String targetId) { this.targetId = targetId; }

    public String getTargetName() { return targetName; }
    public void setTargetName(String targetName) { this.targetName = targetName; }

    public String getServerIp() { return serverIp; }
    public void setServerIp(String serverIp) { this.serverIp = serverIp; }

    public String getMetricName() { return metricName; }
    public void setMetricName(String metricName) { this.metricName = metricName; }

    public String getMetricValue() { return metricValue; }
    public void setMetricValue(String metricValue) { this.metricValue = metricValue; }

    public String getThresholdValue() { return thresholdValue; }
    public void setThresholdValue(String thresholdValue) { this.thresholdValue = thresholdValue; }

    public String getOperator() { return operator; }
    public void setOperator(String operator) { this.operator = operator; }

    public String getAlertLevel() { return alertLevel; }
    public void setAlertLevel(String alertLevel) { this.alertLevel = alertLevel; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Integer getTriggerPeriods() { return triggerPeriods; }
    public void setTriggerPeriods(Integer triggerPeriods) { this.triggerPeriods = triggerPeriods; }

    public String getAlertTime() { return alertTime; }
    public void setAlertTime(String alertTime) { this.alertTime = alertTime; }

    public String getResolveTime() { return resolveTime; }
    public void setResolveTime(String resolveTime) { this.resolveTime = resolveTime; }

    public String getResolveType() { return resolveType; }
    public void setResolveType(String resolveType) { this.resolveType = resolveType; }
}
