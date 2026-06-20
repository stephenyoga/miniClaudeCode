package com.claudecode.memory;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 中文优先的轻量分词器——基于双字滑动窗口。
 * 替换 jieba 的轻量方案，后续可升级为 jieba-analysis 或 Embedding。
 */
public final class MemoryQueryTokenizer {

    private MemoryQueryTokenizer() {}

    /** 对查询文本分词 */
    public static Set<String> tokenize(String query) {
        if (query == null || query.isBlank()) return Set.of();
        String lower = query.toLowerCase(Locale.ROOT).trim();
        LinkedHashSet<String> tokens = new LinkedHashSet<>();

        int i = 0;
        StringBuilder buf = new StringBuilder();
        while (i < lower.length()) {
            int cp = lower.codePointAt(i);
            int charCount = Character.charCount(cp);

            if (isChinese(cp)) {
                // 遇中文刷出缓存的英文
                flushBuf(buf, tokens);
                tokens.add(String.valueOf((char) cp));
                // 双字滑动窗口
                if (i + charCount < lower.length()) {
                    int nextCp = lower.codePointAt(i + charCount);
                    if (isChinese(nextCp)) {
                        tokens.add(new String(new char[]{(char) cp, (char) nextCp}));
                    }
                }
            } else if (Character.isLetterOrDigit(cp)) {
                // 英文字母/数字拼接
                buf.append((char) cp);
            } else {
                flushBuf(buf, tokens);
            }
            i += charCount;
        }
        flushBuf(buf, tokens);

        // 过滤单字和纯标点
        tokens.removeIf(t -> t.length() < 2 || isPunctuation(t));
        return tokens;
    }

    /** 判断文本中是否匹配任意查询 token（支持模糊中文字符重叠） */
    public static boolean matches(String text, Set<String> queryTokens) {
        if (text == null || queryTokens == null || queryTokens.isEmpty()) return false;
        String lower = text.toLowerCase(Locale.ROOT);
        for (String token : queryTokens) {
            // 精确子串匹配
            if (lower.contains(token)) return true;
            // 中文单字重叠匹配：token 的每个中文字符只要有 2 个以上出现在文本中就算命中
            int matched = 0;
            int total = 0;
            for (char c : token.toCharArray()) {
                if (c >= 0x4E00 && c <= 0x9FFF) {
                    total++;
                    if (lower.indexOf(c) >= 0) matched++;
                }
            }
            if (total >= 2 && matched >= 2) return true;
        }
        return false;
    }

    private static boolean isChinese(int cp) {
        return cp >= 0x4E00 && cp <= 0x9FFF;
    }

    private static boolean isPunctuation(String s) {
        return s.chars().allMatch(c -> !Character.isLetterOrDigit(c));
    }

    private static void flushBuf(StringBuilder buf, Set<String> tokens) {
        if (buf.isEmpty()) return;
        String word = buf.toString().trim();
        if (word.length() >= 2) tokens.add(word);
        buf.setLength(0);
    }
}
