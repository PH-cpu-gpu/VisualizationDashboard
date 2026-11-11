package com.gjzg.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.gjzg.entity.AlertRecord;
import com.gjzg.entity.NotifyChannel;
import com.gjzg.mapper.NotifyChannelMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * 告警通知服务（企业微信群机器人 Webhook，全局统一渠道）。
 *
 * <p>设计要点：</p>
 * <ul>
 *   <li>完全异步：告警评估线程只入队，HTTP 推送在独立守护线程执行，外网慢或不通都不影响采集；</li>
 *   <li>批量聚合：2 秒窗口内产生的多条告警合并成一条 Markdown，规避企业微信 20 条/分钟限频；</li>
 *   <li>失败隔离：任何异常只记日志，不向调用方抛出；</li>
 *   <li>安全：只允许企业微信官方 Webhook 域名，避免任意 URL 被当作请求目标。</li>
 * </ul>
 */
@Component
public class AlertNotifyService {

    private static final Logger log = LoggerFactory.getLogger(AlertNotifyService.class);

    /** 队列容量：远大于单个采集批次的告警条数，满则丢弃并记日志 */
    private static final int QUEUE_CAPACITY = 500;

    /** 单次最多处理的事件数 */
    private static final int MAX_BATCH = 100;

    /** 聚合窗口：2 秒内的告警合并成一条消息 */
    private static final long FLUSH_INTERVAL_MS = 2000L;

    /** 渠道缓存有效期 */
    private static final long CHANNEL_CACHE_MS = 60000L;

    /** 渠道加载失败后的短时缓存，避免表未建/库不可用时每条告警反复查库刷错误日志 */
    private static final long CHANNEL_FAIL_CACHE_MS = 30000L;

    /** 企业微信 markdown 内容上限 4096 字节，留余量按 3500 字节分片 */
    private static final int MAX_CONTENT_BYTES = 3500;

    /** 企业微信群机器人 Webhook 地址格式（key 为 UUID 形态） */
    private static final Pattern WECOM_WEBHOOK_PATTERN =
            Pattern.compile("^https://qyapi\\.weixin\\.qq\\.com/cgi-bin/webhook/send\\?key=[A-Za-z0-9\\-_]+$");

    @Autowired
    private NotifyChannelMapper notifyChannelMapper;

