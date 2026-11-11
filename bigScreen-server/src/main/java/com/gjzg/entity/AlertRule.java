package com.gjzg.entity;

import java.util.Date;

/**
 * 告警规则实体
 */
public class AlertRule {

    private String id;
    private String targetType;   // node / link
    private String targetId;
    private String metricName;
    private String operator;     // > < >= <= == !=
    private String thresholdValue;
    private String alertLevel;   // warning / critical
    private Integer enabled;     // 1启用 0禁用

    /** 持续时间抑制：连续 N 个采集周期超阈才触发告警；1 表示单周期即触发 */
    private Integer triggerPeriods;

    /** 恢复抑制：连续 N 个采集周期正常才自动解除告警；1 表示单周期正常即解除 */
    private Integer recoverPeriods;

    private Date createTime;

    public AlertRule() {
        this.id = generateUuid();
        this.createTime = new Date();
        this.enabled = 1;
        this.alertLevel = "warning";
        this.triggerPeriods = 1;
        this.recoverPeriods = 1;
    }

    private static String generateUuid() {
        return java.util.UUID.randomUUID().toString().replace("-", "");
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getTargetType() { return targetType; }
    public void setTargetType(String targetType) { this.targetType = targetType; }

    public String getTargetId() { return targetId; }
    public void setTargetId(String targetId) { this.targetId = targetId; }

    public String getMetricName() { return metricName; }
    public void setMetricName(String metricName) { this.metricName = metricName; }

    public String getOperator() { return operator; }
    public void setOperator(String operator) { this.operator = operator; }

    public String getThresholdValue() { return thresholdValue; }
    public void setThresholdValue(String thresholdValue) { this.thresholdValue = thresholdValue; }

    public String getAlertLevel() { return alertLevel; }
    public void setAlertLevel(String alertLevel) { this.alertLevel = alertLevel; }

    public Integer getEnabled() { return enabled; }
    public void setEnabled(Integer enabled) { this.enabled = enabled; }

    public Integer getTriggerPeriods() { return triggerPeriods; }
    public void setTriggerPeriods(Integer triggerPeriods) { this.triggerPeriods = triggerPeriods; }

    public Integer getRecoverPeriods() { return recoverPeriods; }
    public void setRecoverPeriods(Integer recoverPeriods) { this.recoverPeriods = recoverPeriods; }

    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
}
