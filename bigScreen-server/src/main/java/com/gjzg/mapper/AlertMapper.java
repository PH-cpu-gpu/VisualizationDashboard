package com.gjzg.mapper;

import com.gjzg.entity.AlertRecord;
import com.gjzg.entity.AlertRule;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 告警相关 Mapper
 */
@Mapper
public interface AlertMapper {

    // ========== 告警规则 ==========

    List<AlertRule> selectAllRules();

    List<AlertRule> selectEnabledRules(@Param("targetType") String targetType, @Param("targetId") String targetId);

    int insertRule(AlertRule rule);

    int updateRule(AlertRule rule);

    int deleteRule(@Param("id") String id);

    /**
     * 解除告警记录对某规则的引用（置空 RULE_ID），避免删除规则时违反外键约束，同时保留历史告警记录
     */
    int detachAlertRecordsByRuleId(@Param("ruleId") String ruleId);

    AlertRule selectRuleById(@Param("id") String id);

    // ========== 告警记录 ==========

    int insertAlertRecord(AlertRecord record);

    /**
     * 自动恢复告警：采集评估发现指标恢复正常时调用（RESOLVE_TYPE 固定写入 AUTO）
     */
    int resolveAlert(@Param("id") String id);

    /**
     * 查询某规则在指定实例维度上的活跃告警（status='active'）
     * serverIp 为空时匹配汇总维度（SERVER_IP IS NULL），非空时匹配该实例
     */
    List<AlertRecord> selectActiveAlertsByRule(@Param("ruleId") String ruleId, @Param("serverIp") String serverIp);

    /**
     * 查询所有活跃告警
     */
    List<AlertRecord> selectAllActiveAlerts();

    /**
     * 查询告警历史（最近100条）
     */
    List<AlertRecord> selectAlertHistory(@Param("limit") int limit);
}
