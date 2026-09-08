package com.demo;

import java.util.HashMap;
import java.util.Map;

/** 用户存储：内存注册表，保存已注册账号与密码摘要 */
public class UserRepository {

    private final Map<String, User> users = new HashMap<>();

    public UserRepository() {
        // 预置一个测试账号（密码明文 admin123，这里仅为演示）
        users.put("admin", new User("admin", hash("admin123"), "ADMIN"));
        users.put("guest", new User("guest", hash("guest123"), "GUEST"));
    }

    /** 按用户名查询用户，不存在返回 null */
    public User findByUsername(String username) {
        return users.get(username);
    }

    /** 简单的字符串哈希（演示用，非安全实现） */
    public static String hash(String raw) {
        return Integer.toHexString(raw.hashCode());
    }
}
