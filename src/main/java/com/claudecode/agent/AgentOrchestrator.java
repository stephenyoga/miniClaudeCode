package com.claudecode.agent;

import com.claudecode.llm.DeepSeekClient;
import com.claudecode.tool.ToolRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/**
 * Agent 编排器 —— 多 Agent 协作的"主控"。
 *
 * 协作流程：
 * 1. 用户输入 → Planner 拆解为步骤
 * 2. 解析计划 → 生成步骤 DAG
 * 3. 按依赖顺序分批执行（并行 + 池化 Worker）
 * 4. 每步执行完 → Reviewer 审查 → 不通过则重试（最多 2 次）
 * 5. 汇总结果
 */
public class AgentOrchestrator {

    private final DeepSeekClient llmClient;
    private final SubAgent planner;
    private final List<SubAgent> workers;
    private final SubAgent reviewer;
    private final ToolRegistry toolRegistry;
    private final ObjectMapper mapper = new ObjectMapper();

    private static final int MAX_RETRIES_PER_STEP = 2;

    private record ExecutionStep(
            String id, String description, String type,
            List<String> dependencies, String result, StepStatus status) {
        static ExecutionStep pending(String id, String description, String type, List<String> dependencies) {
            return new ExecutionStep(id, description, type, dependencies, null, StepStatus.PENDING);
        }
        ExecutionStep withResult(String r) { return new ExecutionStep(id, description, type, dependencies, r, StepStatus.COMPLETED); }
        ExecutionStep withFailed(String r) { return new ExecutionStep(id, description, type, dependencies, r, StepStatus.FAILED); }
    }

    private enum StepStatus { PENDING, RUNNING, COMPLETED, FAILED }

    public AgentOrchestrator(DeepSeekClient llmClient) {
        this(llmClient, new ToolRegistry());
    }

    public AgentOrchestrator(DeepSeekClient llmClient, ToolRegistry toolRegistry) {
        this.llmClient = llmClient;
        this.toolRegistry = toolRegistry;
        this.planner = new SubAgent("planner", MultiAgentRole.PLANNER, llmClient, toolRegistry);
        this.workers = List.of(
                new SubAgent("worker-1", MultiAgentRole.WORKER, llmClient, toolRegistry),
                new SubAgent("worker-2", MultiAgentRole.WORKER, llmClient, toolRegistry)
        );
        this.reviewer = new SubAgent("reviewer", MultiAgentRole.REVIEWER, llmClient, toolRegistry);
    }

    /** 运行多 Agent 协作任务 */
    public String run(String userInput) {
        // 1. 规划
        System.out.println("\n📋 第一阶段：规划");
        System.out.println("🧑‍💼 规划者正在分析任务...\n");
        MultiAgentMessage planResult = planner.execute(
                MultiAgentMessage.task("orchestrator",
                        "请为以下任务制定执行计划：\n" + userInput));
        planner.clearHistory();

        if (planResult.type() == MultiAgentMessage.Type.ERROR) {
            return "❌ 规划失败：" + planResult.content();
        }
        if (planResult.content() == null || planResult.content().isBlank()) {
            return "❌ 规划失败：规划者未能生成有效计划";
        }

        // 2. 解析计划
        List<ExecutionStep> steps = parsePlan(planResult.content());
        if (steps.isEmpty()) {
            return "❌ 规划失败：无法解析执行计划\n原始输出:\n" + planResult.content();
        }

        System.out.println("📋 执行计划（共 " + steps.size() + " 步）：");
        for (ExecutionStep step : steps) {
            String deps = step.dependencies().isEmpty() ? "" : " (依赖: " + String.join(", ", step.dependencies()) + ")";
            System.out.println("  ⏳ [" + step.id() + "] " + step.description() + deps);
        }
        System.out.println();

        // 3. 执行
        System.out.println("⚡ 第二阶段：执行\n");
        Map<String, Integer> retryCount = new ConcurrentHashMap<>();
        int batchIndex = 0;
        int cursor = 0;

        while (true) {
            List<ExecutionStep> executable = getExecutableSteps(steps);
            if (executable.isEmpty()) break;
            batchIndex++;

            if (executable.size() == 1) {
                ExecutionStep step = executable.get(0);
                SubAgent worker = workers.get(cursor % workers.size());
                cursor++;
                String context = buildStepContext(steps, step);
                runStep(step, steps, retryCount, worker, context, System.out);
                worker.clearHistory();
            } else {
                System.out.println("⚡ 批次 #" + batchIndex + "：" + executable.size() + " 个独立步骤并行执行\n");
                runBatchParallel(executable, steps, retryCount);
            }
        }

        // 4. 汇总
        for (ExecutionStep step : steps) {
            if (step.status() == StepStatus.PENDING) {
                System.out.println("⏭️ [" + step.id() + "] 因前置失败跳过: " + step.description());
            }
        }

        String result = buildFinalResult(steps);
        System.out.println("\n📋 " + result);
        return result;
    }

