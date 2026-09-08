package com.claudecode.eval;

import com.claudecode.config.EnvConfig;
import com.claudecode.llm.DeepSeekClient;
import com.claudecode.llm.LLMModels;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.File;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * 评测编排器 —— 逐个执行 benchmark/cases/*.json 中的用例。
 *
 * 流程：
 * 1. 读取并解析用例（含 enabled / requires 依赖声明）
 * 2. 探测依赖（deepseek key、ollama embedding），缺失的用例标记 SKIP
 * 3. 为每个用例准备独立沙盒目录（必要时复制 assets 预置素材）
 * 4. 按 sessions 逐个 fork 子 JVM 运行 CaseRunner，收集 transcript
 * 5. 用 DeepSeek 作为 judge，按用例 rubric 对 transcript 打分
 * 6. 写 results/<id>.json，最后汇总 report.md
 *
 * 启动参数：<envFile(.env绝对路径)> <benchmarkRoot> [id过滤前缀]
 */
public class EvalRunner {

    private static final ObjectMapper mapper = new ObjectMapper();
    private static final int CHILD_TIMEOUT_SECONDS = 480;
    private static final int JUDGE_TEXT_BUDGET = 24000;

    private final Path root;
    private final Path casesDir;
    private final Path assetsDir;
    private final Path workspacesDir;
    private final Path resultsDir;
    private final Path tmpDir;
    private final DeepSeekClient client;
    private final String classpath;
    private final String javaExe;
    private final String idFilter;
    private final String envFile;

    private final List<ObjectNode> rows = new ArrayList<>();
    private int ran = 0, skipped = 0, failed = 0;

    public EvalRunner(String envFile, String benchmarkRoot, String idFilter) throws Exception {
        EnvConfig.init(envFile);
        this.envFile = Path.of(envFile).toAbsolutePath().normalize().toString();
        this.root = Path.of(benchmarkRoot).toAbsolutePath().normalize();
        this.casesDir = root.resolve("cases");
        this.assetsDir = root.resolve("assets");
        this.workspacesDir = root.resolve("workspaces");
        this.resultsDir = root.resolve("results");
        this.tmpDir = root.resolve(".tmp");
        this.idFilter = idFilter == null ? "" : idFilter.trim();
        Files.createDirectories(casesDir);
        Files.createDirectories(assetsDir);
        Files.createDirectories(workspacesDir);
        Files.createDirectories(resultsDir);
        Files.createDirectories(tmpDir);

        String apiKey = EnvConfig.get("DEEPSEEK_API_KEY");
        this.client = new DeepSeekClient(apiKey != null && !apiKey.isEmpty() ? apiKey : System.getenv("DEEPSEEK_API_KEY"));
        this.classpath = absolutizeClasspath(System.getProperty("java.class.path"));
        String sep = System.getProperty("file.separator");
        this.javaExe = System.getProperty("java.home") + sep + "bin" + sep
                + (System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java");
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("用法: EvalRunner <envFile> <benchmarkRoot> [id过滤前缀]");
            System.exit(2);
        }
        String filter = args.length > 2 ? args[2] : "";
        EvalRunner runner = new EvalRunner(args[0], args[1], filter);
        runner.runAll();
    }

    private void runAll() throws Exception {
        List<Path> caseFiles;
        try (Stream<Path> s = Files.list(casesDir)) {
            caseFiles = s.filter(p -> p.getFileName().toString().endsWith(".json"))
                    .filter(p -> p.getFileName().toString().startsWith("_") == false)
                    .sorted(Comparator.comparing(Path::toString)).toList();
        }
        for (Path cf : caseFiles) {
            if (!idFilter.isEmpty()) {
                boolean hit = false;
                for (String f : idFilter.split(",")) {
                    if (cf.getFileName().toString().contains(f.trim())) { hit = true; break; }
                }
                if (!hit) continue;
            }
            ObjectNode caze = (ObjectNode) mapper.readTree(Files.readString(cf));
            String id = caze.path("id").asText(cf.getFileName().toString());
            caze.put("sourceFile", cf.getFileName().toString());
            ObjectNode row = evaluateCase(caze);
            rows.add(row);
            String rowId = row.path("caseId").asText(id);
            try {
                Files.writeString(resultsDir.resolve(rowId + ".json"),
                        mapper.writerWithDefaultPrettyPrinter().writeValueAsString(row), StandardCharsets.UTF_8);
            } catch (Exception ignored) {}
        }
        writeReport();
        System.out.println("\n========== 评测汇总 ==========");
        System.out.printf("用例: %d  完成: %d  跳过: %d  失败: %d%n", rows.size(), ran, skipped, failed);
        for (ObjectNode r : rows) {
            System.out.printf("%-38s %s%n", r.path("caseId").asText(),
                    r.path("status").asText() + (r.has("score") ? "  score=" + r.path("score").asText() + "/" + r.path("maxScore").asText() : ""));
        }
    }

