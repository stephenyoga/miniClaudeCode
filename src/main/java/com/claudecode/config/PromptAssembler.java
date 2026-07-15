package com.claudecode.config;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 提示词装配器 —— 从 resources/prompts/ 加载 .md 文件作为 system prompt。
 *
 * 所有 system prompt 集中放在 src/main/resources/prompts/ 目录下，
 * 按模式分类（modes/agent.md, modes/planner.md 等）。
 * 支持 {{variable}} 变量替换，在加载时动态注入模型名、操作系统等信息。
 *
 * 使用方式：
 *   PromptAssembler.load("modes/agent.md", Map.of("model", "deepseek-v4-pro"))
 *
 * 相比将提示词硬编码在 Java 中的好处：
 * - 改提示词不用重新编译
 * - 提示词可读性更好（纯文本 Markdown）
 * - 支持按模式组织文件
 */
public class PromptAssembler {

    /** resources/prompts/ 目录在 classpath 中的路径前缀 */
    private static final String PROMPTS_BASE = "prompts/";

    /**
     * 加载提示词文件并替换变量。
     *
     * @param path 相对于 resources/prompts/ 的路径，如 "modes/agent.md"
     * @param vars 变量替换表，文件中的 {{key}} 会被替换为对应的 value
     * @return 替换后的提示词文本，文件不存在返回空字符串
     */
    public static String load(String path, Map<String, String> vars) {
        String template = loadRaw(path);
        if (vars == null || vars.isEmpty()) return template;
        String result = template;
        for (var e : vars.entrySet()) {
            result = result.replace("{{" + e.getKey() + "}}", e.getValue() != null ? e.getValue() : "");
        }
        return result;
    }

    /** 加载提示词文件，不做变量替换 */
    public static String load(String path) {
        return load(path, Map.of());
    }

    /**
     * 从 classpath 读取文件内容。
     * 使用 ClassLoader.getResourceAsStream() 而非文件系统路径，
     * 确保打包为 JAR 后仍然能读取到 resources 内的文件。
     */
    private static String loadRaw(String path) {
        String fullPath = PROMPTS_BASE + path;
        // 从 classpath 加载（JAR 内或 target/classes/ 下）
        InputStream is = PromptAssembler.class.getClassLoader().getResourceAsStream(fullPath);
        if (is == null) {
            System.err.println("⚠️ 提示词文件未找到: " + fullPath);
            return "";
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            return reader.lines().collect(Collectors.joining("\n"));
        } catch (IOException e) {
            System.err.println("⚠️ 读取提示词失败: " + fullPath + " - " + e.getMessage());
            return "";
        }
    }
}
