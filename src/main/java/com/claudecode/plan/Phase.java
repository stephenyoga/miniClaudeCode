package com.claudecode.plan;

/**
 * 阶段定义 —— 分层规划中的第一层抽象。
 *
 * 分层规划先让 LLM 输出宏观阶段（如"环境搭建"、"核心开发"、"测试验证"），
 * 再逐阶段细化子任务。Phase 就是这些宏观阶段的单位。
 *
 * @param id          阶段标识，如 "phase_1"
 * @param description 阶段描述，如 "环境搭建"
 */
public record Phase(String id, String description) {}
