package com.gjzg.api.entity;

import java.io.Serializable;
import java.util.Date;

/**
 * 节点信息实体
 */
public class NodeInfo implements Serializable {

    private static final long serialVersionUID = 1L;

    private String id;
    private String nodeName;
    private String description;
    private Date createTime;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getNodeName() {
        return nodeName;
    }

    public void setNodeName(String nodeName) {
        this.nodeName = nodeName;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Date getCreateTime() {
        return createTime;
    }

    public void setCreateTime(Date createTime) {
        this.createTime = createTime;
    }
}
