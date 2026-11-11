package com.gjzg.service;

import com.gjzg.entity.LinkInfo;
import com.gjzg.entity.NodeInfo;
import com.gjzg.mapper.LinkInfoMapper;
import com.gjzg.mapper.NodeInfoMapper;
import com.gjzg.mapper.TopologyAdminMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 拓扑管理服务（节点/链路增删改、实例绑定）
 * <p>
 * 所有涉及多表写入的方法都要求事务：删除节点/链路必须按外键依赖顺序级联，
 * 否则会触发 ORA-02292。
 */
@Service
public class TopologyAdminService {

    private static final Logger log = LoggerFactory.getLogger(TopologyAdminService.class);

    /** 允许新建/修改的节点样式：新增样式必须同步前端 computeLinkPath 的尺寸表 */
    private static final Set<String> ALLOWED_STYLE_CLASS =
            new HashSet<>(Arrays.asList("client", "nginx", "app"));

    /** 允许的绑定类型 */
    private static final Set<String> ALLOWED_BIND_TYPE =
            new HashSet<>(Arrays.asList("IP", "LOG_FILTER"));

    /** 简易 IPv4 校验（仅拦截明显非法输入，不做严格网段判断） */
    private static final Pattern IP_PATTERN = Pattern.compile(
            "^((25[0-5]|2[0-4]\\d|[01]?\\d?\\d)\\.){3}(25[0-5]|2[0-4]\\d|[01]?\\d?\\d)$");

    @Autowired
    private NodeInfoMapper nodeInfoMapper;

    @Autowired
    private LinkInfoMapper linkInfoMapper;

    @Autowired
    private TopologyAdminMapper adminMapper;

    @Autowired
    private AlertStreakState streakState;

    // ==================== 节点 ====================

    /**
     * 查询全部节点（含绑定属性 BIND_TYPE / INSTANCE_IPS / REQUEST_PREFIX）
     */
    public List<NodeInfo> listNodesWithBinding() {
        return nodeInfoMapper.selectAll();
    }

    @Transactional
    public String createNode(NodeInfo node) {
        validateNode(node, true);
        node.setId(node.getId() == null ? null : node.getId().toUpperCase());
        try {
            nodeInfoMapper.insert(node);
        } catch (DuplicateKeyException e) {
            throw new IllegalArgumentException("节点名称已存在：" + node.getNodeName());
        }
        // 按样式套用指标模板（模板未初始化时不会插入任何行，节点仍可正常展示）
        adminMapper.insertMetricDefsFromTpl(node.getId(), node.getStyleClass());
        log.info("新增拓扑节点：{}（{}），已按样式 {} 套用指标模板",
                node.getNodeName(), node.getId(), node.getStyleClass());
        return node.getId();
    }

    @Transactional
    public void updateNode(String id, NodeInfo node) {
        NodeInfo existing = nodeInfoMapper.selectById(id);
        if (existing == null) {
            throw new IllegalArgumentException("节点不存在：" + id);
        }
        node.setId(id);
        if (node.getNodeName() == null) {
            node.setNodeName(existing.getNodeName());
        }
        if (node.getDescription() == null) {
            node.setDescription(existing.getDescription());
        }
        if (node.getStyleClass() == null) {
            node.setStyleClass(existing.getStyleClass());
        }
        if (node.getNodeType() == null) {
            node.setNodeType(existing.getNodeType());
        }
        if (node.getPosX() == null) {
            node.setPosX(existing.getPosX());
        }
        if (node.getPosY() == null) {
            node.setPosY(existing.getPosY());
        }
        // 绑定属性：未传（null）保持原值，传空串表示清空
        if (node.getBindType() == null) {
            node.setBindType(existing.getBindType());
        }
        if (node.getInstanceIps() == null) {
            node.setInstanceIps(existing.getInstanceIps());
        }
        if (node.getRequestPrefix() == null) {
            node.setRequestPrefix(existing.getRequestPrefix());
        }
        validateNode(node, false);
        try {
            nodeInfoMapper.update(node);
        } catch (DuplicateKeyException e) {
            throw new IllegalArgumentException("节点名称已存在：" + node.getNodeName());
        }
    }

