package com.claudecode.tool;

/**
 * 工具参数定义 —— 描述一个工具需要什么参数，传给 LLM 生成 JSON Schema。
 *
 * LLM 看到这个定义后会生成对应格式的参数 JSON。
 * 例如 Param("path", "string", "文件路径", true) 表示：
 * - 参数名 path
 * - 类型 string
 * - 用途是"文件路径"
 * - 必填（required=true）
 */
public record Param(
        String name,
        String type,
        String description,
        boolean required
) {}
