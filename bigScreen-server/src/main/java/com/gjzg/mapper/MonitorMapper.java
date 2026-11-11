package com.gjzg.mapper;

import com.gjzg.api.entity.LinkMetricVO;
import com.gjzg.api.entity.MetricHistoryVO;
import com.gjzg.api.entity.MetricValueDTO;
import com.gjzg.api.entity.NodeMetricVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 监控大屏数据查询Mapper
 */
@Mapper
public interface MonitorMapper {

    /**
     * 查询所有节点的最新指标值（支持按实例筛选）
     */
    List<NodeMetricVO> selectAllNodeMetrics(@Param("serverIp") String serverIp);

    /**
     * 根据节点ID查询该节点的所有最新指标值（支持按实例筛选）
     */
    List<NodeMetricVO> selectNodeMetricsByNodeId(@Param("nodeId") String nodeId, @Param("serverIp") String serverIp);

    /**
     * 查询指定节点的所有实例IP列表
     */
    List<String> selectDistinctServerIps(@Param("nodeId") String nodeId);

    /**
     * 根据节点ID查询最新值（METRIC_ID -> METRIC_VALUE），用于模拟采集的种子值加载
     */
    List<java.util.Map<String, Object>> selectNodeLatestValues(@Param("nodeId") String nodeId);

    /**
     * 查询所有链路的最新指标值
     */
    List<LinkMetricVO> selectAllLinkMetrics();

    /**
     * 根据链路ID查询该链路的所有最新指标值
     */
    List<LinkMetricVO> selectLinkMetricsByLinkId(@Param("linkId") String linkId);

    /**
     * 批量插入节点历史值
     */
    int batchInsertNodeHistory(@Param("list") List<MetricValueDTO> list, @Param("collectId") String collectId);

    /**
     * MERGE 写入节点最新值：按 (nodeId, metricId, serverIp) 存在则更新、不存在则插入
     */
    int mergeNodeLatest(@Param("list") List<MetricValueDTO> list, @Param("collectId") String collectId);

    /**
     * 根据节点ID查询指标定义映射（METRIC_NAME -> ID）
     */
    List<java.util.Map<String, Object>> selectNodeMetricDefMap(@Param("nodeId") String nodeId);

    /**
     * 根据链路ID查询指标定义映射（METRIC_NAME -> ID）
     */
    List<java.util.Map<String, Object>> selectLinkMetricDefMap(@Param("linkId") String linkId);

    /**
     * 批量插入链路历史值
     */
    int batchInsertLinkHistory(@Param("list") List<MetricValueDTO> list, @Param("collectId") String collectId);

    /**
     * MERGE 写入链路最新值：按 (linkId, metricId) 存在则更新、不存在则插入
     */
    int mergeLinkLatest(@Param("list") List<MetricValueDTO> list, @Param("collectId") String collectId);

    /**
     * 查询节点某指标在某日期的历史数据
     * @param serverIp 实例IP；为空时查汇总维度（SERVER_IP IS NULL），非空时查指定实例
     */
    List<MetricHistoryVO> selectNodeMetricHistory(@Param("nodeId") String nodeId, @Param("metricName") String metricName,
                                                  @Param("startTime") String startTime, @Param("endTime") String endTime,
                                                  @Param("serverIp") String serverIp);

    /**
     * 查询链路某指标在某日期的历史数据
     */
    List<MetricHistoryVO> selectLinkMetricHistory(@Param("linkId") String linkId, @Param("metricName") String metricName, @Param("startTime") String startTime, @Param("endTime") String endTime);

    /**
     * 查询节点所有指标在最近N小时的历史数据（用于详情面板趋势图）
     * @param serverIp 实例IP；为空时查汇总维度，非空时查指定实例
     */
    List<MetricHistoryVO> selectNodeMetricsTrend(@Param("nodeId") String nodeId, @Param("hours") int hours,
                                                 @Param("serverIp") String serverIp);

    /**
     * 查询链路所有指标在最近N小时的历史数据（用于详情面板趋势图）
     */
    List<MetricHistoryVO> selectLinkMetricsTrend(@Param("linkId") String linkId, @Param("hours") int hours);
}
