package com.claudecode.config;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 提示词装配器 —— 从 resources/prompts/ 加载 .md 文件作为 system prompt。
 * 支持运行时变量替换 {{key}}。
 */
public class PromptAssembler {

    private static final String PROMPTS_BASE = "prompts/";

    /**
     * 加载提示词文件，支持变量替换
     * @param path 相对于 resources/prompts/ 的路径，如 "modes/agent.md"
     * @param vars 替换模板中的 {{key}}
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

    /** 加载提示词文件，不替换变量 */
    public static String load(String path) {
        return load(path, Map.of());
    }

    private static String loadRaw(String path) {
        String fullPath = PROMPTS_BASE + path;
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
