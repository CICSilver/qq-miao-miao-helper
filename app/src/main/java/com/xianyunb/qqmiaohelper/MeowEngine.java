package com.xianyunb.qqmiaohelper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 喵化引擎：纯文本变换，无任何 Android 依赖，可单独测试。
 *
 * 相比原版 TextProcessor 修掉的问题：
 *   1. 保护区 —— URL / 邮箱 / @提及 / [表情] / 反引号代码 不再被破坏。
 *      原版把 '.' 和 '?' 也当成加喵的标点，导致每个链接都被拆坏
 *      （https://docs.qq.com → https://docs.喵qq.喵com）。
 *   2. 换行保留 —— 原版 stripEmoticons() 里的 "\\s+" -> " " 会把多行消息压成一行。
 *   3. 单趟替换 —— 自定义规则下，替换产物不会被后续规则再次替换。
 *   4. 颜文字稳定 —— 由内容决定而非每次重抽，打字过程中不再闪烁。
 *   5. 纯链接消息原样放行。
 */
public final class MeowEngine {

    // 占位符用私用区字符：不含标点故不参与断句，不含中文故不被规则命中
    private static final char MASK_OPEN = '\uE000';
    private static final char MASK_CLOSE = '\uE001';
    private static final Pattern MASK_RE = Pattern.compile("\uE000(\\d+)\uE001");
    private static final Pattern PUA_STRIP = Pattern.compile("[\uE000\uE001]");

    /** 句末终止符。刻意不含 '.' 和 '，'：前者会拆坏链接和小数，后者会把句子切太碎。 */
    private static final String TERMINATORS = "。！？!?…～~；;\n";

    private static boolean isTerminator(char c) {
        return TERMINATORS.indexOf(c) >= 0;
    }

    /** 保护区规则，顺序即优先级。代码块最先，URL 次之，这样 URL 里的斜杠不会被误认。 */
    private static final Pattern[] PROTECT = {
            Pattern.compile("```[\\s\\S]*?```|`[^`\n]+`"),
            Pattern.compile("\\b(?:https?://|www\\.)[^\\s\\u4e00-\\u9fff，。！？；：）】]+",
                    Pattern.CASE_INSENSITIVE),
            // 邮箱：原版会把 abc.def@qq.com 拆成 abc.喵def@qq.喵com
            Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"),
            Pattern.compile("@[^\\s@，。！？,.!?]{1,32}"),
            Pattern.compile("\\[[^\\[\\]\n]{1,12}\\]"),
    };

    private MeowEngine() {
    }

    // ------------------------------------------------------------------ 配置

    public static final class Rule {
        public final String from;
        public final String to;

        public Rule(String from, String to) {
            this.from = from == null ? "" : from;
            this.to = to == null ? "" : to;
        }

        /** 替换结果包含自身原文（我们 -> 我们这群喵）。增量重写下会无限套娃。 */
        public boolean isSelfReferential() {
            return !from.isEmpty() && to.contains(from);
        }
    }

    public static final class Config {
        /** 句尾语气词 */
        public String suffix = "喵";
        /** true: 今天真好喵！  false: 今天真好！喵（原版行为） */
        public boolean suffixBeforePunct = true;
        public boolean enableSuffix = true;
        /** 一条消息最多加几个语气词，<= 0 表示不限制 */
        public int maxSuffixPerMessage = 0;
        /** 短于此长度的句子不加语气词，避免出现「嗯喵」 */
        public int minSentenceLength = 2;

        public List<Rule> rules = new ArrayList<>();

        /** 是否追加颜文字。实际的挑选在 KaomojiLib，由 MeowCommitter 调用。 */
        public boolean enableKaomoji = true;

        public boolean protectRegions = true;
    }

    // ------------------------------------------------------------------ 工具

    /** FNV-1a：把字符串稳定映射成 32 位种子，保证同样的文字做出同样的选择。 */
    public static int hashSeed(String s) {
        int h = (int) 2166136261L;
        for (int i = 0; i < s.length(); i++) {
            h ^= s.charAt(i);
            h *= 16777619;
        }
        return h;
    }

    // ------------------------------------------------------------ 保护区遮罩

    private static final class Masked {
        final String text;
        final List<String> vault;

        Masked(String text, List<String> vault) {
            this.text = text;
            this.vault = vault;
        }
    }

