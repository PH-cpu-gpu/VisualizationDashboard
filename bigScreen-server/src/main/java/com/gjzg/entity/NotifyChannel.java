package com.gjzg.entity;

import java.util.Date;

/**
 * 告警通知渠道实体（全局生效）
 *
 * <p>所有 ENABLED = 1 的渠道都会接收全部告警：告警触发时推一次，指标恢复时再推一次。
 * 目前仅实现企业微信群机器人（WECOM_WEBHOOK），CHANNEL_TYPE 预留后续扩展。</p>
 */
public class NotifyChannel {

    public static final String TYPE_WECOM_WEBHOOK = "WECOM_WEBHOOK";

    private String id;
    private String channelName;
    private String channelType;
    /** Webhook 明文地址，仅入库与发送时使用，接口出参必须脱敏 */
    private String webhookUrl;
    /** 脱敏后的地址（非数据库列），仅用于接口出参 */
    private String webhookUrlMask;
    private Integer enabled;     // 1启用 0禁用
    private Date createTime;
    private Date updateTime;

    public NotifyChannel() {
        this.id = java.util.UUID.randomUUID().toString().replace("-", "");
        this.channelType = TYPE_WECOM_WEBHOOK;
        this.enabled = 1;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getChannelName() { return channelName; }
    public void setChannelName(String channelName) { this.channelName = channelName; }

    public String getChannelType() { return channelType; }
    public void setChannelType(String channelType) { this.channelType = channelType; }

    public String getWebhookUrl() { return webhookUrl; }
    public void setWebhookUrl(String webhookUrl) { this.webhookUrl = webhookUrl; }

    public String getWebhookUrlMask() { return webhookUrlMask; }
    public void setWebhookUrlMask(String webhookUrlMask) { this.webhookUrlMask = webhookUrlMask; }

    public Integer getEnabled() { return enabled; }
    public void setEnabled(Integer enabled) { this.enabled = enabled; }

    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }

    public Date getUpdateTime() { return updateTime; }
    public void setUpdateTime(Date updateTime) { this.updateTime = updateTime; }
}
