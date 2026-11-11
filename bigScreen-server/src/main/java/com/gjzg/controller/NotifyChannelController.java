package com.gjzg.controller;

import com.alibaba.fastjson.JSONObject;
import com.gjdf.daocloud.common.base.api.pojo.JsonResult;
import com.gjzg.entity.NotifyChannel;
import com.gjzg.mapper.NotifyChannelMapper;
import com.gjzg.service.AlertNotifyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 告警通知渠道接口（全局统一渠道，目前支持企业微信群机器人）
 */
@RestController
@RequestMapping("/api/monitor/notify")
@CrossOrigin(origins = "*")
public class NotifyChannelController {

    private static final Logger log = LoggerFactory.getLogger(NotifyChannelController.class);

    @Autowired
    private NotifyChannelMapper notifyChannelMapper;

    @Autowired
    private AlertNotifyService alertNotifyService;

    /**
     * 查询全部渠道（Webhook 地址脱敏，不返回明文 key）
     */
    @GetMapping("/channels")
    public JsonResult queryChannels() {
        try {
            List<NotifyChannel> list = notifyChannelMapper.selectAll();
            if (list != null) {
                for (NotifyChannel c : list) {
                    c.setWebhookUrlMask(AlertNotifyService.maskWebhookUrl(c.getWebhookUrl()));
                    c.setWebhookUrl(null);
                }
            }
            return JsonResult.success("查询成功", list);
        } catch (Exception e) {
            return JsonResult.fail("查询通知渠道失败：" + e.getMessage());
        }
    }

    /**
     * 新增渠道
     */
    @PostMapping("/channels")
    public JsonResult createChannel(@RequestBody NotifyChannel channel) {
        try {
            String error = validate(channel, true);
            if (error != null) {
                return JsonResult.fail(error);
            }
            channel.setChannelType(NotifyChannel.TYPE_WECOM_WEBHOOK);
            if (channel.getEnabled() == null) channel.setEnabled(1);
            channel.setWebhookUrl(channel.getWebhookUrl().trim());
            notifyChannelMapper.insert(channel);
            alertNotifyService.invalidateChannelCache();
            return JsonResult.success("创建成功", channel.getId());
        } catch (Exception e) {
            return JsonResult.fail("创建通知渠道失败：" + e.getMessage());
        }
    }

    /**
     * 修改渠道；webhookUrl 留空表示沿用原地址（前端不回填明文）
     */
    @PutMapping("/channels/{id}")
    public JsonResult updateChannel(@PathVariable String id, @RequestBody NotifyChannel channel) {
        try {
            NotifyChannel existing = notifyChannelMapper.selectById(id);
            if (existing == null) {
                return JsonResult.fail("渠道不存在：" + id);
            }
            channel.setId(id);
            String error = validate(channel, false);
            if (error != null) {
                return JsonResult.fail(error);
            }
            channel.setChannelType(NotifyChannel.TYPE_WECOM_WEBHOOK);
            if (channel.getEnabled() == null) channel.setEnabled(existing.getEnabled());
            if (channel.getWebhookUrl() != null) {
                channel.setWebhookUrl(channel.getWebhookUrl().trim());
            }
            notifyChannelMapper.update(channel);
            alertNotifyService.invalidateChannelCache();
            return JsonResult.success("更新成功", id);
        } catch (Exception e) {
            return JsonResult.fail("更新通知渠道失败：" + e.getMessage());
        }
    }

    /**
     * 删除渠道
     */
    @DeleteMapping("/channels/{id}")
    public JsonResult deleteChannel(@PathVariable String id) {
        try {
            notifyChannelMapper.deleteById(id);
            alertNotifyService.invalidateChannelCache();
            return JsonResult.success("删除成功", id);
        } catch (Exception e) {
            return JsonResult.fail("删除通知渠道失败：" + e.getMessage());
        }
    }

    /**
     * 发送测试消息，直接返回企业微信的 errcode / errmsg 便于页面提示
     */
    @PostMapping("/channels/{id}/test")
    public JsonResult testChannel(@PathVariable String id) {
        try {
            NotifyChannel channel = notifyChannelMapper.selectById(id);
            if (channel == null) {
                return JsonResult.fail("渠道不存在：" + id);
            }
            if (!AlertNotifyService.isValidWebhookUrl(channel.getWebhookUrl())) {
                return JsonResult.fail("Webhook 地址不合法，仅支持企业微信群机器人地址");
            }
            JSONObject result = alertNotifyService.sendTest(channel);
            int errcode = result.getIntValue("errcode");
            if (errcode == 0) {
                return JsonResult.success("测试消息已发送，请查看企业微信群", result);
            }
            log.error("通知渠道测试失败：id={}, errcode={}, errmsg={}", id, errcode, result.getString("errmsg"));
            return JsonResult.fail("发送失败（errcode=" + errcode + "）：" + result.getString("errmsg"));
        } catch (Exception e) {
            return JsonResult.fail("测试发送失败：" + e.getMessage());
        }
    }

    /**
     * 入参校验：新增时地址必填，修改时地址可空（空=不修改）
     */
    private String validate(NotifyChannel channel, boolean requireUrl) {
        if (channel == null) return "请求参数不能为空";
        if (channel.getChannelName() == null || channel.getChannelName().trim().isEmpty()) {
            return "渠道名称不能为空";
        }
        String url = channel.getWebhookUrl();
        if (url == null || url.trim().isEmpty()) {
            return requireUrl ? "Webhook 地址不能为空" : null;
        }
        if (!AlertNotifyService.isValidWebhookUrl(url)) {
            return "Webhook 地址不合法，请填写企业微信群机器人地址（https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=...）";
        }
        return null;
    }
}