    private static Masked mask(String input, Config cfg) {
        List<String> vault = new ArrayList<>();
        // 先清掉用户输入里可能存在的私用区字符，避免与占位符冲突
        String text = PUA_STRIP.matcher(input).replaceAll("");
        if (!cfg.protectRegions) {
            return new Masked(text, vault);
        }
        for (Pattern p : PROTECT) {
            Matcher m = p.matcher(text);
            StringBuilder sb = new StringBuilder();
            int last = 0;
            while (m.find()) {
                sb.append(text, last, m.start());
                sb.append(MASK_OPEN).append(vault.size()).append(MASK_CLOSE);
                vault.add(m.group());
                last = m.end();
            }
            sb.append(text, last, text.length());
            text = sb.toString();
        }
        return new Masked(text, vault);
    }

    private static String unmask(String text, List<String> vault) {
        Matcher m = MASK_RE.matcher(text);
        StringBuilder sb = new StringBuilder();
        int last = 0;
        while (m.find()) {
            sb.append(text, last, m.start());
            int idx = Integer.parseInt(m.group(1));
            sb.append(idx >= 0 && idx < vault.size() ? vault.get(idx) : "");
            last = m.end();
        }
        sb.append(text, last, text.length());
        return sb.toString();
    }

    // -------------------------------------------------------------- 关键词替换

    private static final class Node {
        final Map<Character, Node> next = new HashMap<>();
        String to;
    }

    private static Node buildTrie(List<Rule> rules) {
        Node root = new Node();
        if (rules == null) {
            return root;
        }
        for (Rule r : rules) {
            if (r == null || r.from.isEmpty()) {
                continue;
            }
            Node node = root;
            for (int i = 0; i < r.from.length(); i++) {
                char ch = r.from.charAt(i);
                Node child = node.next.get(ch);
                if (child == null) {
                    child = new Node();
                    node.next.put(ch, child);
                }
                node = child;
            }
            node.to = r.to;
        }
        return root;
    }

    /**
     * 单趟、最长优先的替换。
     *
     * 输出缓冲区永不回扫，所以 {我 -> 本喵, 喵 -> 呜} 这种规则不会把刚产生的
     * 「本喵」又改成「本呜」—— 原版那种链式 replace() 一定会踩这个坑。
     * 顺带解决「我们」必须优先于「我」的问题。
     */
    static String applyReplacements(String text, Node trie) {
        StringBuilder out = new StringBuilder(text.length() + 16);
        int i = 0;
        while (i < text.length()) {
            // 占位符整体跳过，不参与匹配
            if (text.charAt(i) == MASK_OPEN) {
                int close = text.indexOf(MASK_CLOSE, i + 1);
                if (close >= 0) {
                    out.append(text, i, close + 1);
                    i = close + 1;
                    continue;
                }
            }
            Node node = trie;
            String bestTo = null;
            int bestLen = 0;
            int j = i;
            while (j < text.length()) {
                Node child = node.next.get(text.charAt(j));
                if (child == null) {
                    break;
                }
                node = child;
                j++;
                if (node.to != null) {
                    bestTo = node.to;
                    bestLen = j - i;
                }
            }
            if (bestTo != null && bestLen > 0) {
                out.append(bestTo);
                i += bestLen;
            } else {
                out.append(text.charAt(i));
                i++;
            }
        }
        return out.toString();
    }

    // ------------------------------------------------------------------ 断句

    private static final class Sentence {
        String body = "";
        String punct = "";
    }

