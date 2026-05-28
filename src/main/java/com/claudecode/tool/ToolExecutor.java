// ToolExecutor.java - 工具执行器接口
package com.claudecode.tool;

import java.util.Map;

/**
 * 工具执行器接口
 * 定义工具的执行逻辑
 */
public interface ToolExecutor {
    String execute(Map<String, String> args);
}