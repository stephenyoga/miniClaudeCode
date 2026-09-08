package com.claudecode.eval;

import com.claudecode.agent.Agent;
import com.claudecode.agent.AgentOrchestrator;
import com.claudecode.agent.PlanAndExecuteAgent;
import com.claudecode.config.EnvConfig;
import com.claudecode.config.PromptAssembler;
import com.claudecode.llm.DeepSeekClient;
import com.claudecode.llm.LLMModels;
import com.claudecode.memory.MemoryManager;
import com.claudecode.plan.ExecutionPlan;
import com.claudecode.rag.CodeIndex;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 单用例执行器 —— 在隔离的沙盒工作目录中运行一个评测会话。
 *
 * EvalRunner 会为每个 (case, session) fork 一个独立子 JVM，并把子进程的工作目录
 * 设置为该用例的沙盒目录。这样 Agent 的 write_file / execute_command 等工具
 * 的相对路径操作都会落在沙盒内，互不干扰、不污染真实项目。
 *
 * 每个会话结束后，CaseRunner 把「用户提示 + Agent 最终答复 + 会话消息摘要 +
 * 工作区产物快照」打包成 JSON transcript 写到指定文件，供父进程做 LLM-as-judge。
 *
 * 启动参数：<envFile> <caseJson> <sandboxDir> <sessionIndex> <outJson>
 */
public class CaseRunner {

    private static final ObjectMapper mapper = new ObjectMapper();
    private static final int MAX_MSG_CHARS = 800;     // 单条会话消息摘要上限
    private static final int MAX_FILE_CHARS = 2000;   // 工作区单个文件内容上限

