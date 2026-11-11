package com.gjzg.service;

import com.gjzg.entity.LinkInfo;
import com.gjzg.entity.NodeInfo;
import com.gjzg.mapper.LinkInfoMapper;
import com.gjzg.mapper.NodeInfoMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 采集器绑定关系提供者
 * <p>
 * 全部绑定关系均从数据库构建，不再回落到代码常量：
 * <ul>
 *   <li>实例 IP 是 T_MT_NODE_INFO 的属性字段（BIND_TYPE / INSTANCE_IPS / REQUEST_PREFIX）</li>
 *   <li>客户端节点按 styleClass=client 全量纳入（不要求配置 IP）</li>
 *   <li>兜底节点按 styleClass=tomcat 识别（承载未映射到任何节点的实例 IP 数据）</li>
 *   <li>链路指标归属由 T_MT_LINK_INFO 两端节点样式决定：client -> nginx 才采集</li>
 * </ul>
 * 采集器每轮采集调用一次 {@link #load()}，界面上的节点/链路变更最多一个采集周期（2 分钟）后生效；
 * 库中没有任何节点时返回空快照，本轮不采集。
 */
@Service
public class TopologyBindingProvider {

    private static final Logger log = LoggerFactory.getLogger(TopologyBindingProvider.class);

    private static final String BIND_TYPE_LOG_FILTER = "LOG_FILTER";
    private static final String STYLE_NGINX = "nginx";
    private static final String STYLE_CLIENT = "client";
    /** 兜底节点样式：不参与拓扑展示，承载未映射实例 IP 的数据 */
    private static final String STYLE_TOMCAT = "tomcat";

    @Autowired
    private NodeInfoMapper nodeInfoMapper;

    @Autowired
    private LinkInfoMapper linkInfoMapper;

    /** 上次绑定来源，仅在变化时打日志，避免每轮采集刷屏 */
    private volatile String lastSource = null;

    /**
     * 加载一份绑定快照（一次查询，供整轮采集使用）
     */
    public BindingSnapshot load() {
        try {
            List<NodeInfo> nodes = nodeInfoMapper.selectAll();
            List<LinkInfo> links = linkInfoMapper.selectAll();
            BindingSnapshot snapshot = build(nodes, links);
            logSource("T_MT_NODE_INFO / T_MT_LINK_INFO（"
                    + snapshot.getTomcatNodeIps().size() + " 个应用节点 / "
                    + snapshot.getNginxNodeIps().size() + " 个 nginx 节点 / "
                    + snapshot.getSubSiteFilters().size() + " 个日志过滤节点 / "
                    + snapshot.getClientNodeIds().size() + " 个客户端节点 / "
                    + snapshot.getClientNginxLinks().size() + " 条客户端链路）");
            return snapshot;
        } catch (Exception e) {
            logSource("读取绑定关系失败，本轮不采集：" + e.getMessage());
            return BindingSnapshot.empty();
        }
    }

    /**
     * 从节点属性列与链路表构建绑定关系
     */
    private BindingSnapshot build(List<NodeInfo> nodes, List<LinkInfo> links) {
        Map<String, List<String>> tomcatNodeIps = new LinkedHashMap<>();
        Map<String, List<String>> ipNodes = new LinkedHashMap<>();
        Map<String, List<String>> nginxNodeIps = new LinkedHashMap<>();
        Map<String, SubSiteFilter> subSiteFilters = new LinkedHashMap<>();
        Map<String, NodeInfo> nodeById = new LinkedHashMap<>();
        List<String> clientNodeIds = new ArrayList<>();
        String tomcatFallbackNodeId = null;

        for (NodeInfo node : nodes) {
            nodeById.put(node.getId(), node);
            String style = node.getStyleClass();
            // 客户端节点按样式全量纳入（不要求配置实例 IP）：其指标为全量日志口径
            if (STYLE_CLIENT.equals(style)) {
                clientNodeIds.add(node.getId());
                continue;
            }
            // 兜底节点不参与展示，也不按应用节点采集，只作为未映射 IP 的写入目标
            if (STYLE_TOMCAT.equals(style)) {
                tomcatFallbackNodeId = node.getId();
                continue;
            }
            List<String> ips = splitIps(node.getInstanceIps());
            if (ips.isEmpty()) {
                continue;
            }
            if (BIND_TYPE_LOG_FILTER.equals(node.getBindType())) {
                String prefix = node.getRequestPrefix();
                if (prefix == null || prefix.trim().isEmpty()) {
                    log.warn("节点 {} 的日志过滤绑定缺少请求前缀，已忽略", node.getId());
                    continue;
                }
                subSiteFilters.put(node.getId(), new SubSiteFilter(ips, prefix.trim()));
            } else if (STYLE_NGINX.equals(style)) {
                nginxNodeIps.put(node.getId(), ips);
            } else {
                tomcatNodeIps.put(node.getId(), ips);
                // 反向索引只登记应用节点：同机多应用共享同一 IP
                for (String ip : ips) {
                    addTo(ipNodes, ip, node.getId());
                }
            }
        }

        // 链路指标归属：源节点为客户端、目标节点为 nginx 才采集，按目标 nginx 的实例 IP 过滤日志
        Map<String, List<String>> clientNginxLinks = new LinkedHashMap<>();
        for (LinkInfo link : links) {
            NodeInfo source = nodeById.get(link.getSourceNodeId());
            NodeInfo target = nodeById.get(link.getTargetNodeId());
            if (source == null || target == null) {
                continue;
            }
            if (STYLE_CLIENT.equals(source.getStyleClass()) && STYLE_NGINX.equals(target.getStyleClass())) {
                List<String> ips = nginxNodeIps.get(target.getId());
                if (ips != null && !ips.isEmpty()) {
                    clientNginxLinks.put(link.getId(), ips);
                }
            }
        }

        List<String> metricNodeIds = new ArrayList<>(tomcatNodeIps.keySet());
        // 分站不在 tomcatNodeIps 中（无独立实例 IP），需单独纳入指标定义加载范围
        metricNodeIds.addAll(subSiteFilters.keySet());
        if (tomcatFallbackNodeId != null) {
            metricNodeIds.add(tomcatFallbackNodeId);
        }
        return new BindingSnapshot(tomcatNodeIps, ipNodes, subSiteFilters, nginxNodeIps,
                metricNodeIds, clientNodeIds, tomcatFallbackNodeId, clientNginxLinks);
    }

    private static List<String> splitIps(String raw) {
        List<String> list = new ArrayList<>();
        if (raw == null) {
            return list;
        }
        for (String part : raw.split(",")) {
            String ip = part.trim();
            if (!ip.isEmpty() && !list.contains(ip)) {
                list.add(ip);
            }
        }
        return list;
    }

    private void logSource(String source) {
        if (!source.equals(lastSource)) {
            lastSource = source;
            log.info("采集绑定来源：{}", source);
        }
    }

    private static void addTo(Map<String, List<String>> map, String key, String value) {
        List<String> list = map.get(key);
        if (list == null) {
            list = new ArrayList<>();
            map.put(key, list);
        }
        if (!list.contains(value)) {
            list.add(value);
        }
    }

    /**
     * 1.0 分站日志筛选规则：REMOTE_ADDR 列表 + REQUEST 前缀
     */
    public static final class SubSiteFilter {

        /** 命中该列表的 REMOTE_ADDR 视为属于对应系列（nginx 转发地址） */
        private final List<String> remoteIps;

        /** REQUEST 前缀：/pposgjdf 分站，/pposfa 估值分站 */
        private final String requestPrefix;

        public SubSiteFilter(List<String> remoteIps, String requestPrefix) {
            this.remoteIps = remoteIps;
            this.requestPrefix = requestPrefix;
        }

        public List<String> getRemoteIps() {
            return remoteIps;
        }

        public String getRequestPrefix() {
            return requestPrefix;
        }
    }

    /**
     * 一轮采集使用的绑定快照（全部只读）
     */
    public static class BindingSnapshot {

        private final Map<String, List<String>> tomcatNodeIps;
        private final Map<String, List<String>> ipNodes;
        private final Map<String, SubSiteFilter> subSiteFilters;
        private final Map<String, List<String>> nginxNodeIps;
        private final List<String> metricNodeIds;
        private final List<String> clientNodeIds;
        private final String tomcatFallbackNodeId;
        private final Map<String, List<String>> clientNginxLinks;

        private BindingSnapshot(Map<String, List<String>> tomcatNodeIps,
                                Map<String, List<String>> ipNodes,
                                Map<String, SubSiteFilter> subSiteFilters,
                                Map<String, List<String>> nginxNodeIps,
                                List<String> metricNodeIds,
                                List<String> clientNodeIds,
                                String tomcatFallbackNodeId,
                                Map<String, List<String>> clientNginxLinks) {
            this.tomcatNodeIps = Collections.unmodifiableMap(tomcatNodeIps);
            this.ipNodes = Collections.unmodifiableMap(ipNodes);
            this.subSiteFilters = Collections.unmodifiableMap(subSiteFilters);
            this.nginxNodeIps = Collections.unmodifiableMap(nginxNodeIps);
            this.metricNodeIds = Collections.unmodifiableList(metricNodeIds);
            this.clientNodeIds = Collections.unmodifiableList(clientNodeIds);
            this.tomcatFallbackNodeId = tomcatFallbackNodeId;
            this.clientNginxLinks = Collections.unmodifiableMap(clientNginxLinks);
        }

        /** 空快照：库中无节点或读取失败时使用，采集器本轮直接跳过 */
        static BindingSnapshot empty() {
            return new BindingSnapshot(Collections.<String, List<String>>emptyMap(),
                    Collections.<String, List<String>>emptyMap(),
                    Collections.<String, SubSiteFilter>emptyMap(),
                    Collections.<String, List<String>>emptyMap(),
                    Collections.<String>emptyList(),
                    Collections.<String>emptyList(),
                    null,
                    Collections.<String, List<String>>emptyMap());
        }

        /** 应用节点 -> 实例 IP 列表（同机多应用共享同一 IP） */
        public Map<String, List<String>> getTomcatNodeIps() {
            return tomcatNodeIps;
        }

        /** 实例 IP -> 归属应用节点列表 */
        public Map<String, List<String>> getIpNodes() {
            return ipNodes;
        }

        /** 日志过滤型节点（1.0 分站）的筛选规则 */
        public Map<String, SubSiteFilter> getSubSiteFilters() {
            return subSiteFilters;
        }

        /** nginx 节点 -> 集群实例 IP 列表 */
        public Map<String, List<String>> getNginxNodeIps() {
            return nginxNodeIps;
        }

        /** 需要加载 Tomcat 指标定义的节点（应用节点 + 日志过滤节点 + 兜底节点） */
        public List<String> getMetricNodeIds() {
            return metricNodeIds;
        }

        /** 客户端节点（styleClass=client，全局入口视角，可能有多个） */
        public List<String> getClientNodeIds() {
            return clientNodeIds;
        }

        /** 兜底节点 ID（styleClass=tomcat），库中不存在时为 null */
        public String getTomcatFallbackNodeId() {
            return tomcatFallbackNodeId;
        }

        /** 客户端 -> nginx 链路：linkId -> 目标 nginx 的实例 IP 列表 */
        public Map<String, List<String>> getClientNginxLinks() {
            return clientNginxLinks;
        }
    }
}
