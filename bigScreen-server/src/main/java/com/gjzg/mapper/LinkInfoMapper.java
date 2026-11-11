package com.gjzg.mapper;

import com.gjzg.entity.LinkInfo;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 链路信息Mapper
 */
@Mapper
public interface LinkInfoMapper {

    /**
     * 插入链路
     */
    int insert(LinkInfo linkInfo);

    /**
     * 根据ID查询
     */
    LinkInfo selectById(String id);

    /**
     * 查询所有链路
     */
    List<LinkInfo> selectAll();

    /**
     * 根据源节点和目标节点查询
     */
    LinkInfo selectByNodes(@Param("sourceNodeId") String sourceNodeId,
                           @Param("targetNodeId") String targetNodeId);

    /**
     * 根据ID删除链路
     */
    int deleteById(String id);

    /**
     * 根据节点ID删除相关链路（级联删除）
     */
    int deleteByNodeId(String nodeId);
}
