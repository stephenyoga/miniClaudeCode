package com.claudecode.config;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * 环境配置读取器 —— 读取 .env 文件中的配置项。
 *
 * .env 文件是项目根目录下的 Key=Value 文本文件（已加入 .gitignore）。
 * 用于配置 DEEPSEEK_API_KEY、DEEPSEEK_MODEL 等环境变量。
 *
 * 设计为静态类，在 Main 启动时 init()，后续全局通过 get(key) 读取。
 */
public class EnvConfig {

    private static final Map<String, String> envVariables = new HashMap<>();
    private static boolean initialized = false;

    /**
     * 初始化配置，从指定路径读取 .env 文件。
     * 只执行一次（initialized 标志控制）。
     *
     * 格式说明：
     *   # 注释行
     *   KEY=VALUE
     *   KEY="VALUE"    ← 引号会被保留，不是标准 .env 解析，但值里带空格时需要用
     *
     * 读取后会同时设置到 System.setProperty()，方便其他类通过系统属性读取。
     */
    public static synchronized void init(String envFilePath) {
        if (initialized) return;

        File file = new File(envFilePath);
        if (!file.exists()) {
            System.err.println("警告: .env 文件不存在: " + envFilePath);
            initialized = true;
            return;
        }

        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;

                // 按第一个 = 分割 key 和 value
                int equalsIndex = line.indexOf('=');
                if (equalsIndex > 0) {
                    String key = line.substring(0, equalsIndex).trim();
                    String value = line.substring(equalsIndex + 1).trim();
                    envVariables.put(key, value);
                    System.setProperty(key, value);
                }
            }
            System.out.println("✅ API Key 加载成功");
        } catch (IOException e) {
            System.err.println("警告: 读取 .env 文件失败: " + e.getMessage());
        }

        initialized = true;
    }

    /** 使用默认路径（项目根目录下的 .env）初始化 */
    public static void init() {
        init(".env");
    }

    /** 获取配置值，不存在返回 null */
    public static String get(String key) {
        if (!initialized) init();
        return envVariables.get(key);
    }

    /** 获取配置值，不存在返回 defaultValue */
    public static String get(String key, String defaultValue) {
        String value = get(key);
        return value != null && !value.isEmpty() ? value : defaultValue;
    }

    /** 检查是否包含指定配置 */
    public static boolean contains(String key) {
        if (!initialized) init();
        return envVariables.containsKey(key);
    }

    /** 清除配置（测试用） */
    public static void clear() {
        envVariables.clear();
        initialized = false;
    }
}
