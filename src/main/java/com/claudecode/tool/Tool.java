package com.claudecode.tool;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 工具定义 —— 描述一个可以被 LLM 调用的工具。
 *
 * LLM 通过 Function Calling 机制看到工具列表（name + description + parameters），
 * 决定调用哪个工具并传入参数。ToolRegistry 收到调用请求后执行对应的 executor。
 *
 * @param name       工具名称，LLM 通过此名称引用工具（如 "read_file"）
 * @param description 工具描述，告诉 LLM 这个工具做什么用
 * @param parameters  参数定义的 JSON Schema（由 ToolRegistry.createParameters 生成）
 * @param executor    实际执行逻辑（Lambda 表达式）
 */
public record Tool(
        String name,
        String description,
        JsonNode parameters,
        ToolExecutor executor
) {}
