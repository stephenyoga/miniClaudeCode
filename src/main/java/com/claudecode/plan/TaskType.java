package com.claudecode.plan;

/**
 * 任务类型枚举 —— Plan-and-Execute 中每个任务的功能分类。
 *
 * LLM 规划时给每个 task 指定类型，PlanAndExecuteAgent 根据类型决定
 * 是否允许工具调用：
 * - FILE_READ / FILE_WRITE / COMMAND: 可以调工具
 * - ANALYSIS / VERIFICATION / PLANNING: 只做分析不调工具，直接输出
 */
public enum TaskType {
    /** 规划类任务，用于分析和决策（不调工具） */
    PLANNING,
    /** 读取文件，获取信息（可调 read_file 工具） */
    FILE_READ,
    /** 写入文件，输出结果（可调 write_file 工具） */
    FILE_WRITE,
    /** 执行命令，编译运行等（可调 execute_command 工具） */
    COMMAND,
    /** 分析已有结果，做出中间决策（不调工具） */
    ANALYSIS,
    /** 验证结果，检查正确性（不调工具） */
    VERIFICATION
}
