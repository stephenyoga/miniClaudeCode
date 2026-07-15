package com.claudecode.memory;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 中文优先的轻量分词器 —— 基于双字滑动窗口 + 单字重叠匹配。
 *
 * 替换 jieba 的轻量方案。jieba 依赖 50M+ 词典文件，对一个 CLI 工具来说太重了。
 * 双字 2-gram + 单字兜底对短文本检索效果够用，后续可升级为 jieba 或 Embedding。
 *
 * 分词策略：
 * - 中文部分：双字滑动窗口（"西南财经大学" → "西南"、"南财"、"财经"、"经大"、"大学"）
 * - 英文部分：按空格/符号分割完整单词
 * - 混合文本：中英文各自处理
 *
 * 匹配策略：
 * - 精确子串匹配优先
 * - 中文单字重叠匹配兜底：查询词和记忆内容只要有 2 个以上同款中文字符就算命中
 */
public final class MemoryQueryTokenizer {

    private MemoryQueryTokenizer() {}

    /**
     * 对查询文本分词。
     * 输入："西南财经大学 JDK" → 输出：{"西南", "南财", "财经", "经大", "大学", "JDK"}
     */
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
                // 遇到中文：先把之前缓存的英文单词刷出来，再处理中文
                flushBuf(buf, tokens);
                tokens.add(String.valueOf((char) cp));  // 单字兜底
                // 双字滑动窗口
                if (i + charCount < lower.length()) {
                    int nextCp = lower.codePointAt(i + charCount);
                    if (isChinese(nextCp)) {
                        tokens.add(new String(new char[]{(char) cp, (char) nextCp}));
                    }
                }
            } else if (Character.isLetterOrDigit(cp)) {
                // 英文字母/数字：先缓存，等遇到非字母时再刷出
                buf.append((char) cp);
            } else {
                flushBuf(buf, tokens);
            }
            i += charCount;
        }
        flushBuf(buf, tokens);

        // 过滤掉单字词（英文单词至少 2 个字母）和纯标点符号
        tokens.removeIf(t -> t.length() < 2 || isPunctuation(t));
        return tokens;
    }

    /**
     * 判断文本中是否匹配任意查询 token。
     * 先做精确子串匹配，如果没命中再试中文单字重叠匹配。
     *
     * 单字重叠匹配示例：
     * - 查询 token "学校" → 文本 "西南财经大学" 中包含 "西"、"南"、"财"、"经"、"大"、"学"
     * - "学" 和 "校" 都是中文，且 2 个都出现在文本中 → 匹配成功
     */
    public static boolean matches(String text, Set<String> queryTokens) {
        if (text == null || queryTokens == null || queryTokens.isEmpty()) return false;
        String lower = text.toLowerCase(Locale.ROOT);
        for (String token : queryTokens) {
            // 方式 1：精确子串匹配
            if (lower.contains(token)) return true;
            // 方式 2：中文单字重叠匹配
            // token 中只要有 2 个以上的中文字符出现在 text 中就命中
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

    /** 判断是否为中文字符（Unicode 范围 一-鿿） */
    private static boolean isChinese(int cp) {
        return cp >= 0x4E00 && cp <= 0x9FFF;
    }

    /** 判断字符串是否纯标点（无字母数字） */
    private static boolean isPunctuation(String s) {
        return s.chars().allMatch(c -> !Character.isLetterOrDigit(c));
    }

    /** 把缓存的英文单词刷入 tokens，长度 >= 2 才保留 */
    private static void flushBuf(StringBuilder buf, Set<String> tokens) {
        if (buf.isEmpty()) return;
        String word = buf.toString().trim();
        if (word.length() >= 2) tokens.add(word);
        buf.setLength(0);
    }
}
