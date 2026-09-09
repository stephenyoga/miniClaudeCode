package com.shop.util;

import java.util.List;

/** 极简 CSV 序列化：自动对含逗号/引号/换行的字段加引号转义 */
public final class CsvExporter {

    private CsvExporter() {}

    /** rows[0] 视为表头。返回带 \r\n 换行的完整 CSV 文本 */
    public static String toCsv(List<String[]> rows) {
        StringBuilder sb = new StringBuilder();
        for (String[] row : rows) {
            for (int i = 0; i < row.length; i++) {
                if (i > 0) sb.append(',');
                sb.append(escape(row[i]));
            }
            sb.append("\r\n");
        }
        return sb.toString();
    }

    private static String escape(String field) {
        String s = field == null ? "" : field;
        boolean needQuote = s.indexOf(',') >= 0 || s.indexOf('"') >= 0 || s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0;
        if (!needQuote) return s;
        return '"' + s.replace("\"", "\"\"") + '"';
    }
}
