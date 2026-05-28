
package com.claudecode.cli;

import com.claudecode.agent.Agent;
import com.claudecode.config.EnvConfig;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.Scanner;

/**
 * 交互式命令行界面
 * 提供用户与Agent交互的入口
 */
public class Main {

    private static final String API_KEY_ENV = "DEEPSEEK_API_KEY";
    private static final String ENV_FILE = ".env";

    public static void main(String[] args) {
        printBanner();

        // 初始化环境配置（读取 .env 文件）
        EnvConfig.init();

        // 加载 API Key
        String apiKey = loadApiKey();
        if (apiKey == null || apiKey.isEmpty()) {
            System.err.println("❌ 错误: 未找到 DEEPSEEK_API_KEY");
            System.exit(1);
        }

        // 创建 Agent
        Agent agent = new Agent(apiKey);

        // 交互式循环
        Scanner scanner = new Scanner(System.in);
        System.out.println("💡 提示: 输入 '/help' 查看所有命令\n");

        while (true) {
            System.out.print("👤 你: ");
            String input = scanner.nextLine().trim();

            if (input.isEmpty()) continue;

            // 命令处理
            if (input.startsWith("/")) {
                handleCommand(input, agent);
                continue;
            }

            // 退出命令（兼容旧格式）
            if (input.equalsIgnoreCase("exit") || input.equalsIgnoreCase("quit")) break;

            // 根据思考模式显示不同的等待提示
            if (agent.isThinkingEnabled()) {
                System.out.println("🔄 正在思考...");
            } else {
                System.out.println("⚡️ 快速响应");
            }
            System.out.println("🤖 Agent:");

            // 流式运行 Agent
            agent.runStream(input);
            System.out.println();
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
    private static void handleCommand(String command, Agent agent) {
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
            case "/thinking":
                boolean thinkingStatus = agent.toggleThinking();
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
            case "/help":
                showHelp();
                break;
            default:
                System.out.println("❓ 未知命令: " + command + "，输入 /help 查看所有命令\n");
        }
    }

    /**
     * 显示帮助信息
     */
    private static void showHelp() {
        System.out.println("""
📋 可用命令:

基础操作:
  /help          显示此帮助信息
  /exit /quit    退出程序
  /clear         清空对话历史

模型控制:
  /thinking      切换思考模式（开启/关闭）
  /effort        查看当前思考强度
  /effort high   设置思考强度为高
  /effort max    设置思考强度为最大

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