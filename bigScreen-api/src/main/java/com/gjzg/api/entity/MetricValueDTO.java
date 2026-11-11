package com.gjzg.api.entity;

/**
 * 指标采集数据DTO
 * 用于接收单条指标上报数据
 */
public class MetricValueDTO {

    /**
     * 节点ID（节点指标必填）
     */
    private String nodeId;

    /**
     * 链路ID（链路指标必填）
     */
    private String linkId;

    /**
     * 指标定义ID
     */
    private String metricId;

    /**
     * 指标值
     */
    private String metricValue;

    /**
     * 实例IP（可选，为空表示全部实例汇总）
     */
    private String serverIp;

    /**
     * 采集时间（yyyy-MM-dd HH:mm:ss，可选，默认当前时间）
     */
    private String sourceTime;

    public String getNodeId() {
        return nodeId;
    }

    public void setNodeId(String nodeId) {
        this.nodeId = nodeId;
    }

    public String getLinkId() {
        return linkId;
    }

    public void setLinkId(String linkId) {
        this.linkId = linkId;
    }

    public String getMetricId() {
        return metricId;
    }

    public void setMetricId(String metricId) {
        this.metricId = metricId;
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

    public String getServerIp() {
        return serverIp;
    }

    public void setServerIp(String serverIp) {
        this.serverIp = serverIp;
    }
}
