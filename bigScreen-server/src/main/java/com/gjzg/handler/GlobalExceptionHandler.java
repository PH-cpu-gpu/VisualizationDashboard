package com.gjzg.handler;

import com.gjdf.daocloud.common.base.api.pojo.JsonResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingPathVariableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * 全局参数异常处理
 * <p>
 * 仅接管参数绑定阶段抛出的异常（发生在进入 Controller 方法之前），统一返回 JsonResult 结构，
 * 避免 Spring 默认的 400 错误页导致前端无法按统一协议解析。
 * 业务方法内部的异常仍由各 Controller / Service 自行捕获，提示内容保持不变。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 缺少必填的请求参数，例如 /history/node 未传 metricName
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public JsonResult handleMissingParameter(MissingServletRequestParameterException e) {
        log.warn("请求缺少必填参数：{}", e.getParameterName());
        return JsonResult.fail("缺少必填参数：" + e.getParameterName());
    }

    /**
     * 缺少路径变量，例如 /display/nodeMetrics/{nodeId} 路径缺失
     */
    @ExceptionHandler(MissingPathVariableException.class)
    public JsonResult handleMissingPathVariable(MissingPathVariableException e) {
        log.warn("请求缺少路径变量：{}", e.getVariableName());
        return JsonResult.fail("缺少路径变量：" + e.getVariableName());
    }

    /**
     * 参数类型转换失败，例如 /alert/history?limit=abc
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public JsonResult handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        log.warn("请求参数类型错误：{}={}", e.getName(), e.getValue());
        return JsonResult.fail("参数类型错误：" + e.getName() + "=" + e.getValue());
    }

    /**
     * 请求体无法解析，例如采集接口传入非法 JSON
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public JsonResult handleBodyNotReadable(HttpMessageNotReadableException e) {
        log.warn("请求体解析失败：{}", e.getMessage());
        return JsonResult.fail("请求体格式错误");
    }
}
