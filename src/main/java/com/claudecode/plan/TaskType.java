package com.claudecode.plan;

/**
 * 任务类型枚举
 */
public enum TaskType {
    /** 规划任务，用于分析和决策 */
    PLANNING,
    /** 读取文件，获取信息 */
    FILE_READ,
    /** 写入文件，输出结果 */
    FILE_WRITE,
    /** 执行命令，编译运行等 */
    COMMAND,
    /** 分析结果，中间决策 */
    ANALYSIS,
    /** 验证结果，检查正确性 */
    VERIFICATION
}
