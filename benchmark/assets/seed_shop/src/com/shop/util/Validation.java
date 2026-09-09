package com.shop.util;

/** 业务参数校验工具 */
public final class Validation {

    private Validation() {}

    public static void requirePositive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " 必须为正数，收到: " + value);
        }
    }

    public static void requireNotBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
    }
}
