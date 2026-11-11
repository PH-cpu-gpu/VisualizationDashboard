package com.gjzg.api.entity;

import java.io.Serializable;

/**
 * 指标历史数据视图对象（用于趋势图展示）
 */
public class MetricHistoryVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private String metricName;
    private String metricValue;
    private String sourceTime;

    public String getMetricName() {
        return metricName;
    }

    public void setMetricName(String metricName) {
        this.metricName = metricName;
    }

    public String getMetricValue() {
        return metricValue;
    }

    public void setMetricValue(String metricValue) {
        this.metricValue = metricValue;
    }

    public String getSourceTime() {
        return sourceTime;
    }

    public void setSourceTime(String sourceTime) {
        this.sourceTime = sourceTime;
    }
}