    public static void main(String[] args) {
        if (args.length < 5) {
            System.err.println("用法: CaseRunner <envFile> <caseJson> <sandboxDir> <sessionIndex> <outJson>");
            System.exit(2);
        }
        String envFile = args[0];
        String caseJsonPath = args[1];
        String sandbox = args[2];
        int sessionIdx = Integer.parseInt(args[3]);
        String outJson = args[4];
        try {
            EnvConfig.init(envFile);
            JsonNode caze = mapper.readTree(Files.readString(Path.of(caseJsonPath)));
            ObjectNode result = mapper.createObjectNode();
            result.put("caseId", caze.path("id").asText());
            result.put("sessionIndex", sessionIdx);
            result.put("mode", caze.path("mode").asText());

            JsonNode session = caze.path("sessions").get(sessionIdx);
            String action = session.path("action").asText("run");

            // 会话 0 且用例要求预索引 → 先用 RAG 索引沙盒代码库（等价用户手动 /index）
            boolean needIndex = sessionIdx == 0 && caze.path("preIndex").asBoolean(false);
            if (needIndex) {
                String idx = new CodeIndex().index(".");
                result.put("preIndex", truncate(idx, 300));
            }

            if (!"saveFacts".equals(action)) {
                String apiKey = EnvConfig.get("DEEPSEEK_API_KEY");
                if (apiKey == null || apiKey.isEmpty()) {
                    apiKey = System.getenv("DEEPSEEK_API_KEY");
                }
                if (apiKey == null || apiKey.isEmpty()) {
                    throw new IllegalStateException("缺少 DEEPSEEK_API_KEY");
                }
                DeepSeekClient client = new DeepSeekClient(apiKey);
                MemoryManager mm = new MemoryManager(client);
                mm.setSystemMessage(buildSystemPrompt(client));

                ArrayNode finalAnswers = mapper.createArrayNode();
                ArrayNode messagesAll = mapper.createArrayNode();
                List<String> prompts = new ArrayList<>();
                JsonNode turns = session.path("turns");
                if (turns.isArray() && turns.size() > 0) {
                    // 多轮对话：同一 MemoryManager 内连续追问，用于记忆召回测试
                    int i = 0;
                    for (JsonNode t : turns) {
                        String prompt = t.isTextual() ? t.asText() : t.path("text").asText();
                        prompts.add(prompt);
                        String answer = runOnce(caze, client, mm, prompt);
                        finalAnswers.add(answer);
                        JsonNode msgs = summarizeMessages(mm, sessionIdx);
                        messagesAll.addAll((ArrayNode) msgs.deepCopy());
                        boolean last = ++i == turns.size();
                        if (last && caze.path("saveFacts").asBoolean(false)) {
                            mm.extractAndSaveFacts();
                            result.put("savedFacts", true);
                        }
                    }
                    result.put("prompts", String.join(" || ", prompts));
                    result.put("finalAnswer", finalAnswers.toString());
                    result.set("messages", messagesAll);
                } else {
                    String prompt = session.path("prompt").asText();
                    result.put("prompt", prompt);
                    String answer = runOnce(caze, client, mm, prompt);
                    result.put("finalAnswer", answer);
                    if (caze.path("saveFacts").asBoolean(false)) {
                        mm.extractAndSaveFacts();
                        result.put("savedFacts", true);
                    }
                    result.set("messages", summarizeMessages(mm, sessionIdx));
                }
            } else {
                // saveFacts 动作：把当前会话抽取的事实持久化（供下一次 fork 的跨会话召回）
                String apiKey = EnvConfig.get("DEEPSEEK_API_KEY");
                if (apiKey == null || apiKey.isEmpty()) apiKey = System.getenv("DEEPSEEK_API_KEY");
                if (apiKey == null || apiKey.isEmpty()) throw new IllegalStateException("缺少 DEEPSEEK_API_KEY");
                DeepSeekClient client = new DeepSeekClient(apiKey);
                MemoryManager mm = new MemoryManager(client);
                mm.setSystemMessage(buildSystemPrompt(client));
                mm.extractAndSaveFacts();
                result.put("action", "saveFacts");
            }
            result.put("status", "ok");
            result.set("workspace", snapshotWorkspace(Path.of(sandbox), sandbox));

            Files.createDirectories(Path.of(outJson).getParent());
            Files.writeString(Path.of(outJson), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result));
            System.exit(0);
        } catch (Throwable t) {
            t.printStackTrace();
            try {
                ObjectNode err = mapper.createObjectNode();
                err.put("status", "error");
                err.put("error", String.valueOf(t));
                Files.createDirectories(Path.of(outJson).getParent());
                Files.writeString(Path.of(outJson), err.toPrettyString());
            } catch (Exception ignored) {}
            System.exit(1);
        }
    }

    /** 构造与被测 Main 一致的全局 system prompt（modes/agent.md + 变量替换） */
    private static String buildSystemPrompt(DeepSeekClient client) {
        String model = client.getModel();
        String os = System.getProperty("os.name", "Windows");
        String osHint = os.toLowerCase().contains("win")
                ? "Windows。Shell命令必须使用cmd格式(dir/del/rmdir/mkdir/type, 不要用ls/rm/cat。路径分隔符用\\)。"
                : "Linux/macOS。Shell命令使用bash格式。";
        try {
            return PromptAssembler.load("modes/agent.md", Map.of("model", model, "os", os, "os_hint", osHint));
        } catch (Exception e) {
            return "你是 Mini Claude Code，一个编程助手。环境: " + os;
        }
    }

    /** 按 mode 分发：react / plan / team */
    private static String runOnce(JsonNode caze, DeepSeekClient client, MemoryManager mm, String prompt)
            throws Exception {
        String mode = caze.path("mode").asText("react");
        return switch (mode) {
            case "plan" -> runPlan(client, mm, prompt);
            case "team" -> new AgentOrchestrator(client).run(prompt);
            default -> new Agent(client, mm).run(prompt);
        };
    }

    private static String runPlan(DeepSeekClient client, MemoryManager mm, String prompt) throws Exception {
        PlanAndExecuteAgent planAgent = new PlanAndExecuteAgent(client, mm);
        ExecutionPlan plan = planAgent.plan(prompt);
        planAgent.execute(plan);
        return planAgent.summarize(plan);
    }

    /** 把会话上下文压缩为可读摘要，供 judge 参考 Agent 用了哪些工具 */
    private static ArrayNode summarizeMessages(MemoryManager mm, int sessionIdx) {
        ArrayNode arr = mapper.createArrayNode();
        try {
            for (LLMModels.Message m : mm.getConversationContext()) {
                ObjectNode o = mapper.createObjectNode();
                o.put("role", m.role());
                if (m.reasoningContent() != null && !m.reasoningContent().isEmpty()) {
                    o.put("reasoning", truncate(m.reasoningContent(), 400));
                }
                if (m.toolCalls() != null && !m.toolCalls().isEmpty()) {
                    List<String> calls = new ArrayList<>();
                    for (LLMModels.ToolCall tc : m.toolCalls()) {
                        String fn = tc.function() != null ? tc.function().name() : "?";
                        calls.add(fn);
                    }
                    o.put("toolCalls", String.join(",", calls));
                }
                if (m.content() != null && !m.content().isEmpty()) {
                    o.put("content", truncate(m.content(), MAX_MSG_CHARS));
                }
                arr.add(o);
            }
        } catch (Exception e) {
            ObjectNode o = arr.addObject();
            o.put("dumpError", String.valueOf(e));
        }
        return arr;
    }

    /** 递归快照沙盒产物（跳过运行期目录与超长二进制内容） */
    private static ObjectNode snapshotWorkspace(Path sandbox, String absPrefix) {
        ObjectNode root = mapper.createObjectNode();
        root.put("files", 0);
        ArrayNode tree = mapper.createArrayNode();
        try (Stream<Path> paths = Files.walk(sandbox)) {
            List<Path> list = paths.sorted(Comparator.comparing(Path::toString)).toList();
            for (Path p : list) {
                if (Files.isDirectory(p)) continue;
                String rel = p.toAbsolutePath().normalize().toString();
                if (rel.startsWith(absPrefix)) rel = rel.substring(absPrefix.length());
                rel = rel.replace('\\', '/').replaceFirst("^/", "");
                if (skipInSnapshot(rel)) continue;
                if (tree.size() >= 60) break;
                ObjectNode f = tree.addObject();
                f.put("path", rel);
                String ext = extOf(rel);
                if (isTextual(ext)) {
                    try {
                        String content = Files.readString(p, StandardCharsets.UTF_8);
                        f.put("size", content.length());
                        f.put("content", truncate(content, MAX_FILE_CHARS));
                    } catch (Exception e) {
                        f.put("content", "<不可读: " + e.getMessage() + ">");
                    }
                } else {
                    try {
                        f.put("size", Files.size(p));
                    } catch (Exception e) {
                        f.put("size", -1);
                    }
                }
            }
        } catch (IOException e) {
            root.put("snapshotError", String.valueOf(e));
        }
        root.set("tree", tree);
        return root;
    }

    private static boolean skipInSnapshot(String rel) {
        return rel.startsWith("memory_db/")
                || rel.startsWith("rag_db/")
                || rel.startsWith(".git/")
                || rel.endsWith(".class")
                || rel.endsWith(".jar")
                || rel.endsWith(".exe");
    }

    private static boolean isTextual(String ext) {
        return switch (ext) {
            case "java", "py", "js", "ts", "jsx", "tsx", "vue", "html", "css", "json",
                 "xml", "yaml", "yml", "md", "txt", "properties", "sh", "bat", "toml" -> true;
            default -> false;
        };
    }

    private static String extOf(String name) {
        int i = name.lastIndexOf('.');
        return i < 0 ? "" : name.substring(i + 1).toLowerCase();
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…[截断]";
    }
}