    /** 解析规划者输出的 JSON 计划（支持从自然语言中提取 JSON） */
    List<ExecutionStep> parsePlan(String raw) {
        try {
            // 提取第一个 { 到最后一个 }
            int start = raw.indexOf('{');
            int end = raw.lastIndexOf('}');
            if (start < 0 || end <= start) return List.of();
            String cleaned = raw.substring(start, end + 1)
                    .replaceAll("```json\\s*", "")
                    .replaceAll("```\\s*", "")
                    .trim();
            JsonNode root = mapper.readTree(cleaned);
            // 兼容 tasks / steps 两种字段名
            JsonNode tasksNode = root.path("steps");
            if (!tasksNode.isArray() || tasksNode.isEmpty()) {
                tasksNode = root.path("tasks");
            }
            if (!tasksNode.isArray() || tasksNode.isEmpty()) return List.of();

            List<ExecutionStep> steps = new ArrayList<>();
            Map<String, String> idMap = new HashMap<>();
            int idx = 1;

            for (JsonNode node : tasksNode) {
                String originalId = node.path("id").asText("step_" + idx);
                String newId = "step_" + idx++;
                idMap.put(originalId, newId);
                steps.add(ExecutionStep.pending(newId,
                        node.path("description").asText(""),
                        node.path("type").asText("COMMAND"),
                        new ArrayList<>()));
            }
            // 建立依赖
            idx = 1;
            for (JsonNode node : tasksNode) {
                String newId = "step_" + idx++;
                JsonNode deps = node.path("dependencies");
                if (deps.isArray()) {
                    List<String> mapped = new ArrayList<>();
                    for (JsonNode d : deps) mapped.add(idMap.getOrDefault(d.asText(), d.asText()));
                    int i = idx - 2;
                    ExecutionStep old = steps.get(i);
                    steps.set(i, new ExecutionStep(old.id(), old.description(), old.type(),
                            mapped, old.result(), old.status()));
                }
            }
            return steps;
        } catch (Exception e) {
            return List.of();
        }
    }

    /** 获取可执行的步骤（依赖全部完成） */
    private List<ExecutionStep> getExecutableSteps(List<ExecutionStep> steps) {
        Map<String, StepStatus> status = new HashMap<>();
        for (ExecutionStep s : steps) status.put(s.id(), s.status());
        return steps.stream()
                .filter(s -> s.status() == StepStatus.PENDING)
                .filter(s -> s.dependencies().stream()
                        .allMatch(d -> status.get(d) == StepStatus.COMPLETED))
                .toList();
    }

    /** 执行单个步骤（Worker 执行 + Reviewer 审查 + 重试） */
    private void runStep(ExecutionStep step, List<ExecutionStep> steps,
                         Map<String, Integer> retryCount, SubAgent worker,
                         String context, PrintStream out) {
        out.println("🔧 " + worker.getName() + " 执行 [" + step.id() + "]: " + step.description());

        MultiAgentMessage taskMsg = MultiAgentMessage.task("orchestrator", step.description());
        MultiAgentMessage result = worker.executeWithContext(taskMsg, context, out);

        if (result.type() == MultiAgentMessage.Type.ERROR) {
            updateStep(steps, step.id(), step.withFailed(result.content()));
            out.println("❌ [" + step.id() + "] 执行失败：" + result.content() + "\n");
            return;
        }
        if (result.content() == null || result.content().isBlank()) {
            updateStep(steps, step.id(), step.withFailed("结果为空"));
            out.println("❌ [" + step.id() + "] 结果为空\n");
            return;
        }

        // Reviewer 审查
        out.println("🔍 review 正在审查 [" + step.id() + "]...");
        MultiAgentMessage reviewResult = reviewer.review(step.description(), result.content(), out);
        reviewer.clearHistory();

        boolean approved = parseReviewApproval(reviewResult.content());
        String acceptedResult = result.content();

        if (approved) {
            updateStep(steps, step.id(), step.withResult(acceptedResult));
            out.println("✅ [" + step.id() + "] 审查通过\n");
            return;
        }

        // 不通过 → 重试
        int retries = retryCount.getOrDefault(step.id(), 0);
        String issues = parseReviewIssues(reviewResult.content());

        while (!approved && retries < MAX_RETRIES_PER_STEP) {
            retries++;
            retryCount.put(step.id(), retries);
            out.println("⚠️ [" + step.id() + "] 审查未通过，正在重试 (" + retries + "/" + MAX_RETRIES_PER_STEP + ")");
            out.println("   反馈: " + issues + "\n");

            String feedbackCtx = context + "\n\n之前的执行结果被拒绝，原因：\n" + issues;
            MultiAgentMessage retryResult = worker.executeWithContext(taskMsg, feedbackCtx, out);

            if (retryResult.type() == MultiAgentMessage.Type.ERROR) break;
            if (retryResult.content() == null || retryResult.content().isBlank()) continue;

            acceptedResult = retryResult.content();
            MultiAgentMessage retryReview = reviewer.review(step.description(), acceptedResult, out);
            reviewer.clearHistory();
            approved = parseReviewApproval(retryReview.content());
            issues = parseReviewIssues(retryReview.content());
        }

        updateStep(steps, step.id(), step.withResult(acceptedResult));
        out.println(approved ? "✅ [" + step.id() + "] 重试后审查通过\n"
                : "⚠️ [" + step.id() + "] 超过最大重试次数，保留当前结果\n");
    }

