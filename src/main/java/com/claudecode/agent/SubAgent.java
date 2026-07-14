package com.claudecode.agent;

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
 * 子代理 —— 轻量独立 Agent，每个 SubAgent 有独立的角色、系统提示和对话历史，
 * 但共享 LLM 客户端和工具注册表。
 */
public class SubAgent {

    private final String name;
    private final MultiAgentRole role;
    private final DeepSeekClient llmClient;
    private final ToolRegistry toolRegistry;
    private final List<LLMModels.Message> conversation;
    private final ObjectMapper mapper = new ObjectMapper();

    private static final int MAX_ITERATIONS = 10;

    public SubAgent(String name, MultiAgentRole role, DeepSeekClient llmClient, ToolRegistry toolRegistry) {
        this.name = name;
        this.role = role;
        this.llmClient = llmClient;
        this.toolRegistry = toolRegistry;
        this.conversation = new ArrayList<>();
        this.conversation.add(LLMModels.Message.system(buildSystemPrompt()));
    }

    /** 执行任务，返回结果消息 */
    public MultiAgentMessage execute(MultiAgentMessage task) {
        return execute(task, System.out);
    }

    /** 执行任务并将输出写入指定流（用于并行时防交错） */
    public MultiAgentMessage execute(MultiAgentMessage task, PrintStream out) {
        conversation.add(LLMModels.Message.user(task.content()));

        int iteration = 0;
        while (iteration < MAX_ITERATIONS) {
            iteration++;

            try {
                LLMModels.ChatResponse response = llmClient.chat(
                        conversation, shouldUseTools() ? toolDefinitions() : null);

                if (!response.hasToolCalls()) {
                    String content = response.getContent();
                    conversation.add(LLMModels.Message.assistant(content));
                    out.println(content);
                    return MultiAgentMessage.result(name, role, content);
                }

                // 有工具调用
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

    /** 执行任务（带额外上下文） */
    public MultiAgentMessage executeWithContext(MultiAgentMessage task, String context, PrintStream out) {
        String enriched = context + "\n\n当前任务：" + task.content();
        MultiAgentMessage enrichedTask = new MultiAgentMessage(
                task.fromAgent(), task.fromRole(), enriched, task.type());
        return execute(enrichedTask, out);
    }

    /** 审查执行结果（Reviewer 专用） */
    public MultiAgentMessage review(String originalTask, String executionResult) {
        return review(originalTask, executionResult, System.out);
    }

    public MultiAgentMessage review(String originalTask, String executionResult, PrintStream out) {
        String input = "原始任务：" + originalTask + "\n\n执行结果：\n" + executionResult;
        return execute(MultiAgentMessage.task("orchestrator", input), out);
    }

    /** 清空对话历史（保留 system prompt） */
    public void clearHistory() {
        LLMModels.Message sys = conversation.get(0);
        conversation.clear();
        conversation.add(sys);
    }

    /** 只有执行者需要工具 */
    private boolean shouldUseTools() { return role == MultiAgentRole.WORKER; }

    public String getName() { return name; }
    public MultiAgentRole getRole() { return role; }

    private String buildSystemPrompt() {
        return switch (role) {
            case PLANNER -> """
                    你是规划专家。分析用户的任务需求，将其拆解为可执行的步骤。
                    只输出纯 JSON，不要包含任何解释文字、前后缀或 markdown 标记。

                    JSON 格式：
                    {
                      "summary": "任务概述",
                      "tasks": [
                        {
                          "id": "step_1",
                          "description": "步骤描述",
                          "type": "COMMAND",
                          "dependencies": []
                        }
                      ]
                    }
                    规则：
                    - 拆分为 2-6 个步骤
                    - 有依赖关系的步骤在 dependencies 中列出前置步骤 id
                    - description 必须使用绝对路径，不能使用相对路径
                    - 如果用户指定了目标目录，所有涉及文件/目录操作的步骤必须包含完整路径
                    """;
            case WORKER -> """
                    你是执行专家。根据当前任务调用工具完成操作。
                    可使用的工具：文件读取、文件写入、命令执行、目录列表等。
                    如果需要分析或总结，直接输出结果，不要调用工具。
                    只输出执行结果，不要添加额外说明。
                    """;
            case REVIEWER -> """
                    你是质量审查专家。检查执行结果是否满足任务要求。
                    按以下 JSON 格式输出审查结果：
                    {"approved": true, "summary": "通过"}
                    或
                    {"approved": false, "issues": ["问题1", "问题2"], "summary": "未通过"}
                    """;
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
