package com.claudecode.agent;

import com.claudecode.llm.LLMModels;

import java.util.ArrayList;
import java.util.List;

/**
 * 共享对话上下文管理器。
 * ReAct 和 Plan-and-Execute 模式通过它共享"已经做过什么"的信息。
 */
public class ConversationManager {

    private final List<LLMModels.Message> messages;

    public ConversationManager(String systemPrompt) {
        this.messages = new ArrayList<>();
        messages.add(LLMModels.Message.system(systemPrompt));
    }

    /** 添加一条消息到对话历史 */
    public synchronized void add(LLMModels.Message msg) {
        messages.add(msg);
    }

    /** 添加用户消息 */
    public synchronized void addUser(String content) {
        messages.add(LLMModels.Message.user(content));
    }

    /** Plan 模式执行完成后，将操作摘要注入上下文 */
    public synchronized void addPlanSummary(String goal, String summary) {
        messages.add(LLMModels.Message.user(
                "【系统提示】刚才通过 Plan-and-Execute 模式完成了以下任务：" +
                "\n目标: " + goal +
                "\n执行摘要: " + summary +
                "\n后续对话请基于以上已完成的操作为上下文。"));
        messages.add(LLMModels.Message.assistant("已了解，之前的操作已完成。我会基于此继续协助。"));
    }

    /** 获取当前对话历史（用于传给 LLM） */
    public synchronized List<LLMModels.Message> toList() {
        return new ArrayList<>(messages);
    }

    /** 清空历史（保留系统提示） */
    public void clear() {
        LLMModels.Message sysMsg = messages.getFirst();
        messages.clear();
        messages.add(sysMsg);
    }

    /** 对话历史条数 */
    public int size() {
        return messages.size();
    }
}
