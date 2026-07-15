package com.claudecode.memory;

import java.util.Map;

/**
 * Token 预算分配器 —— 管理模型上下文窗口的预算分配。
 *
 * 模型有固定的上下文窗口大小（如 DeepSeek V4 Pro 是 1M token），
 * 但全部用完会报错。TokenBudget 在窗口内预留一部分给 system prompt、
 * tool definitions 和 response，剩下的才是对话历史可用空间。
 *
 * 当对话历史 token 超过可用空间的 80% 时，触发压缩（needsCompression）。
 *
 * 窗口 -> 系统预留 -> 工具预留 -> 回复预留 = 对话可用空间
 * 1M    - 1000     - 2000      - 4000       = 993,000
 * 64K   - 500      - 800       - 2000       = 60,700
 */
public class TokenBudget {

    // 各模型的上下文窗口大小（从 DeepSeek 官方文档获取）
    private static final Map<String, Integer> MODEL_WINDOWS = Map.of(
            "deepseek-v4-pro", 1_000_000,
            "deepseek-v4-flash", 1_000_000,
            "deepseek-r1", 64_000,
            "deepseek-chat", 64_000,
            "deepseek-coder", 64_000,
            "deepseek-reasoner", 64_000
    );
    private static final int DEFAULT_WINDOW = 64_000;

    private final int contextWindow;       // 模型上下文窗口总量
    private final int reservedForSystem;   // system prompt 预留
    private final int reservedForTools;    // tool definitions 预留
    private final int reservedForResponse; // response 预留

    // Token 消耗统计（累计值，可通过 /tokens 查看）
    private int totalInputTokens;
    private int totalOutputTokens;
    private int llmCallCount;

    public TokenBudget(String modelName) {
        this.contextWindow = MODEL_WINDOWS.getOrDefault(modelName, DEFAULT_WINDOW);
        // 大窗口模型预留更多，小窗口模型预留更少
        if (contextWindow >= 1_000_000) {
            this.reservedForSystem = 1000;
            this.reservedForTools = 2000;
            this.reservedForResponse = 4000;
        } else {
            this.reservedForSystem = 500;
            this.reservedForTools = 800;
            this.reservedForResponse = 2000;
        }
    }

    /** 可用于对话历史的最大 Token 数（窗口总量 - 各种预留） */
    public int getAvailableForConversation() {
        return contextWindow - reservedForSystem - reservedForTools - reservedForResponse;
    }

    /** 是否超过可用预算的 80%，超过则触发压缩 */
    public boolean needsCompression(int currentTokens) {
        return currentTokens > getAvailableForConversation() * 0.8;
    }

    /** 记录一次 LLM 调用的 Token 消耗 */
    public void recordUsage(int inputTokens, int outputTokens) {
        totalInputTokens += inputTokens;
        totalOutputTokens += outputTokens;
        llmCallCount++;
    }

    /** 生成 Token 统计报告（用于 /tokens 和 /memory 命令） */
    public String getUsageReport() {
        return String.format(
                "Token 统计: 调用 %d 次 | 总输入: %d | 总输出: %d | 上下文窗口: %s",
                llmCallCount, totalInputTokens, totalOutputTokens, formatWindow(contextWindow));
    }

    /** 重置所有统计 */
    public void reset() {
        totalInputTokens = 0;
        totalOutputTokens = 0;
        llmCallCount = 0;
    }

    public int contextWindow() { return contextWindow; }
    public int totalInput() { return totalInputTokens; }
    public int totalOutput() { return totalOutputTokens; }
    public int callCount() { return llmCallCount; }

    /** 格式化窗口大小：1000000 → "1M"，64000 → "64K" */
    private static String formatWindow(int w) {
        if (w >= 1_000_000) return (w / 1_000_000) + "M";
        if (w >= 1_000) return (w / 1_000) + "K";
        return String.valueOf(w);
    }
}
