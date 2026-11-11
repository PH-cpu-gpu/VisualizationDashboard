package com.gjzg.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * 应用级健康检查
 * <p>
 * 注意：根路径 /health 已被框架 Actuator 的健康检查端点接管（返回 {"status":"UP",...}），
 * 自定义 Controller 原先注册在 /health 上会被覆盖、永远匹配不到。
 * 因此本控制器改注册到 /api/health，用于返回应用自定义的探针结果，
 * 两者互不影响：/health 供框架与部署平台使用，/api/health 供业务侧使用。
 */
@RestController
@RequestMapping("/api/health")
public class HealthController {

    @GetMapping
    public Map<String, Object> health() {
        Map<String, Object> result = new HashMap<>();
        result.put("code", "000000");
        result.put("message", "success");
        result.put("data", "bigScreen-server is running");
        return result;
    }
}
