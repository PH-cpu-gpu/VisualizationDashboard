package com.gjzg.mapper;

import com.gjzg.entity.NodeMetricDef;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 节点指标定义Mapper
 */
@Mapper
public interface NodeMetricDefMapper {

    /**
     * 插入指标定义
     */
    int insert(NodeMetricDef def);

    /**
     * 批量插入
     */
    int batchInsert(List<NodeMetricDef> list);

    /**
     * 根据节点ID查询指标列表
     */
    List<NodeMetricDef> selectByNodeId(String nodeId);

    /**
     * 根据ID查询
     */
    NodeMetricDef selectById(String id);
}
