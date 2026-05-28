
package com.claudecode.config;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * 环境配置读取器
 * 用于读取 .env 文件中的环境变量配置
 */
public class EnvConfig {

    private static final Map<String, String> envVariables = new HashMap<>();
    private static boolean initialized = false;

    /**
     * 初始化配置，读取 .env 文件
     * @param envFilePath .env 文件路径
     */
    public static synchronized void init(String envFilePath) {
        if (initialized) {
            return;
        }

        File file = new File(envFilePath);
        if (!file.exists()) {
            System.err.println("警告: .env 文件不存在: " + envFilePath);
            initialized = true;
            return;
        }

        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                // 跳过注释行和空行
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }

                // 解析 KEY=VALUE 格式
                int equalsIndex = line.indexOf('=');
                if (equalsIndex > 0) {
                    String key = line.substring(0, equalsIndex).trim();
                    String value = line.substring(equalsIndex + 1).trim();
                    envVariables.put(key, value);
                    // 同时设置到系统属性
                    System.setProperty(key, value);
                }
            }
            System.out.println("✅ .env 文件加载成功");
        } catch (IOException e) {
            System.err.println("警告: 读取 .env 文件失败: " + e.getMessage());
        }

        initialized = true;
    }

    /**
     * 使用默认路径初始化（项目根目录下的 .env）
     */
    public static void init() {
        init(".env");
    }

    /**
     * 获取环境变量值
     * @param key 变量名
     * @return 变量值，如果不存在返回 null
     */
    public static String get(String key) {
        if (!initialized) {
            init();
        }
        return envVariables.get(key);
    }

    /**
     * 获取环境变量值，带默认值
     * @param key 变量名
     * @param defaultValue 默认值
     * @return 变量值，如果不存在返回默认值
     */
    public static String get(String key, String defaultValue) {
        String value = get(key);
        return value != null && !value.isEmpty() ? value : defaultValue;
    }

    /**
     * 检查是否包含指定变量
     * @param key 变量名
     * @return 是否包含
     */
    public static boolean contains(String key) {
        if (!initialized) {
            init();
        }
        return envVariables.containsKey(key);
    }

    /**
     * 清除所有配置（用于测试）
     */
    public static void clear() {
        envVariables.clear();
        initialized = false;
    }
}