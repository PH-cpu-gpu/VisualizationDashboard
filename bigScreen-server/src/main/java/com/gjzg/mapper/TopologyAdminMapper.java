package com.gjzg.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 拓扑管理Mapper
 * <p>
 * 承载节点/链路的写操作、级联删除与指标模板；实例 IP 是 T_MT_NODE_INFO 的属性字段，
 * 不再有独立绑定表，绑定读写统一走 NodeInfoMapper。
 */
@Mapper
public interface TopologyAdminMapper {

    // ==================== 布局 ====================

    /**
     * 更新单个节点坐标（批量坐标由 Service 循环调用，节点数量很小）
     */
    int updateNodeLayout(@Param("id") String id,
                         @Param("posX") Integer posX,
                         @Param("posY") Integer posY);

    // ==================== 节点删除级联 ====================

    int deleteNodeMetricLatest(@Param("nodeId") String nodeId);

    int deleteNodeMetricHistory(@Param("nodeId") String nodeId);

    int deleteNodeMetricDef(@Param("nodeId") String nodeId);

    int deleteLinkValueLatestByNode(@Param("nodeId") String nodeId);

    int deleteLinkValueHistoryByNode(@Param("nodeId") String nodeId);

    int deleteLinkMetricDefByNode(@Param("nodeId") String nodeId);

    int countLinksByNode(@Param("nodeId") String nodeId);

    int countMetricRowsByNode(@Param("nodeId") String nodeId);

    /**
     * 删除指定目标的告警规则（targetType = node / link，大小写不敏感）
     */
    int deleteAlertRulesByTarget(@Param("targetType") String targetType,
                                 @Param("targetId") String targetId);

    /**
     * 关闭指定目标仍在活跃的告警记录（删除节点/链路时调用）：
     * 只置 STATUS=resolved / RESOLVE_TYPE=MANUAL，保留历史记录可追溯
     */
    int resolveAlertsByTarget(@Param("targetType") String targetType,
                              @Param("targetId") String targetId);

    // ==================== 链路删除级联 ====================

    int deleteLinkValueLatestByLink(@Param("linkId") String linkId);

    int deleteLinkValueHistoryByLink(@Param("linkId") String linkId);

    int deleteLinkMetricDefByLink(@Param("linkId") String linkId);

    // ==================== 指标模板 ====================

    /**
     * 按 styleClass 从模板表复制指标定义到 t_mt_node_metric_def（已存在的指标名跳过）
     */
    int insertMetricDefsFromTpl(@Param("nodeId") String nodeId, @Param("styleClass") String styleClass);

    /**
     * 按两端节点样式从链路指标模板复制指标定义到 t_mt_link_metric_def（已存在的指标名跳过）
     */
    int insertLinkMetricDefsFromTpl(@Param("linkId") String linkId,
                                    @Param("sourceStyle") String sourceStyle,
                                    @Param("targetStyle") String targetStyle);
}
