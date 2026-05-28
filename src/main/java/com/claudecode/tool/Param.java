// Param.java - 参数定义
package com.claudecode.tool;

/**
 * 参数定义
 *
 * @param name 参数名称
 * @param type 参数类型（string, boolean, number等）
 * @param description 参数描述
 * @param required 是否必填
 */
public record Param(
        String name,
        String type,
        String description,
        boolean required
) {}