    private final BlockingQueue<NotifyEvent> queue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);

    private final RestTemplate restTemplate;

    private ScheduledExecutorService scheduler;

    private volatile List<NotifyChannel> channelCache;
    private volatile long channelCacheExpireAt;

    public AlertNotifyService() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(5000);
        this.restTemplate = new RestTemplate(factory);
    }

    @PostConstruct
    public void init() {
        ThreadFactory threadFactory = new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "alert-notify");
                t.setDaemon(true);
                return t;
            }
        };
        scheduler = Executors.newSingleThreadScheduledExecutor(threadFactory);
        scheduler.scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
                safeFlush();
            }
        }, FLUSH_INTERVAL_MS, FLUSH_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    public void destroy() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    // ========== 对外入口（由告警评估调用，永不抛出异常） ==========

    /**
     * 告警触发通知
     */
    public void notifyTriggered(AlertRecord record) {
        offer(new NotifyEvent(true, record));
    }

    /**
     * 告警恢复通知
     */
    public void notifyRecovered(AlertRecord record) {
        offer(new NotifyEvent(false, record));
    }

    /**
     * 渠道增删改后清理缓存，使新配置立即生效
     */
    public void invalidateChannelCache() {
        channelCache = null;
        channelCacheExpireAt = 0L;
    }

    private void offer(NotifyEvent event) {
        if (event == null || event.record == null) return;
        if (!queue.offer(event)) {
            log.warn("告警通知队列已满，丢弃本次通知：{}", targetText(event.record));
        }
    }

    // ========== 配置校验与脱敏（供控制器复用） ==========

    /**
     * 校验是否为企业微信机器人 Webhook 地址，非该域名一律拒绝，避免 SSRF
     */
    public static boolean isValidWebhookUrl(String url) {
        return url != null && WECOM_WEBHOOK_PATTERN.matcher(url.trim()).matches();
    }

    /**
     * 地址脱敏：仅保留前缀与 key 末 4 位
     */
    public static String maskWebhookUrl(String url) {
        if (url == null || url.isEmpty()) return "";
        int idx = url.indexOf("key=");
        if (idx < 0) {
            return url.length() <= 8 ? "****" : url.substring(0, 8) + "****";
        }
        String key = url.substring(idx + 4);
        String tail = key.length() <= 4 ? key : key.substring(key.length() - 4);
        return url.substring(0, idx + 4) + "****" + tail;
    }

    // ========== 测试发送 ==========

    /**
     * 向指定渠道发送测试消息，返回企业微信的 errcode / errmsg
     */
    public JSONObject sendTest(NotifyChannel channel) {
        String content = "## 监控大屏通知测试\n"
                + "> 渠道：" + channel.getChannelName() + "\n"
                + "> 时间：" + now() + "\n"
                + "> 收到本条消息说明 Webhook 配置正确，后续告警触发与恢复都会推送到本群。";
        return postMarkdown(channel, content);
    }

    // ========== 聚合与发送 ==========

    private void safeFlush() {
        try {
            flush();
        } catch (Exception e) {
            log.error("告警通知发送任务异常", e);
        }
    }

    private void flush() {
        List<NotifyEvent> batch = new ArrayList<>();
        queue.drainTo(batch, MAX_BATCH);
        if (batch.isEmpty()) return;

        List<NotifyChannel> channels = loadEnabledChannels();
        if (channels.isEmpty()) {
            log.debug("未配置启用的通知渠道，跳过本次 {} 条告警通知", batch.size());
            return;
        }

        List<AlertRecord> triggered = new ArrayList<>();
        List<AlertRecord> recovered = new ArrayList<>();
        for (NotifyEvent event : batch) {
            if (event.triggered) {
                triggered.add(event.record);
            } else {
                recovered.add(event.record);
            }
        }
        if (!triggered.isEmpty()) {
            sendBatch(channels, buildTriggeredContent(triggered));
        }
        if (!recovered.isEmpty()) {
            sendBatch(channels, buildRecoveredContent(recovered));
        }
    }

    private List<NotifyChannel> loadEnabledChannels() {
        long now = System.currentTimeMillis();
        List<NotifyChannel> cached = channelCache;
        if (cached != null && now < channelCacheExpireAt) {
            return cached;
        }
        try {
            List<NotifyChannel> list = notifyChannelMapper.selectEnabled();
            List<NotifyChannel> result = new ArrayList<>();
            Set<String> urls = new LinkedHashSet<>();
            if (list != null) {
                for (NotifyChannel c : list) {
                    if (c.getWebhookUrl() == null) continue;
                    // 同一地址配置多条时只发一次，避免重复刷屏
                    if (urls.add(c.getWebhookUrl().trim())) {
                        result.add(c);
                    }
                }
            }
            channelCache = Collections.unmodifiableList(result);
            channelCacheExpireAt = now + CHANNEL_CACHE_MS;
            return channelCache;
        } catch (Exception e) {
            // 表未建或库不可用时不阻塞主流程，短时缓存空结果避免错误日志刷屏
            log.error("加载通知渠道失败，本次告警不推送", e);
            channelCache = Collections.emptyList();
            channelCacheExpireAt = now + CHANNEL_FAIL_CACHE_MS;
            return channelCache;
        }
    }

    private void sendBatch(List<NotifyChannel> channels, String content) {
        List<String> chunks = splitByBytes(content, MAX_CONTENT_BYTES);
        for (NotifyChannel channel : channels) {
            for (int i = 0; i < chunks.size(); i++) {
                JSONObject result = postMarkdown(channel, chunks.get(i));
                int errcode = result.getIntValue("errcode");
                if (errcode == 0) {
                    log.info("告警通知已发送：channel={}, 分段={}/{}", channel.getChannelName(), i + 1, chunks.size());
                } else {
                    log.error("告警通知发送失败：channel={}, errcode={}, errmsg={}",
                            channel.getChannelName(), errcode, result.getString("errmsg"));
                }
            }
        }
    }

    private JSONObject postMarkdown(NotifyChannel channel, String content) {
        JSONObject ret = new JSONObject();
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("msgtype", "markdown");
            Map<String, String> markdown = new LinkedHashMap<>();
            markdown.put("content", content);
            body.put("markdown", markdown);

            // 使用 byte[] 提交，避免 StringHttpMessageConverter 默认 ISO-8859-1 导致中文乱码
            byte[] payload = JSON.toJSONString(body).getBytes(StandardCharsets.UTF_8);
            HttpHeaders headers = new HttpHeaders();
            headers.set("Content-Type", "application/json;charset=UTF-8");
            HttpEntity<byte[]> entity = new HttpEntity<>(payload, headers);

            ResponseEntity<byte[]> response = restTemplate.exchange(
                    channel.getWebhookUrl(), HttpMethod.POST, entity, byte[].class);
            String text = response.getBody() == null ? "" : new String(response.getBody(), StandardCharsets.UTF_8);

            JSONObject json = null;
            try {
                json = JSON.parseObject(text);
            } catch (Exception ignored) {
                // 非 JSON 响应按失败处理
            }
            if (json == null) {
                ret.put("errcode", -1);
                ret.put("errmsg", "企业微信返回内容无法解析：" + text);
                return ret;
            }
            ret.put("errcode", json.getIntValue("errcode"));
            ret.put("errmsg", json.getString("errmsg"));
            return ret;
        } catch (Exception e) {
            ret.put("errcode", -1);
            ret.put("errmsg", e.getClass().getSimpleName() + ": " + e.getMessage());
            return ret;
        }
    }

    // ========== 消息模板 ==========

    private String buildTriggeredContent(List<AlertRecord> list) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 监控告警（").append(list.size()).append(" 条）\n");
        sb.append("> 时间：").append(now()).append("\n\n");
        int idx = 1;
        for (AlertRecord r : list) {
            sb.append("**").append(idx++).append(". ").append(levelText(r.getAlertLevel()))
                    .append(" | ").append(targetText(r))
                    .append(" | ").append(nvl(r.getMetricName())).append("**\n");
            sb.append("> 对象：").append(scopeText(r)).append("\n");
            sb.append("> 条件：").append(nvl(r.getMetricName())).append(" ").append(nvl(r.getOperator())).append(" ")
                    .append(nvl(r.getThresholdValue())).append("（当前 ").append(nvl(r.getMetricValue())).append("）\n");
            sb.append("> 触发时间：").append(nvl(r.getAlertTime())).append("\n");
            if (r.getTriggerPeriods() != null && r.getTriggerPeriods() > 1) {
                sb.append("> 连续超阈周期：").append(r.getTriggerPeriods()).append("\n");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private String buildRecoveredContent(List<AlertRecord> list) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 告警恢复（").append(list.size()).append(" 条）\n");
        sb.append("> 时间：").append(now()).append("\n\n");
        int idx = 1;
        for (AlertRecord r : list) {
            sb.append("**").append(idx++).append(". ").append(levelText(r.getAlertLevel()))
                    .append(" | ").append(targetText(r))
                    .append(" | ").append(nvl(r.getMetricName())).append("**\n");
            sb.append("> 对象：").append(scopeText(r)).append("\n");
            sb.append("> 条件：").append(nvl(r.getMetricName())).append(" ").append(nvl(r.getOperator())).append(" ")
                    .append(nvl(r.getThresholdValue())).append("（触发时值 ").append(nvl(r.getMetricValue())).append("）\n");
            sb.append("> 触发时间：").append(nvl(r.getAlertTime())).append("\n");
            sb.append("> 恢复时间：").append(now()).append("\n\n");
        }
        return sb.toString();
    }

    private static String levelText(String level) {
        return "critical".equalsIgnoreCase(level) ? "严重" : "警告";
    }

    private static String targetText(AlertRecord record) {
        String name = record.getTargetName();
        if (name == null || name.trim().isEmpty()) {
            name = record.getTargetId();
        }
        return nvl(name);
    }

    private static String scopeText(AlertRecord record) {
        if ("link".equalsIgnoreCase(record.getTargetType())) {
            return "链路";
        }
        String ip = record.getServerIp();
        if (ip == null || ip.trim().isEmpty()) {
            return "节点汇总";
        }
        return "节点 · 实例 " + ip.trim();
    }

    private static String nvl(String value) {
        return value == null ? "" : value;
    }

    private static String now() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
    }

    /**
     * 按 UTF-8 字节数分片（企业微信每个字段 上限 4096 字节），优先在行边界切分
     */
    private static List<String> splitByBytes(String content, int maxBytes) {
        List<String> chunks = new ArrayList<>();
        if (content == null || content.isEmpty()) {
            chunks.add("");
            return chunks;
        }
        String[] lines = content.split("\n", -1);
        StringBuilder current = new StringBuilder();
        int currentBytes = 0;
        for (String line : lines) {
            int lineBytes = (line + "\n").getBytes(StandardCharsets.UTF_8).length;
            if (currentBytes > 0 && currentBytes + lineBytes > maxBytes) {
                chunks.add(current.toString());
                current.setLength(0);
                currentBytes = 0;
            }
            current.append(line).append("\n");
            currentBytes += lineBytes;
        }
        if (current.length() > 0) {
            chunks.add(current.toString());
        }
        return chunks;
    }

    /**
     * 待发送事件：triggered=true 表示告警触发，false 表示恢复
     */
    private static class NotifyEvent {
        private final boolean triggered;
        private final AlertRecord record;

        private NotifyEvent(boolean triggered, AlertRecord record) {
            this.triggered = triggered;
            this.record = record;
        }
    }
}
