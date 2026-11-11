package com.gjzg.mapper;

import com.gjzg.entity.NodeInfo;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 节点信息Mapper
 */
@Mapper
public interface NodeInfoMapper {

    /**
     * 插入节点
     */
    int insert(NodeInfo nodeInfo);

    /**
     * 根据ID查询
     */
    NodeInfo selectById(String id);

    /**
     * 查询所有节点
     */
    List<NodeInfo> selectAll();

    /**
     * 根据名称查询
     */
    NodeInfo selectByName(String nodeName);

    /**
     * 更新节点
     */
    int update(NodeInfo nodeInfo);

    /**
     * 根据ID删除节点
     */
    int deleteById(String id);
}