    private ObjectNode evaluateCase(ObjectNode caze) throws Exception {
        String id = caze.path("id").asText();
        ObjectNode row = mapper.createObjectNode();
        row.put("caseId", id);
        row.put("title", caze.path("title").asText(id));
        row.put("dimension", caze.path("dimension").asText());
        row.put("mode", caze.path("mode").asText());

        if (!caze.path("enabled").asBoolean(true)) {
            return skip(row, "enabled=false");
        }
        String skipReason = probeRequires(caze.path("requires"));
        if (skipReason != null) {
            return skip(row, skipReason);
        }

        Path sandbox = workspacesDir.resolve(id);
        deleteRecursively(sandbox);
        Files.createDirectories(sandbox);

        // 复制预置素材
        String seedDir = caze.path("workspace").path("seedDir").asText("");
        if (!seedDir.isEmpty()) {
            copyRecursively(assetsDir.resolve(seedDir), sandbox);
        }

        JsonNode sessions = caze.path("sessions");
        int sessionCount = sessions.isArray() && sessions.size() > 0 ? sessions.size() : 1;
        List<ObjectNode> transcripts = new ArrayList<>();
        boolean childOk = true;
        for (int i = 0; i < sessionCount && childOk; i++) {
            Path outJson = tmpDir.resolve(id + ".s" + i + ".json");
            Files.deleteIfExists(outJson);
            Path logFile = tmpDir.resolve(id + ".s" + i + ".log");
            int exit = forkChild(caze.get("sourceFile").asText(), sandbox, i, outJson, logFile);
            if (!Files.exists(outJson)) {
                childOk = false;
                row.put("error", "子进程退出码 " + exit + "，无 transcript 输出，日志见 " + logFile.getFileName());
                break;
            }
            ObjectNode tr = (ObjectNode) mapper.readTree(Files.readString(outJson));
            transcripts.add(tr);
            if (!"ok".equals(tr.path("status").asText())) {
                childOk = false;
                row.put("error", "会话 " + i + " 运行失败: " + tr.path("error").asText());
            }
        }

        if (!childOk) {
            failed++;
            row.put("status", "error");
            return row;
        }
        ran++;

        String transcriptText = buildTranscriptText(caze, transcripts);
        row.put("transcriptChars", transcriptText.length());
        judge(caze, transcriptText, row);
        return row;
    }

    /** 把 classpath 里相对路径条目转成绝对（子进程 cwd 是沙盒，相对路径会失效） */
    private static String absolutizeClasspath(String cp) {
        Path cwd = Path.of("").toAbsolutePath();
        String sep = File.pathSeparator;
        StringBuilder sb = new StringBuilder();
        for (String entry : cp.split(java.util.regex.Pattern.quote(sep))) {
            if (entry.isEmpty()) continue;
            String abs = Path.of(entry).isAbsolute() ? entry : cwd.resolve(entry).normalize().toString();
            if (sb.length() > 0) sb.append(sep);
            sb.append(abs);
        }
        return sb.toString();
    }

    // ── 依赖探测 ───────────────────────────────────────────────
    private String probeRequires(JsonNode requires) {
        if (!requires.isArray()) return null;
        for (JsonNode r : requires) {
            String name = r.asText();
            if ("deepseek".equals(name)) {
                String key = EnvConfig.get("DEEPSEEK_API_KEY");
                if (key == null || key.isEmpty()) return "缺少 DEEPSEEK_API_KEY";
            }
            if ("ollama".equals(name)) {
                if (!ollamaAlive()) return "Ollama embedding 不可达(localhost:11434)";
            }
        }
        return null;
    }

    private boolean ollamaAlive() {
        try {
            HttpURLConnection c = (HttpURLConnection) URI.create("http://localhost:11434/api/tags").toURL().openConnection();
            c.setConnectTimeout(2500);
            c.setReadTimeout(2500);
            c.setRequestMethod("GET");
            int code = c.getResponseCode();
            c.disconnect();
            return code == 200;
        } catch (Exception e) {
            return false;
        }
    }

    private ObjectNode skip(ObjectNode row, String reason) {
        skipped++;
        row.put("status", "skipped");
        row.put("skipReason", reason);
        return row;
    }

