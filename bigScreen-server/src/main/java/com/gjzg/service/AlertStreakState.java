package com.gjzg.service;

import org.springframework.stereotype.Component;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 告警持续时间抑制的连续周期计数器（内存态，进程内共享）。
 *
 * <p>用途：实现"连续 N 个采集周期超阈才触发告警 / 连续 N 个周期正常才解除告警"，
 * 压掉单周期抖动导致的瞬时告警。计数维度与告警去重维度保持一致：
 * 目标 + 规则 + 实例（serverIp 为空表示节点/链路汇总维度）。</p>
 *
 * <p>状态生命周期：</p>
 * <ul>
 *   <li>规则修改/删除、节点或链路删除时由调用方显式清理，避免旧计数套新阈值；</li>
 *   <li>惰性过期：某维度连续多个周期未被评估（采集器停摆、实例缺测）时计数自动作废，
 *       避免"半截进度"长期挂起；</li>
 *   <li>进程重启后计数归零，最多延迟 N 个采集周期才触发，不会漏报。</li>
 * </ul>
 */
@Component
public class AlertStreakState {

    /** 采集周期：NginxLogMetricCollector / TomcatLogMetricCollector 均为 @Scheduled(fixedRate = 120000) */
    public static final long COLLECT_INTERVAL_MS = 120000L;

    /** 连续周期数上限（按 2 分钟周期约合 1 天），防止误填超大值导致永不触发 */
    public static final int MAX_PERIODS = 720;

    /** key = targetType|targetId|ruleId|scope，scope 为实例 IP 或汇总维度的 "#" */
    private final Map<String, Streak> states = new ConcurrentHashMap<>();

    /**
     * 拼装计数键：与告警记录去重维度一致（规则 + 实例）
     */
    public String keyOf(String targetType, String targetId, String ruleId, String serverIp) {
        String scope = (serverIp == null || serverIp.trim().isEmpty()) ? "#" : serverIp.trim();
        return targetType + "|" + targetId + "|" + ruleId + "|" + scope;
    }

    /**
     * 超阈：连续超阈计数 +1 并清零恢复计数，返回当前连续周期数
     */
    public int incTrigger(String key, int needPeriods) {
        final long now = System.currentTimeMillis();
        final long stale = staleMillis(needPeriods);
        Streak s = states.compute(key, (k, v) -> {
            if (v == null || now - v.lastTouch > stale) {
                v = new Streak();
            }
            v.triggerCount++;
            v.recoverCount = 0;
            v.lastTouch = now;
            return v;
        });
        return s.triggerCount;
    }

    /**
     * 正常：连续正常计数 +1 并清零超阈计数，返回当前连续正常周期数
     */
    public int incRecover(String key, int recoverPeriods) {
        final long now = System.currentTimeMillis();
        final long stale = staleMillis(recoverPeriods);
        Streak s = states.compute(key, (k, v) -> {
            if (v == null || now - v.lastTouch > stale) {
                v = new Streak();
            }
            v.recoverCount++;
            v.triggerCount = 0;
            v.lastTouch = now;
            return v;
        });
        return s.recoverCount;
    }

    /**
     * 清理某规则的全部计数（规则被修改或删除后调用）
     */
    public void clearByRule(String ruleId) {
        if (ruleId == null || ruleId.isEmpty()) return;
        String marker = "|" + ruleId + "|";
        removeIfKeyMatches(key -> key.contains(marker));
    }

    /**
     * 清理某目标（节点/链路）的全部计数（目标被删除后调用）
     */
    public void clearByTarget(String targetType, String targetId) {
        if (targetType == null || targetId == null) return;
        String prefix = targetType + "|" + targetId + "|";
        removeIfKeyMatches(key -> key.startsWith(prefix));
    }

    private void removeIfKeyMatches(KeyMatcher matcher) {
        Iterator<String> it = states.keySet().iterator();
        while (it.hasNext()) {
            if (matcher.matches(it.next())) {
                it.remove();
            }
        }
    }

    /**
     * 过期阈值：留出 2 倍周期容差，至少 3 个周期，避免偶发漏采集就丢弃连续进度
     */
    private long staleMillis(int periods) {
        int p = periods < 1 ? 1 : Math.min(periods, MAX_PERIODS);
        return COLLECT_INTERVAL_MS * Math.max(p, 3) * 2;
    }

    private interface KeyMatcher {
        boolean matches(String key);
    }

    private static class Streak {
        /** 连续超阈周期数 */
        int triggerCount;
        /** 连续正常周期数 */
        int recoverCount;
        /** 最近一次被评估的时间戳（毫秒），用于惰性过期 */
        long lastTouch;
    }
}
