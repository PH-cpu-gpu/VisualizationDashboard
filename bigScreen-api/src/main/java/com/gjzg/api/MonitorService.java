package com.gjzg.api;

import com.alibaba.fastjson.JSONObject;
import com.gjdf.daocloud.common.base.api.pojo.JsonResult;
import com.gjzg.api.entity.MetricValueDTO;

import java.util.List;

/**
 * 监控大屏数据服务接口（纯Spring本地服务，非RPC）
 */
public interface MonitorService {

    /**
     * 查询所有节点的最新指标值（serverIp为null时返回汇总，非null时返回指定实例）
     */
    JsonResult queryAllNodeMetrics(JSONObject requestParam);

    /**
     * 根据节点ID查询该节点的所有最新指标值（支持serverIp筛选）
     */
    JsonResult queryNodeMetricsById(JSONObject requestParam);

    /**
     * 查询指定节点的所有实例IP列表
     */
    JsonResult queryNodeServerIps(JSONObject requestParam);

    /**
     * 查询所有链路的最新指标值
     */
    JsonResult queryAllLinkMetrics(JSONObject requestParam);

    /**
     * 根据链路ID查询该链路的所有最新指标值
     */
    JsonResult queryLinkMetricsById(JSONObject requestParam);

    /**
     * 批量采集节点指标（同时写入history和latest，统一COLLECT_ID）
     */
    JsonResult collectNodeMetrics(List<MetricValueDTO> list);

    /**
     * 批量采集链路指标（同时写入history和latest，统一COLLECT_ID）
     */
    JsonResult collectLinkMetrics(List<MetricValueDTO> list);

    /**
     * 查询节点某指标在某日期的历史数据（用于趋势图对比）
     */
    JsonResult queryNodeMetricHistory(JSONObject requestParam);

    /**
     * 查询链路某指标在某日期的历史数据（用于趋势图对比）
     */
    JsonResult queryLinkMetricHistory(JSONObject requestParam);

    /**
     * 查询节点所有指标在最近N小时的历史数据（用于详情面板趋势图）
     */
    JsonResult queryNodeMetricsTrend(JSONObject requestParam);

    /**
     * 查询链路所有指标在最近N小时的历史数据（用于详情面板趋势图）
     */
    JsonResult queryLinkMetricsTrend(JSONObject requestParam);
}
