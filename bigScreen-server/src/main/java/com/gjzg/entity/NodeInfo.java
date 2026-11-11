package com.gjzg.entity;

import java.util.Date;

/**
 * 节点信息表
 */
public class NodeInfo {

    private String id;
    private String nodeName;
    private String description;
    private Integer posX;
    private Integer posY;
    private String nodeType;
    private String styleClass;
    private Date createTime;

    /**
     * 绑定类型（T_MT_NODE_INFO.BIND_TYPE）：
     * IP=实例 IP，LOG_FILTER=日志过滤虚拟节点（1.0 分站），null=未绑定
     */
    private String bindType;

    /**
     * 节点实例/日志匹配 IP 列表（T_MT_NODE_INFO.INSTANCE_IPS），逗号分隔：
     * 192.168.49.162,192.168.49.163
     * 同一 IP 可出现在多个节点下（同机多应用共享机器级指标）
     */
    private String instanceIps;

    /**
     * 请求前缀（T_MT_NODE_INFO.REQUEST_PREFIX），仅 BIND_TYPE=LOG_FILTER 时使用：
     * 分站 /pposgjdf，估值分站 /pposfa
     */
    private String requestPrefix;

    public NodeInfo() {
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

    public Integer getPosX() {
        return posX;
    }

    public void setPosX(Integer posX) {
        this.posX = posX;
    }

    public Integer getPosY() {
        return posY;
    }

    public void setPosY(Integer posY) {
        this.posY = posY;
    }

    public String getNodeType() {
        return nodeType;
    }

    public void setNodeType(String nodeType) {
        this.nodeType = nodeType;
    }

    public String getStyleClass() {
        return styleClass;
    }

    public void setStyleClass(String styleClass) {
        this.styleClass = styleClass;
    }

    public String getBindType() {
        return bindType;
    }

    public void setBindType(String bindType) {
        this.bindType = bindType;
    }

    public String getInstanceIps() {
        return instanceIps;
    }

    public void setInstanceIps(String instanceIps) {
        this.instanceIps = instanceIps;
    }

    public String getRequestPrefix() {
        return requestPrefix;
    }

    public void setRequestPrefix(String requestPrefix) {
        this.requestPrefix = requestPrefix;
    }

    public Date getCreateTime() {
        return createTime;
    }

    public void setCreateTime(Date createTime) {
        this.createTime = createTime;
    }
}