    /**
     * 删除节点：按外键依赖顺序级联清理指标、链路、绑定与告警规则
     */
    @Transactional
    public Map<String, Object> deleteNode(String id) {
        NodeInfo existing = nodeInfoMapper.selectById(id);
        if (existing == null) {
            throw new IllegalArgumentException("节点不存在：" + id);
        }
        Map<String, Object> impact = getNodeImpact(id);

        adminMapper.deleteNodeMetricLatest(id);
        adminMapper.deleteNodeMetricHistory(id);
        adminMapper.deleteNodeMetricDef(id);
        adminMapper.deleteLinkValueLatestByNode(id);
        adminMapper.deleteLinkValueHistoryByNode(id);
        adminMapper.deleteLinkMetricDefByNode(id);
        // 先关闭仍在活跃的告警记录（保留历史，RESOLVE_TYPE=MANUAL），再删规则；
        // 库中 TARGET_TYPE 为小写 node/link，SQL 内用 UPPER 做过大小写兼容
        int resolvedAlerts = adminMapper.resolveAlertsByTarget("node", id);
        adminMapper.deleteAlertRulesByTarget("node", id);
        // 规则与目标都被删掉后，内存里的连续周期计数也必须清掉
        streakState.clearByTarget("node", id);
        linkInfoMapper.deleteByNodeId(id);
        nodeInfoMapper.deleteById(id);

        log.info("删除拓扑节点：{}（{}），关联链路 {} 条、指标行 {} 条、实例 IP {} 个、关闭活跃告警 {} 条",
                existing.getNodeName(), id, impact.get("linkCount"),
                impact.get("metricRowCount"), impact.get("instanceCount"), resolvedAlerts);
        return impact;
    }

    /**
     * 删除前影响面统计（前端二次确认文案用）
     */
    public Map<String, Object> getNodeImpact(String id) {
        NodeInfo node = nodeInfoMapper.selectById(id);
        Map<String, Object> impact = new LinkedHashMap<>();
        impact.put("linkCount", adminMapper.countLinksByNode(id));
        impact.put("metricRowCount", adminMapper.countMetricRowsByNode(id));
        impact.put("instanceCount", node == null ? 0 : splitIps(node.getInstanceIps()).size());
        return impact;
    }

    @Transactional
    public int updateLayout(List<NodeInfo> list) {
        if (list == null || list.isEmpty()) {
            return 0;
        }
        int rows = 0;
        for (NodeInfo node : list) {
            if (node.getId() == null || node.getPosX() == null || node.getPosY() == null) {
                continue;
            }
            rows += adminMapper.updateNodeLayout(node.getId(), node.getPosX(), node.getPosY());
        }
        return rows;
    }

    // ==================== 链路 ====================

    @Transactional
    public String createLink(LinkInfo link) {
        if (link.getSourceNodeId() == null || link.getTargetNodeId() == null) {
            throw new IllegalArgumentException("链路必须指定源节点和目标节点");
        }
        if (link.getSourceNodeId().equals(link.getTargetNodeId())) {
            throw new IllegalArgumentException("链路不能连接到自身");
        }
        NodeInfo source = nodeInfoMapper.selectById(link.getSourceNodeId());
        NodeInfo target = nodeInfoMapper.selectById(link.getTargetNodeId());
        if (source == null || target == null) {
            throw new IllegalArgumentException("源节点或目标节点不存在");
        }
        if (linkInfoMapper.selectByNodes(link.getSourceNodeId(), link.getTargetNodeId()) != null) {
            throw new IllegalArgumentException("该链路已存在");
        }
        assertNoCycle(link.getSourceNodeId(), link.getTargetNodeId());
        if (link.getLinkName() == null || link.getLinkName().trim().isEmpty()) {
            link.setLinkName(source.getNodeName() + " -> " + target.getNodeName());
        }
        try {
            linkInfoMapper.insert(link);
        } catch (DuplicateKeyException e) {
            throw new IllegalArgumentException("该链路已存在");
        }
        // 按两端节点样式套用链路指标模板（如 client -> nginx 会得到 5 个指标；
        // 无对应模板的链路不会插入任何行，仍可正常展示）
        adminMapper.insertLinkMetricDefsFromTpl(link.getId(), source.getStyleClass(), target.getStyleClass());
        log.info("新增拓扑链路：{}（{} -> {}）", link.getLinkName(), source.getNodeName(), target.getNodeName());
        return link.getId();
    }

