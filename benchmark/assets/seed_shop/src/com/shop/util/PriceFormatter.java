package com.shop.util;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/** 金额/日期展示工具 */
public final class PriceFormatter {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private PriceFormatter() {}

    /** 分 -> "¥xx.xx" */
    public static String formatCents(long cents) {
        return String.format("¥%.2f", cents / 100.0);
    }

    public static String formatDate(long epochMillis) {
        return Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).format(DATE);
    }
}
