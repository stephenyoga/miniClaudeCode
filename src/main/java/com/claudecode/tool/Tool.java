// Tool.java - 工具定义
package com.claudecode.tool;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 工具定义
 *
 * @param name 工具名称
 * @param description 工具描述（传给LLM）
 * @param parameters 参数定义（JSON Schema格式）
 * @param executor 执行逻辑
 */
public record Tool(
        String name,
        String description,
        JsonNode parameters,
        ToolExecutor executor
) {}