    /** 并行执行一批独立步骤 */
    private void runBatchParallel(List<ExecutionStep> batch, List<ExecutionStep> steps,
                                  Map<String, Integer> retryCount) {
        int parallelism = Math.min(batch.size(), workers.size());
        ExecutorService executor = Executors.newFixedThreadPool(parallelism, r -> {
            Thread t = new Thread(r, "team-executor");
            t.setDaemon(true);
            return t;
        });
        BlockingQueue<SubAgent> workerPool = new LinkedBlockingQueue<>(workers);
        Map<String, ByteArrayOutputStream> buffers = new ConcurrentHashMap<>();
        List<Future<?>> futures = new ArrayList<>();

        for (ExecutionStep step : batch) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            buffers.put(step.id(), baos);
            PrintStream stepOut = new PrintStream(baos, true, StandardCharsets.UTF_8);
            String context = buildStepContext(steps, step);

            futures.add(executor.submit(() -> {
                SubAgent w = null;
                try {
                    w = workerPool.take();
                    runStep(step, steps, retryCount, w, context, stepOut);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    updateStep(steps, step.id(), step.withFailed("并行执行被中断"));
                } finally {
                    if (w != null) { w.clearHistory(); workerPool.offer(w); }
                    stepOut.flush();
                }
                return null;
            }));
        }
        for (Future<?> f : futures) {
            try { f.get(); } catch (Exception ignored) {}
        }
        executor.shutdownNow();

        // 按步骤顺序 flush 缓冲区
        for (ExecutionStep step : batch) {
            ByteArrayOutputStream buf = buffers.get(step.id());
            if (buf != null && buf.size() > 0) {
                System.out.print(buf.toString(StandardCharsets.UTF_8));
                System.out.flush();
            }
        }
    }

    private boolean parseReviewApproval(String content) {
        if (content == null || content.isBlank()) return false;
        try {
            String cleaned = content.replaceAll("```json\\s*", "").replaceAll("```\\s*", "").trim();
            JsonNode root = mapper.readTree(cleaned);
            JsonNode approved = root.path("approved");
            return !approved.isMissingNode() && approved.asBoolean(false);
        } catch (Exception e) {
            String lower = content.toLowerCase();
            boolean hasPositive = lower.contains("\"approved\": true") || lower.contains("通过") || lower.contains("合格");
            boolean hasNegative = lower.contains("\"approved\": false") || lower.contains("不通过") || lower.contains("不合格");
            if (hasNegative && !hasPositive) return false;
            return hasPositive;
        }
    }

    private String parseReviewIssues(String content) {
        if (content == null || content.isBlank()) return "";
        try {
            String cleaned = content.replaceAll("```json\\s*", "").replaceAll("```\\s*", "").trim();
            JsonNode root = mapper.readTree(cleaned);
            JsonNode issues = root.path("issues");
            if (issues.isArray() && !issues.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (JsonNode n : issues) sb.append("- ").append(n.asText()).append("\n");
                return sb.toString().trim();
            }
            return root.path("summary").asText("");
        } catch (Exception e) {
            return "审查未通过，请改进结果";
        }
    }

    private synchronized void updateStep(List<ExecutionStep> steps, String id, ExecutionStep updated) {
        for (int i = 0; i < steps.size(); i++) {
            if (steps.get(i).id().equals(id)) {
                steps.set(i, updated);
                return;
            }
        }
    }

    private String buildStepContext(List<ExecutionStep> steps, ExecutionStep current) {
        StringBuilder ctx = new StringBuilder();
        for (ExecutionStep step : steps) {
            if (step.status() == StepStatus.COMPLETED && current.dependencies().contains(step.id())) {
                ctx.append("已完成依赖 [").append(step.id()).append("]: ").append(step.description()).append("\n");
                if (step.result() != null && !step.result().isBlank()) {
                    String preview = step.result().length() > 500 ? step.result().substring(0, 500) + "..." : step.result();
                    ctx.append("结果：").append(preview).append("\n");
                }
                ctx.append("\n");
            }
        }
        return ctx.toString();
    }

    private String buildFinalResult(List<ExecutionStep> steps) {
        boolean allDone = steps.stream().allMatch(s -> s.status() == StepStatus.COMPLETED);
        boolean hasFailed = steps.stream().anyMatch(s -> s.status() == StepStatus.FAILED);
        StringBuilder sb = new StringBuilder();
        if (allDone) sb.append("✅ 多 Agent 协作任务完成！\n");
        else if (hasFailed) sb.append("⚠️ 存在失败步骤，计划未完全完成\n");
        else sb.append("⚠️ 部分完成，存在未执行步骤\n");

        for (ExecutionStep s : steps) {
            String icon = s.status() == StepStatus.COMPLETED ? "✅" : s.status() == StepStatus.FAILED ? "❌" : "⏳";
            sb.append("  ").append(icon).append(" [").append(s.id()).append("] ").append(s.description()).append("\n");
        }
        return sb.toString();
    }
}
