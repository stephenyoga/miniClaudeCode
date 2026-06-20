package com.claudecode.memory;

import java.util.Map;

/**
 * Token 预算分配器。
 * 根据模型自动匹配上下文窗口大小。
 */
public class TokenBudget {

    private static final Map<String, Integer> MODEL_WINDOWS = Map.of(
            "deepseek-v4-pro", 1_000_000,
            "deepseek-v4-flash", 1_000_000,
            "deepseek-r1", 64_000,
            "deepseek-chat", 64_000,
            "deepseek-coder", 64_000,
            "deepseek-reasoner", 64_000
    );
    private static final int DEFAULT_WINDOW = 64_000;

    private final int contextWindow;
    private final int reservedForSystem;
    private final int reservedForTools;
    private final int reservedForResponse;

    private int totalInputTokens;
    private int totalOutputTokens;
    private int llmCallCount;

    public TokenBudget(String modelName) {
        this.contextWindow = MODEL_WINDOWS.getOrDefault(modelName, DEFAULT_WINDOW);
        // 窗口越大，预留越宽松
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

    /** 实际可用于对话历史的 Token 数 */
    public int getAvailableForConversation() {
        return contextWindow - reservedForSystem - reservedForTools - reservedForResponse;
    }

    /** 是否需要压缩（超过可用量的 80%） */
    public boolean needsCompression(int currentTokens) {
        return currentTokens > getAvailableForConversation() * 0.8;
    }

    public void recordUsage(int inputTokens, int outputTokens) {
        totalInputTokens += inputTokens;
        totalOutputTokens += outputTokens;
        llmCallCount++;
    }

    public String getUsageReport() {
        return String.format(
                "Token 统计: 调用 %d 次 | 总输入: %d | 总输出: %d | 上下文窗口: %s",
                llmCallCount, totalInputTokens, totalOutputTokens, formatWindow(contextWindow));
    }

    public void reset() {
        totalInputTokens = 0;
        totalOutputTokens = 0;
        llmCallCount = 0;
    }

    public int contextWindow() { return contextWindow; }
    public int totalInput() { return totalInputTokens; }
    public int totalOutput() { return totalOutputTokens; }
    public int callCount() { return llmCallCount; }

    private static String formatWindow(int w) {
        if (w >= 1_000_000) return (w / 1_000_000) + "M";
        if (w >= 1_000) return (w / 1_000) + "K";
        return String.valueOf(w);
    }
}
