package com.gjzg.entity;

import java.util.Date;

/**
 * 链路指标定义表
 */
public class LinkMetricDef {

    private String id;
    private String linkId;
    private String metricName;
    private String metricUnit;
    private String collectDesc;
    private Integer timeWindow;
    private Integer sortOrder;
    private Date createTime;

    public LinkMetricDef() {
        this.id = generateUuid();
        this.sortOrder = 0;
        this.timeWindow = 3;
        this.createTime = new Date();
    }

    /**
     * 生成32位UUID（去掉横杠）
     */
    private static String generateUuid() {
        return java.util.UUID.randomUUID().toString().replace("-", "");
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getLinkId() {
        return linkId;
    }

    public void setLinkId(String linkId) {
        this.linkId = linkId;
    }

    public String getMetricName() {
        return metricName;
    }

    public void setMetricName(String metricName) {
        this.metricName = metricName;
    }

    public String getMetricUnit() {
        return metricUnit;
    }

    public void setMetricUnit(String metricUnit) {
        this.metricUnit = metricUnit;
    }

    public String getCollectDesc() {
        return collectDesc;
    }

    public void setCollectDesc(String collectDesc) {
        this.collectDesc = collectDesc;
    }

    public Integer getTimeWindow() {
        return timeWindow;
    }

    public void setTimeWindow(Integer timeWindow) {
        this.timeWindow = timeWindow;
    }

    public Integer getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(Integer sortOrder) {
        this.sortOrder = sortOrder;
    }

    public Date getCreateTime() {
        return createTime;
    }

    public void setCreateTime(Date createTime) {
        this.createTime = createTime;
    }
}
