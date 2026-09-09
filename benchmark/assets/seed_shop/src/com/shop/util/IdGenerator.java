package com.shop.util;

/** 简易 ID 生成器 */
public final class IdGenerator {

    private static long seq = 0;

    private IdGenerator() {}

    public static synchronized String next(String prefix) {
        seq++;
        return prefix + "-" + seq + "-" + (System.currentTimeMillis() % 100000);
    }
}
