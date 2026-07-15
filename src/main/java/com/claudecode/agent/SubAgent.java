package com.claudecode.agent;

import com.claudecode.config.PromptAssembler;
import com.claudecode.llm.DeepSeekClient;
import com.claudecode.llm.LLMModels;
import com.claudecode.tool.ToolRegistry;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 子代理 —— 可复用的轻量 Agent，用于 Multi-Agent 协作。
 *
 * 与 Agent 的区别：
 * - 有独立的对话历史（每个 SubAgent 各一份）
 * - 角色化 system prompt（PLANNER / WORKER / REVIEWER 各有不同的提示词）
 * - 只有 WORKER 可以调用工具，PLANNER 和 REVIEWER 只能输出分析
 * - 支持注入外部 PrintStream（并行执行时各写各的 buffer，防输出交错）
 *
 * 生命周期：创建 → execute(任务) → clearHistory() → execute(下一个任务)
 */
public class SubAgent {

    private final String name;
    private final MultiAgentRole role;
    private final DeepSeekClient llmClient;
    private final ToolRegistry toolRegistry;
    /** 独立的对话历史，不受 Agent 主循环和其他 SubAgent 影响 */
    private final List<LLMModels.Message> conversation;
    private final ObjectMapper mapper = new ObjectMapper();

    private static final int MAX_ITERATIONS = 10;

    public SubAgent(String name, MultiAgentRole role, DeepSeekClient llmClient, ToolRegistry toolRegistry) {
        this.name = name;
        this.role = role;
        this.llmClient = llmClient;
        this.toolRegistry = toolRegistry;
        this.conversation = new ArrayList<>();
        // 初始化时注入角色对应的 system prompt
        this.conversation.add(LLMModels.Message.system(buildSystemPrompt()));
    }

    /**
     * 执行任务（输出到 System.out）。
     * 内部是 mini ReAct 循环：调 LLM → 有工具调用则执行 → 继续循环 → 直到 LLM 直接回答。
     */
    public MultiAgentMessage execute(MultiAgentMessage task) {
        return execute(task, System.out);
    }

    /**
     * 执行任务并将流式输出写入指定 PrintStream。
     * 并行执行时每个 SubAgent 写入自己的 ByteArrayOutputStream，最后顺序 flush。
     */
    public MultiAgentMessage execute(MultiAgentMessage task, PrintStream out) {
        conversation.add(LLMModels.Message.user(task.content()));

        int iteration = 0;
        while (iteration < MAX_ITERATIONS) {
            iteration++;

            try {
                // 只有 WORKER 传递工具定义，PLANNER 和 REVIEWER 不做工具调用
                LLMModels.ChatResponse response = llmClient.chat(
                        conversation, shouldUseTools() ? toolDefinitions() : null);

                if (!response.hasToolCalls()) {
                    // LLM 直接回答 → 保存到对话历史并返回结果
                    String content = response.getContent();
                    conversation.add(LLMModels.Message.assistant(content));
                    out.println(content);
                    return MultiAgentMessage.result(name, role, content);
                }

                // LLM 要调工具 → 执行工具，结果回灌到对话历史后继续循环
                conversation.add(LLMModels.Message.assistantWithToolCall(response.getToolCalls()));
                for (LLMModels.ToolCall tc : response.getToolCalls()) {
                    Map<String, String> args = parseArguments(tc.function().arguments());
                    String result = toolRegistry.executeTool(tc.function().name(), args);
                    out.println("  🔧 " + tc.function().name() + "(" + truncate(tc.function().arguments(), 80) + ")");
                    conversation.add(LLMModels.Message.tool(result, tc.id()));
                }
            } catch (Exception e) {
                return MultiAgentMessage.error(name, role, "执行失败: " + e.getMessage());
            }
        }

        return MultiAgentMessage.error(name, role, "超过最大迭代次数");
    }

    /** 执行任务（带额外上下文注入，用于 Reviewer 审查不通过后带反馈重试） */
    public MultiAgentMessage executeWithContext(MultiAgentMessage task, String context, PrintStream out) {
        String enriched = context + "\n\n当前任务：" + task.content();
        MultiAgentMessage enrichedTask = new MultiAgentMessage(
                task.fromAgent(), task.fromRole(), enriched, task.type());
        return execute(enrichedTask, out);
    }

    /** Reviewer 审查执行结果 */
    public MultiAgentMessage review(String originalTask, String executionResult) {
        return review(originalTask, executionResult, System.out);
    }

    public MultiAgentMessage review(String originalTask, String executionResult, PrintStream out) {
        String input = "原始任务：" + originalTask + "\n\n执行结果：\n" + executionResult;
        return execute(MultiAgentMessage.task("orchestrator", input), out);
    }

    /** 清空对话历史（保留 system prompt），准备执行下一个独立任务 */
    public void clearHistory() {
        LLMModels.Message sys = conversation.get(0);
        conversation.clear();
        conversation.add(sys);
    }

    /** 只有 WORKER 可以调工具，PLANNER 和 REVIEWER 只输出文本 */
    private boolean shouldUseTools() { return role == MultiAgentRole.WORKER; }

    public String getName() { return name; }
    public MultiAgentRole getRole() { return role; }

    /** 根据角色加载对应的 system prompt */
    private String buildSystemPrompt() {
        return switch (role) {
            case PLANNER -> PromptAssembler.load("modes/team-planner.md");
            case WORKER -> PromptAssembler.load("modes/team-worker.md");
            case REVIEWER -> PromptAssembler.load("modes/team-reviewer.md");
        };
    }

    private List<LLMModels.Tool> toolDefinitions() {
        return toolRegistry.getAllTools().stream()
                .map(t -> new LLMModels.Tool(t.name(), t.description(), t.parameters()))
                .toList();
    }

    private Map<String, String> parseArguments(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) return Map.of();
        try {
            Map<String, Object> raw = mapper.readValue(argumentsJson,
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
            Map<String, String> result = new java.util.LinkedHashMap<>();
            for (var e : raw.entrySet()) {
                result.put(e.getKey(), e.getValue() == null ? "" : String.valueOf(e.getValue()));
            }
            return result;
        } catch (Exception e) {
            return Map.of("arguments", argumentsJson);
        }
    }

    private String truncate(String s, int n) {
        return s != null && s.length() > n ? s.substring(0, n) + "..." : s;
    }
}
