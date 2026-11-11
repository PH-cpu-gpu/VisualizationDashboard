package com.gjzg.entity;

import java.util.Date;

/**
 * 链路信息表
 */
public class LinkInfo {

    private String id;
    private String sourceNodeId;
    private String targetNodeId;
    private String linkName;
    private Date createTime;

    public LinkInfo() {
        this.id = generateUuid();
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

    public String getSourceNodeId() {
        return sourceNodeId;
    }

    public void setSourceNodeId(String sourceNodeId) {
        this.sourceNodeId = sourceNodeId;
    }

    public String getTargetNodeId() {
        return targetNodeId;
    }

    public void setTargetNodeId(String targetNodeId) {
        this.targetNodeId = targetNodeId;
    }

    public String getLinkName() {
        return linkName;
    }

    public void setLinkName(String linkName) {
        this.linkName = linkName;
    }

    public Date getCreateTime() {
        return createTime;
    }

    public void setCreateTime(Date createTime) {
        this.createTime = createTime;
    }
}
