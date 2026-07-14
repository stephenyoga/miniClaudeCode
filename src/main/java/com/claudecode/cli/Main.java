
package com.claudecode.cli;

import com.claudecode.agent.Agent;
import com.claudecode.agent.PlanAndExecuteAgent;
import com.claudecode.config.EnvConfig;
import com.claudecode.config.PromptAssembler;
import com.claudecode.agent.AgentOrchestrator;
import com.claudecode.memory.MemoryManager;
import com.claudecode.rag.CodeIndex;
import com.claudecode.rag.CodeRetriever;

import java.io.File;
import java.util.Map;
import java.io.FileNotFoundException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;

/**
 * 交互式命令行界面
 * 提供用户与Agent交互的入口
 */
public class Main {

    private static final String API_KEY_ENV = "DEEPSEEK_API_KEY";
    private static final String ENV_FILE = ".env";

    /** 运行模式 */
    private enum Mode { REACT, PLAN_EXECUTE, TEAM }
    private static Mode currentMode = Mode.REACT;
    private static MemoryManager memoryManager;
    private static AgentOrchestrator orchestrator;

    public static void main(String[] args) {
        // 强制 System.out/err 编码为 UTF-8，解决终端 Emoji 乱码
        System.setOut(new PrintStream(System.out, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(System.err, true, StandardCharsets.UTF_8));

        // Windows 下切换终端代码页到 UTF-8 (65001)
        String osLower = System.getProperty("os.name").toLowerCase();
        if (osLower.contains("win")) {
            try {
                new ProcessBuilder("cmd", "/c", "chcp 65001 >nul").inheritIO().start().waitFor();
            } catch (Exception ignored) {}
        }

        printBanner();

        // 初始化环境配置（读取 .env 文件）
        EnvConfig.init();

        // 加载 API Key
        String apiKey = loadApiKey();
        if (apiKey == null || apiKey.isEmpty()) {
            System.err.println("❌ 错误: 未找到 DEEPSEEK_API_KEY");
            System.exit(1);
        }

        // 共享 DeepSeekClient 和 MemoryManager
        com.claudecode.llm.DeepSeekClient sharedClient = new com.claudecode.llm.DeepSeekClient(apiKey);
        memoryManager = new MemoryManager(sharedClient);
        System.out.println("📚 已加载 " + memoryManager.longTermSize() + " 条长期记忆");
        String modelName = sharedClient.getModel();
        String osName = System.getProperty("os.name");
        String osHint = osLower.contains("win")
                ? "Windows。Shell命令必须使用cmd格式(dir/del/rmdir/mkdir/type, 不要用ls/rm/cat。路径分隔符用\\)。"
                : "Linux/macOS。Shell命令使用bash格式。";
        String sysPrompt = PromptAssembler.load("modes/agent.md", Map.of(
                "model", modelName, "os", osName, "os_hint", osHint));
        memoryManager.setSystemMessage(sysPrompt);
        Agent agent = new Agent(sharedClient, memoryManager);
        PlanAndExecuteAgent planAgent = new PlanAndExecuteAgent(sharedClient, memoryManager);
        orchestrator = new AgentOrchestrator(sharedClient);

        // 交互式循环
        Scanner scanner = new Scanner(System.in);
        System.out.println("💡 提示: 输入 '/help' 查看所有命令");
        System.out.println("🧠 思考模式: 关闭 (输入 /thinking 开启)");
        System.out.println("📍 运行模式: " + modeLabel(currentMode)
                + " (输入 /plan 切换至 P&E，/team 切换至多 Agent)\n");

        while (true) {
            String modeLabel = switch (currentMode) {
                case PLAN_EXECUTE -> "[Plan] ";
                case TEAM -> "[Team] ";
                default -> "";
            };
            String input;
            do {
                System.out.print(modeLabel + "👤 你: ");
                input = scanner.nextLine().trim();
            } while (input.isEmpty());

            // 命令处理
            if (input.startsWith("/")) {
                handleCommand(input, agent, planAgent);
                continue;
            }

            // 退出命令（兼容旧格式）
            if (input.equalsIgnoreCase("exit") || input.equalsIgnoreCase("quit")) break;

            // Team 模式
            if (currentMode == Mode.TEAM) {
                handleTeamMode(input);
                continue;
            }

            // 智能模式选择：简单任务走 ReAct，复杂任务自动切 Plan-and-Execute
            boolean usePlan = currentMode == Mode.PLAN_EXECUTE
                    || (currentMode == Mode.REACT && shouldPlan(input));

            if (usePlan) {
                if (currentMode == Mode.REACT) {
                    System.out.println("🧠 检测到复杂任务，自动切换 Plan-and-Execute 模式");
                }
                handlePlanExecute(planAgent, scanner, input);
            } else {
                if (agent.isThinkingEnabled()) {
                    System.out.println("🔄 正在思考...");
                } else {
                    System.out.println("⚡️ 快速响应 (输入 /thinking 开启思考模式)");
                }
                System.out.println("🤖 Agent:");
                agent.runStream(input);
                System.out.println();
            }
        }

        System.out.println("👋 再见!");
        scanner.close();
    }

    /**
     * 打印启动 Banner（仿 Claude Code 风格）
     */
    private static void printBanner() {
        System.out.println("""
╭─── Mini Claude Code ────────────────────────────────────────────────────────────────────────────────────╮
│                                          │ Tips for getting started                                     │
│                    Welcome!              │ Run commands to interact with the AI assistant               │
│                                          │ ─────────────────────────────────────────────────────────    │
│           ▐▛███▜▌                        │ Available commands                                           │
│          ▝▜█████▛▘                       │ - clear: Clear conversation history                          │
│            ▘▘ ▝▝                         │ - tokens: Show token usage statistics                        │
│                                          │ - info: Show system information                              │
│                                          │ - exit/quit: Exit the program                                │
│                                          │                                                              │
│   DeepSeek · Ready to Chat               │ powered by DeepSeek https://www.deepseek.cn/                 │
│                                          │ designed by Wang                                             │
╰─────────────────────────────────────────────────────────────────────────────────────────────────────────╯
""");
    }

    /**
     * 加载 API Key
     * 优先从 .env 文件读取，其次从环境变量读取
     */
    private static String loadApiKey() {
        // 先尝试从当前目录读取 .env
        File envFile = new File(ENV_FILE);
        if (envFile.exists()) {
            String key = readApiKeyFromFile(envFile);
            if (key != null && !key.isEmpty()) {
                return key;
            }
        }

        // 再尝试从环境变量读取
        return System.getenv(API_KEY_ENV);
    }

    /**
     * 处理命令
     */
    private static void handleCommand(String command, Agent agent, PlanAndExecuteAgent planAgent) {
        switch (command.toLowerCase()) {
            case "/exit":
            case "/quit":
                System.exit(0);
                break;
            case "/clear":
                agent.clearHistory();
                System.out.println("🗑️ 历史已清空\n");
                break;
            case "/tokens":
                System.out.println(agent.getTokenStats() + "\n");
                break;
            case "/reset":
                agent.resetTokenStats();
                System.out.println("🔄 统计已重置\n");
                break;
            case "/info":
                System.out.println(agent.getSystemInfo() + "\n");
                break;
            case "/memory":
                System.out.println("📚 记忆系统状态\n");
                System.out.println(memoryManager.getSystemStatus());
                System.out.println();
                break;
            case "/save":
                memoryManager.extractAndSaveFacts();
                System.out.println("💾 事实已保存到长期记忆\n");
                break;
            case "/thinking":
                boolean thinkingStatus = agent.toggleThinking();
                planAgent.toggleThinking();
                System.out.println("🧠 思考模式: " + (thinkingStatus ? "开启" : "关闭") + "\n");
                break;
            case "/effort":
                String currentEffort = agent.getReasoningEffort();
                System.out.println("⚡ 当前思考强度: " + currentEffort + "\n");
                break;
            case "/effort high":
                agent.setReasoningEffort("high");
                System.out.println("⚡ 思考强度已设置为: high\n");
                break;
            case "/effort max":
                agent.setReasoningEffort("max");
                System.out.println("⚡ 思考强度已设置为: max\n");
                break;
            case "/plan":
                currentMode = currentMode == Mode.PLAN_EXECUTE ? Mode.REACT : Mode.PLAN_EXECUTE;
                System.out.println("📋 模式切换: " + modeLabel(currentMode) + "\n");
                break;
            case "/team":
                currentMode = currentMode == Mode.TEAM ? Mode.REACT : Mode.TEAM;
                System.out.println("👥 模式切换: " + modeLabel(currentMode) + "\n");
                break;
            case "/hplan":
                boolean newVal = !planAgent.isHierarchicalPlanning();
                planAgent.setHierarchicalPlanning(newVal);
                System.out.println("📚 分层规划: " + (newVal ? "开启" : "关闭") + "\n");
                break;
            case "/help":
                showHelp();
                break;
            default:
                if (command.startsWith("/save ")) {
                    String fact = command.substring(6).trim();
                    memoryManager.storeFact(fact);
                    System.out.println("💾 已保存到长期记忆: " + fact + "\n");
                    break;
                }
                if (command.startsWith("/index ")) {
                    String path = command.substring(7).trim();
                    System.out.println("🔍 开始索引代码库: " + path + "\n");
                    String result = new CodeIndex().index(path);
                    System.out.println(result + "\n");
                    break;
                }
                if (command.startsWith("/search ")) {
                    String query = command.substring(8).trim();
                    System.out.println("🔎 搜索结果: " + query + "\n");
                    try {
                        var results = new CodeRetriever(System.getProperty("user.dir")).hybridSearch(query, 5);
                        if (results.isEmpty()) {
                            System.out.println("  无匹配结果\n");
                        } else {
                            for (var r : results) {
                                System.out.println("  [" + r.chunkType() + "] " + r.name());
                                System.out.println("  📄 " + r.filePath());
                                System.out.println("  📊 相似度: " + String.format("%.2f", r.similarity()));
                                String preview = r.content().length() > 200
                                        ? r.content().substring(0, 200) + "..."
                                        : r.content();
                                System.out.println("  " + preview.replace("\n", "\n  ") + "\n");
                            }
                        }
                    } catch (Exception e) {
                        System.out.println("  ❌ 检索失败: " + e.getMessage() + "\n");
                    }
                    break;
                }
                System.out.println("❓ 未知命令: " + command + "，输入 /help 查看所有命令\n");
        }
    }

    /**
     * 启发式判断：是否需要 Plan-and-Execute 模式。
     * 简单任务走 ReAct（快），复杂任务自动切换 Plan（稳）。
     */
    /**
     * Plan-and-Execute 流程：规划 → 确认 → 执行 → 总结。
     */
    private static void handlePlanExecute(PlanAndExecuteAgent planAgent, Scanner scanner, String input) {
        try {
            System.out.println("🤖 Agent:");
            com.claudecode.plan.ExecutionPlan plan = planAgent.plan(input);

            // 确认计划（使用共享 Scanner，避免和 PlanReviewHandler 的 Scanner 冲突）
            while (true) {
                System.out.print("📝 确认计划 [回车执行 / 输入补充说明 / 'n'取消]: ");
                String feedback = scanner.nextLine().trim();
                if (feedback.isEmpty()) break;
                if ("n".equalsIgnoreCase(feedback) || "no".equalsIgnoreCase(feedback)) {
                    System.out.println("🚫 计划已取消\n");
                    return;
                }
                System.out.println("🔄 根据反馈重新规划...\n");
                plan = planAgent.replan(plan, feedback);
                System.out.println(plan.visualize());
                System.out.println();
            }

            planAgent.execute(plan);
            String summary = planAgent.summarize(plan);
            System.out.println("📊 总结:\n" + summary + "\n");
            planAgent.writeBackToContext(plan);  // 回写共享上下文
        } catch (Exception e) {
            System.out.println("❌ 执行失败: " + e.getMessage() + "\n");
        }
    }

    private static String modeLabel(Mode mode) {
        return switch (mode) {
            case REACT -> "ReAct（即时推理）";
            case PLAN_EXECUTE -> "Plan-and-Execute（规划后执行）";
            case TEAM -> "Team（多 Agent 协作）";
        };
    }

    /** 多 Agent 协作模式：Planner → Workers → Reviewer */
    private static void handleTeamMode(String input) {
        try {
            String result = orchestrator.run(input);
            System.out.println("\n" + result + "\n");
        } catch (Exception e) {
            System.out.println("❌ 多 Agent 执行失败: " + e.getMessage() + "\n");
        }
    }

    private static boolean shouldPlan(String input) {
        if (input.length() > 50) return true;

        String[] keywords = {"创建", "写", "读", "执行", "然后", "接着", "编译", "运行",
                "项目", "文件夹", "构建", "部署", "分析", "重构", "修改后", "多个"};
        int actionCount = 0;
        for (String kw : keywords) {
            if (input.contains(kw)) actionCount++;
        }
        return actionCount >= 3;
    }

    private static void showHelp() {
        System.out.println("""
📋 可用命令:

基础操作:
  /help          显示此帮助信息
  /exit /quit    退出程序
  /clear         清空对话历史

运行模式:
  /plan          切换 ReAct / Plan-and-Execute 模式
  /team          切换 Team（多 Agent 协作）模式
  /hplan         切换分层规划（先定阶段 → 再细化任务）

模型控制:
  /thinking      切换思考模式（开启/关闭）
  /effort        查看当前思考强度
  /effort high   设置思考强度为高
  /effort max    设置思考强度为最大

记忆系统:
  /memory        查看记忆状态
  /save          保存关键事实到长期记忆

统计信息:
  /tokens        查看 Token 使用统计
  /reset         重置 Token 统计数据
  /info          显示系统信息
""" + "\n");
    }

    /**
     * 从 .env 文件读取 API Key
     */
    private static String readApiKeyFromFile(File envFile) {
        try (Scanner scanner = new Scanner(envFile)) {
            while (scanner.hasNextLine()) {
                String line = scanner.nextLine().trim();
                // 查找 API_KEY 配置行
                if (line.startsWith(API_KEY_ENV + "=")) {
                    return line.substring(API_KEY_ENV.length() + 1).trim();
                }
            }
        } catch (FileNotFoundException e) {
            // 文件不存在已在上层处理
        }
        return null;
    }
}