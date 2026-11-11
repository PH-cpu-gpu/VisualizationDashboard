package com.gjzg.mapper;

import com.gjzg.entity.NotifyChannel;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 告警通知渠道 Mapper
 */
@Mapper
public interface NotifyChannelMapper {

    List<NotifyChannel> selectAll();

    /**
     * 查询启用中的渠道（推送时使用）
     */
    List<NotifyChannel> selectEnabled();

    NotifyChannel selectById(@Param("id") String id);

    int insert(NotifyChannel channel);

    /**
     * 更新渠道；WEBHOOK_URL 传空时保留原值（前端编辑不回填明文地址）
     */
    int update(NotifyChannel channel);

    int deleteById(@Param("id") String id);
}