    // ── fork 子 JVM ─────────────────────────────────────────────
    private int forkChild(String caseFileName, Path sandbox, int sessionIdx, Path outJson, Path logFile) throws Exception {
        Path caseFile = casesDir.resolve(caseFileName);
        List<String> cmd = new ArrayList<>();
        cmd.add(javaExe);
        cmd.add("-cp");
        cmd.add(classpath);
        cmd.add("com.claudecode.eval.CaseRunner");
        cmd.add(this.envFile); // 项目根 .env 绝对路径
        cmd.add(caseFile.toString());
        cmd.add(sandbox.toString());
        cmd.add(String.valueOf(sessionIdx));
        cmd.add(outJson.toString());

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(sandbox.toFile());
        pb.redirectErrorStream(true);
        pb.redirectOutput(logFile.toFile());
        Process p = pb.start();
        boolean done = p.waitFor(CHILD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!done) {
            p.destroyForcibly();
            return -99;
        }
        return p.exitValue();
    }

    // ── transcript / judge ──────────────────────────────────────
    private String buildTranscriptText(JsonNode caze, List<ObjectNode> transcripts) {
        StringBuilder sb = new StringBuilder();
        sb.append("【任务目标】").append(caze.path("promptDoc").asText("")).append("\n\n");
        int budget = JUDGE_TEXT_BUDGET;
        for (int i = 0; i < transcripts.size(); i++) {
            ObjectNode tr = transcripts.get(i);
            sb.append("===== 会话 ").append(i).append(" =====\n");
            if (tr.has("action")) sb.append("(动作: saveFacts)\n");
            if (tr.has("preIndex")) sb.append("[RAG 索引结果] ").append(tr.path("preIndex").asText()).append("\n");
            if (tr.has("prompt")) sb.append("[用户输入] ").append(tr.path("prompt").asText()).append("\n");
            if (tr.has("finalAnswer")) {
                sb.append("[Agent 最终答复]\n").append(tr.path("finalAnswer").asText()).append("\n");
            }
            // 会话消息摘要（工具调用链）
            JsonNode msgs = tr.path("messages");
            if (msgs.isArray() && msgs.size() > 0) {
                sb.append("[会话消息摘要]\n");
                for (JsonNode m : msgs) {
                    String line = "";
                    if (m.has("toolCalls")) line = "  (调用工具: " + m.path("toolCalls").asText() + ")";
                    else if (m.has("reasoning")) line = "  (思考)";
                    if (m.has("content") && !m.path("content").asText().isEmpty()) {
                        String c = m.path("content").asText();
                        if (c.length() > 500) c = c.substring(0, 500) + "…";
                        line = (m.path("role").asText().equals("user") ? "  [你] " : "  [Agent] ") + c;
                    }
                    if (!line.isBlank()) sb.append(line).append("\n");
                }
            }
            // 工作区产物
            JsonNode ws = tr.path("workspace");
            if (ws.isObject()) {
                sb.append("[工作区产物]\n");
                JsonNode tree = ws.path("tree");
                int filesShown = 0;
                if (tree.isArray()) {
                    for (JsonNode f : tree) {
                        String path = f.path("path").asText();
                        boolean hasContent = f.has("content") && !f.path("content").asText().isEmpty();
                        if (budget <= 0 && hasContent) {
                            sb.append("  ").append(path).append(" (").append(f.path("size").asText()).append(" 字符，内容省略)\n");
                            continue;
                        }
                        if (hasContent) {
                            String content = f.path("content").asText();
                            sb.append("  ── ").append(path).append(" ──\n").append(content).append("\n");
                            budget -= content.length();
                            filesShown++;
                        } else {
                            sb.append("  ").append(path).append("\n");
                        }
                        if (filesShown >= 40) break;
                    }
                }
            }
            if (budget <= 0) break;
        }
        return sb.toString();
    }

