package com.claudecode.tool;

import java.util.Map;

/**
 * 工具执行器接口 —— 每个 Tool 需要实现此接口来定义自己的执行逻辑。
 *
 * 例如 read_file 的 execute() 实现"读取文件内容并返回"，
 * write_file 的 execute() 实现"写入文件并返回结果信息"。
 *
 * @see Tool
 * @see ToolRegistry
 */
@FunctionalInterface
public interface ToolExecutor {
    /**
     * 执行工具逻辑。
     * @param args 工具参数（key=参数名, value=参数值，均以 String 传输）
     * @return 执行结果文本（会作为 tool 消息返回给 LLM）
     */
    String execute(Map<String, String> args);
}
