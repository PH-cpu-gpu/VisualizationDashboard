package com.gjzg.mapper;

import com.gjzg.entity.LinkMetricDef;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 链路指标定义Mapper
 */
@Mapper
public interface LinkMetricDefMapper {

    /**
     * 插入指标定义
     */
    int insert(LinkMetricDef def);

    /**
     * 批量插入
     */
    int batchInsert(List<LinkMetricDef> list);

    /**
     * 根据链路ID查询指标列表
     */
    List<LinkMetricDef> selectByLinkId(String linkId);

    /**
     * 根据ID查询
     */
    LinkMetricDef selectById(String id);
}