    static List<Sentence> splitSentences(String text) {
        List<Sentence> out = new ArrayList<>();
        StringBuilder body = new StringBuilder();
        StringBuilder punct = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (isTerminator(ch)) {
                punct.append(ch);
                boolean lastChar = i + 1 >= text.length();
                boolean nextIsTerm = !lastChar && isTerminator(text.charAt(i + 1));
                if (lastChar || !nextIsTerm) {
                    flush(out, body, punct);
                }
            } else {
                if (punct.length() > 0) {
                    flush(out, body, punct);  // 标点后紧跟正文 -> 上一句已结束
                }
                body.append(ch);
            }
        }
        flush(out, body, punct);
        return out;
    }

    private static void flush(List<Sentence> out, StringBuilder body, StringBuilder punct) {
        if (body.length() == 0 && punct.length() == 0) {
            return;
        }
        Sentence s = new Sentence();
        s.body = body.toString();
        s.punct = punct.toString();
        out.add(s);
        body.setLength(0);
        punct.setLength(0);
    }

    // ------------------------------------------------------------------ 主入口

    /** 句子是否适合加语气词。 */
    private static boolean canSuffix(String body, Config cfg) {
        String t = body.trim();
        if (t.length() < cfg.minSentenceLength) {
            return false;
        }
        if (!cfg.suffix.isEmpty() && t.endsWith(cfg.suffix)) {
            return false;   // 已经有喵了
        }
        return t.isEmpty() || t.charAt(t.length() - 1) != MASK_CLOSE;  // 末尾是链接/@/表情
    }

    /** 剥掉末尾那个由我们加上的颜文字（只认池子里的，不动用户自己打的）。 */
    public static String stripTrailingKaomoji(String text, List<String> pool) {
        if (pool == null) {
            return text;
        }
        String trimmed = trimEnd(text);
        for (String k : pool) {
            if (k != null && !k.isEmpty() && trimmed.endsWith(k)) {
                return trimEnd(trimmed.substring(0, trimmed.length() - k.length()));
            }
        }
        return text;
    }

    private static String trimEnd(String s) {
        int end = s.length();
        while (end > 0 && Character.isWhitespace(s.charAt(end - 1))) {
            end--;
        }
        return s.substring(0, end);
    }

    /** 去掉占位符后是否还剩「正文」（字母或数字）。纯链接 / 纯表情消息不加工。 */
    private static boolean hasProse(String masked) {
        String bare = MASK_RE.matcher(masked).replaceAll("");
        for (int i = 0; i < bare.length(); i++) {
            if (Character.isLetterOrDigit(bare.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 变换一段文本。
     *
     * @param input 用户原文（不是本方法上一次的输出 —— 增量重写请见 MeowRewriter）
     */
    public static String transform(String input, Config cfg) {
        if (input == null || input.trim().isEmpty()) {
            return input;
        }

        Masked m = mask(input, cfg);
        if (!hasProse(m.text)) {
            return input;   // 纯链接 / 纯表情码 / 纯标点，原样放行
        }

        // 1) 关键词替换
        String text = applyReplacements(m.text, buildTrie(cfg.rules));

        // 2) 句尾语气词
        if (cfg.enableSuffix && !cfg.suffix.isEmpty()) {
            StringBuilder sb = new StringBuilder(text.length() + 16);
            int added = 0;
            for (Sentence s : splitSentences(text)) {
                boolean capped = cfg.maxSuffixPerMessage > 0 && added >= cfg.maxSuffixPerMessage;
                if (capped || !canSuffix(s.body, cfg)) {
                    sb.append(s.body).append(s.punct);
                    continue;
                }
                added++;
                if (cfg.suffixBeforePunct) {
                    // 「今天真好喵！」：语气词插在正文与标点之间，尾随空格不被挤掉
                    String core = trimEnd(s.body);
                    sb.append(core).append(cfg.suffix)
                      .append(s.body, core.length(), s.body.length())
                      .append(s.punct);
                } else {
                    // 原版行为：「今天真好！喵」
                    sb.append(s.body).append(s.punct).append(cfg.suffix);
                }
            }
            text = sb.toString();
        }

        // 颜文字不再由引擎追加 —— 它需要「句尾标点」和「未替换的原文」两个
        // 上下文来选组，而这两样只有 MeowCommitter 在封句时才同时握有。
        // 见 KaomojiLib.select / assemble。

        return unmask(text, m.vault);
    }

    private static boolean endsWithWhitespace(String s) {
        return !s.isEmpty() && Character.isWhitespace(s.charAt(s.length() - 1));
    }

    // -------------------------------------------------------------- 规则解析

    /**
     * 解析自定义替换规则。每行一条，格式 {@code 原文=替换}。
     * 空行与以 # 开头的行忽略。为保证最长优先，按原文长度倒序排列。
     */
    public static List<Rule> parseRules(String raw) {
        List<Rule> rules = new ArrayList<>();
        if (raw == null || raw.trim().isEmpty()) {
            return rules;
        }
        for (String line : raw.split("\n")) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#")) {
                continue;
            }
            int eq = t.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String from = t.substring(0, eq).trim();
            String to = t.substring(eq + 1).trim();
            if (!from.isEmpty()) {
                rules.add(new Rule(from, to));
            }
        }
        // 长的排前面：Trie 本身已是最长优先，这里排序只为让规则列表更直观
        // 同理：List.sort 是 API 24+，用 Collections.sort（API 1）代替
        Collections.sort(rules, (a, b) -> Integer.compare(b.from.length(), a.from.length()));
        return rules;
    }

}
