package com.gjzg.controller;

import com.gjdf.daocloud.common.base.api.pojo.JsonResult;
import com.gjzg.entity.LinkInfo;
import com.gjzg.entity.NodeInfo;
import com.gjzg.service.TopologyAdminService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 拓扑编辑接口（写操作 + 绑定）
 * <p>
 * 只读查询仍在 MonitorController（GET /api/monitor/topology/nodes|links）。
 */
@RestController
@RequestMapping("/api/monitor/topology")
@CrossOrigin(origins = "*")
public class TopologyAdminController {

    private static final Logger log = LoggerFactory.getLogger(TopologyAdminController.class);

    @Autowired
    private TopologyAdminService topologyAdminService;

    // ==================== 节点 ====================

    /**
     * 新增节点（按 styleClass 自动套用指标模板）
     */
    @PostMapping("/nodes")
    public JsonResult createNode(@RequestBody NodeInfo nodeInfo) {
        try {
            String id = topologyAdminService.createNode(nodeInfo);
            return JsonResult.success("创建成功", id);
        } catch (IllegalArgumentException e) {
            return JsonResult.fail(e.getMessage());
        } catch (Exception e) {
            log.error("新增节点失败", e);
            return JsonResult.fail("新增节点失败：" + e.getMessage());
        }
    }

    /**
     * 更新节点（名称/描述/样式/坐标，未传字段保持原值）
     */
    @PutMapping("/nodes/{id}")
    public JsonResult updateNode(@PathVariable String id, @RequestBody NodeInfo nodeInfo) {
        try {
            topologyAdminService.updateNode(id, nodeInfo);
            return JsonResult.success("更新成功", id);
        } catch (IllegalArgumentException e) {
            return JsonResult.fail(e.getMessage());
        } catch (Exception e) {
            log.error("更新节点失败", e);
            return JsonResult.fail("更新节点失败：" + e.getMessage());
        }
    }

    /**
     * 删除节点（级联清理指标、链路、绑定与告警规则）
     */
    @DeleteMapping("/nodes/{id}")
    public JsonResult deleteNode(@PathVariable String id) {
        try {
            Map<String, Object> impact = topologyAdminService.deleteNode(id);
            return JsonResult.success("删除成功", impact);
        } catch (IllegalArgumentException e) {
            return JsonResult.fail(e.getMessage());
        } catch (Exception e) {
            log.error("删除节点失败", e);
            return JsonResult.fail("删除节点失败：" + e.getMessage());
        }
    }

    /**
     * 删除前影响面统计
     */
    @GetMapping("/nodes/{id}/impact")
    public JsonResult getNodeImpact(@PathVariable String id) {
        try {
            return JsonResult.success("查询成功", topologyAdminService.getNodeImpact(id));
        } catch (Exception e) {
            return JsonResult.fail("查询影响面失败：" + e.getMessage());
        }
    }

    /**
     * 批量保存节点坐标（自由布局拖动落库）
     */
    @PutMapping("/nodes/layout")
    public JsonResult updateLayout(@RequestBody List<NodeInfo> list) {
        try {
            int rows = topologyAdminService.updateLayout(list);
            return JsonResult.success("保存成功", rows);
        } catch (Exception e) {
            log.error("保存布局失败", e);
            return JsonResult.fail("保存布局失败：" + e.getMessage());
        }
    }

    // ==================== 链路 ====================

    /**
     * 新增链路（自连、重复、成环均返回可读错误）
     */
    @PostMapping("/links")
    public JsonResult createLink(@RequestBody LinkInfo linkInfo) {
        try {
            String id = topologyAdminService.createLink(linkInfo);
            return JsonResult.success("创建成功", id);
        } catch (IllegalArgumentException e) {
            return JsonResult.fail(e.getMessage());
        } catch (Exception e) {
            log.error("新增链路失败", e);
            return JsonResult.fail("新增链路失败：" + e.getMessage());
        }
    }

    /**
     * 删除链路（级联清理链路指标与告警规则）
     */
    @DeleteMapping("/links/{id}")
    public JsonResult deleteLink(@PathVariable String id) {
        try {
            topologyAdminService.deleteLink(id);
            return JsonResult.success("删除成功", id);
        } catch (IllegalArgumentException e) {
            return JsonResult.fail(e.getMessage());
        } catch (Exception e) {
            log.error("删除链路失败", e);
            return JsonResult.fail("删除链路失败：" + e.getMessage());
        }
    }
}