    private void judge(JsonNode caze, String transcript, ObjectNode row) {
        try {
            int maxScore = caze.path("judge").path("maxScore").asInt(100);
            ArrayNode criteria = (ArrayNode) caze.path("judge").path("criteria");
            StringBuilder sys = new StringBuilder();
            sys.append("你是严谨的编码智能体评测官。你会看到一次真实任务的对话转录与产物，请按下列评分标准打分。\n");
            sys.append("打分为 0~100 的数值，只输出一个 JSON 对象，不要输出其他文字。JSON 结构：\n");
            sys.append("{\"score\": <0~100整数>, \"dimensions\": [{\"criterion\": \"<标准名>\", \"score\": <0~100整数>, \"comment\": \"<理由>\"}], \"overall\": \"<总结评语(2~4句)>\"}\n");
            sys.append("\n【参考答案/期望】\n").append(caze.path("expectation").asText("")).append("\n");
            sys.append("\n【分维度标准】\n");
            int i = 1;
            for (JsonNode c : criteria) {
                sys.append(i++).append(". ").append(c.asText()).append("\n");
            }
            sys.append("\n判分建议：80~100 优秀，60~79 基本达标但有问题，40~59 部分完成，0~39 失败。");
            sys.append("若 Agent 明确完成了目标且产物正确给高分；注意用户没有要求的话不要因缺少锦上添花而扣分。");

            LLMModels.Message sysMsg = LLMModels.Message.system(sys.toString());
            LLMModels.Message userMsg = LLMModels.Message.user(transcript);
            var resp = client.chat(List.of(sysMsg, userMsg), null);
            String content = resp != null ? resp.getContent() : null;
            if (content == null || content.isBlank()) {
                throw new IllegalStateException("judge 无返回");
            }
            JsonNode parsed = parseLooseJson(content);
            if (parsed == null) {
                throw new IllegalStateException("judge 输出无法解析: " + content.substring(0, Math.min(200, content.length())));
            }
            int score = parsed.path("score").asInt(-1);
            if (score < 0) {
                // 兜底：由各维度平均推出
                ArrayNode dims = (ArrayNode) parsed.path("dimensions");
                int sum = 0, n = 0;
                if (dims.isArray()) {
                    for (JsonNode d : dims) {
                        int s = d.path("score").asInt(-1);
                        if (s >= 0) { sum += s; n++; }
                    }
                }
                score = n > 0 ? sum / n : 0;
            }
            score = Math.max(0, Math.min(score, maxScore));
            row.put("score", score);
            row.put("maxScore", maxScore);
            row.put("status", "scored");
            row.set("dimensions", parsed.path("dimensions"));
            row.put("overall", parsed.path("overall").asText(""));
        } catch (Exception e) {
            row.put("status", "judge-error");
            row.put("judgeError", String.valueOf(e));
        }
    }

    private JsonNode parseLooseJson(String text) {
        try {
            return mapper.readTree(text);
        } catch (Exception e) {
            int s = text.indexOf('{');
            int e2 = text.lastIndexOf('}');
            if (s >= 0 && e2 > s) {
                try {
                    return mapper.readTree(text.substring(s, e2 + 1));
                } catch (Exception ignored) {}
            }
            return null;
        }
    }

    // ── 文件工具 ────────────────────────────────────────────────
    private static void copyRecursively(Path src, Path dst) throws Exception {
        if (!Files.exists(src)) throw new IllegalStateException("预置素材不存在: " + src);
        try (Stream<Path> s = Files.walk(src)) {
            for (Path p : s.toList()) {
                Path target = dst.resolve(src.relativize(p).toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(p, target);
                }
            }
        }
    }

    private static void deleteRecursively(Path dir) {
        if (!Files.exists(dir)) return;
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path p : s.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        } catch (Exception ignored) {}
    }

    // ── 报告 ────────────────────────────────────────────────────
    private void writeReport() throws Exception {
        StringBuilder md = new StringBuilder();
        md.append("# Mini Claude Code 评测报告\n\n");
        md.append("生成时间: ").append(java.time.LocalDateTime.now()).append("\n\n");
        md.append("| 用例 | 维度 | 状态 | 得分 | 说明 |\n");
        md.append("|---|---|---|---|---|\n");
        for (ObjectNode r : rows) {
            String status = r.path("status").asText();
            String scoreCell;
            String note;
            if ("scored".equals(status)) {
                scoreCell = r.path("score").asText() + "/" + r.path("maxScore").asText();
                note = r.path("overall").asText().replace("\n", " ").length() > 120
                        ? r.path("overall").asText().replace("\n", " ").substring(0, 120) + "…"
                        : r.path("overall").asText().replace("\n", " ");
            } else if ("skipped".equals(status)) {
                scoreCell = "-";
                note = "跳过: " + r.path("skipReason").asText();
            } else {
                scoreCell = "-";
                note = "失败/错误: " + r.path("error").asText("");
            }
            md.append("| ").append(r.path("caseId").asText())
                    .append(" | ").append(r.path("dimension").asText())
                    .append(" | ").append(status)
                    .append(" | ").append(scoreCell)
                    .append(" | ").append(note).append(" |\n");
        }
        Files.writeString(resultsDir.resolve("report.md"), md.toString());
        System.out.println("📄 报告已写入: " + resultsDir.resolve("report.md"));
    }
}