    /**
     * 删除链路：先清指标值、指标定义，再删链路本身与告警规则
     */
    @Transactional
    public void deleteLink(String id) {
        LinkInfo existing = linkInfoMapper.selectById(id);
        if (existing == null) {
            throw new IllegalArgumentException("链路不存在：" + id);
        }
        adminMapper.deleteLinkValueLatestByLink(id);
        adminMapper.deleteLinkValueHistoryByLink(id);
        adminMapper.deleteLinkMetricDefByLink(id);
        int resolvedAlerts = adminMapper.resolveAlertsByTarget("link", id);
        adminMapper.deleteAlertRulesByTarget("link", id);
        // 规则与目标都被删掉后，内存里的连续周期计数也必须清掉
        streakState.clearByTarget("link", id);
        linkInfoMapper.deleteById(id);
        log.info("删除拓扑链路：{}（{}），关闭活跃告警 {} 条", existing.getLinkName(), id, resolvedAlerts);
    }

    /**
     * 新增链路不能形成环：从目标节点出发若能回到源节点即视为成环。
     * 前端 BFS 推层依赖无环，否则节点层深计算失效。
     */
    private void assertNoCycle(String sourceId, String targetId) {
        Map<String, List<String>> children = new HashMap<>();
        for (LinkInfo link : linkInfoMapper.selectAll()) {
            List<String> list = children.get(link.getSourceNodeId());
            if (list == null) {
                list = new ArrayList<>();
                children.put(link.getSourceNodeId(), list);
            }
            list.add(link.getTargetNodeId());
        }
        Deque<String> queue = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        queue.add(targetId);
        while (!queue.isEmpty()) {
            String current = queue.poll();
            if (sourceId.equals(current)) {
                throw new IllegalArgumentException("该连线会使拓扑形成环，请调整方向");
            }
            if (!visited.add(current)) {
                continue;
            }
            List<String> next = children.get(current);
            if (next != null) {
                queue.addAll(next);
            }
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private List<String> splitIps(String raw) {
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

    private String joinIps(List<String> ips) {
        return ips == null || ips.isEmpty() ? null : String.join(",", ips);
    }

    // ==================== 校验 ====================

    private void validateNode(NodeInfo node, boolean creating) {
        if (node.getNodeName() == null || node.getNodeName().trim().isEmpty()) {
            throw new IllegalArgumentException("节点名称不能为空");
        }
        if (node.getNodeName().length() > 100) {
            throw new IllegalArgumentException("节点名称不能超过 100 个字符");
        }
        if (node.getDescription() != null && node.getDescription().length() > 500) {
            throw new IllegalArgumentException("节点描述不能超过 500 个字符");
        }
        if (node.getStyleClass() == null || !ALLOWED_STYLE_CLASS.contains(node.getStyleClass())) {
            throw new IllegalArgumentException("节点样式只支持 client / nginx / app");
        }
        if (creating && node.getNodeType() == null) {
            node.setNodeType("client".equals(node.getStyleClass()) ? "circle" : "rect");
        }
        if (node.getPosX() == null) {
            node.setPosX(300);
        }
        if (node.getPosY() == null) {
            node.setPosY(300);
        }
        // 绑定属性（节点字段，非独立表）：绑定方式 + IP 列表 + 请求前缀
        String bindType = hasText(node.getBindType()) ? node.getBindType().trim() : "IP";
        if (!ALLOWED_BIND_TYPE.contains(bindType)) {
            throw new IllegalArgumentException("绑定类型只支持 IP / LOG_FILTER");
        }
        node.setBindType(bindType);

        List<String> ips = splitIps(node.getInstanceIps());
        for (String ip : ips) {
            if (!IP_PATTERN.matcher(ip).matches()) {
                throw new IllegalArgumentException("实例 IP 格式不正确：" + ip);
            }
        }
        node.setInstanceIps(joinIps(ips));

        if ("LOG_FILTER".equals(bindType)) {
            if (ips.isEmpty()) {
                throw new IllegalArgumentException("日志过滤绑定至少需要一个匹配 IP（REMOTE_ADDR）");
            }
            if (!hasText(node.getRequestPrefix())) {
                throw new IllegalArgumentException("日志过滤绑定必须填写请求前缀，例如 /pposgjdf");
            }
            String prefix = node.getRequestPrefix().trim();
            if (!prefix.startsWith("/")) {
                throw new IllegalArgumentException("请求前缀必须以 / 开头");
            }
            node.setRequestPrefix(prefix);
        } else {
            node.setRequestPrefix(null);
        }
    }
}
