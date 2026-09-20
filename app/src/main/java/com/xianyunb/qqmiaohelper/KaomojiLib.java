package com.xianyunb.qqmiaohelper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 颜文字库与选择逻辑。纯逻辑，无 Android 依赖，可单独测试。
 *
 * 数据来自两份纯文本（内置在 res/raw，用户可在界面里覆盖）：
 *
 *   颜文字库    [情绪标签,... | 符号标签,...]   段落头
 *               (=・ω・=)                      一行一张脸
 *               (=^ω^=) | (=^w^=)             竖线后是可选的纯 ASCII 降级版
 *               @merge 情绪 + 符号 = 情绪       情绪合成表，同一份文件末尾
 *   关键词表    关键词 = 情绪标签               （右边留空 = 排除短语）
 *
 * 每张脸挂【两组】标签，各管一件事：
 *   情绪标签   表达什么心情 —— 由句子里的关键词决定
 *   符号标签   适合什么句尾 —— 由句尾标点决定
 * 两组分开是覆盖率的关键：合在一起的话「疑问」既是情绪又是句尾，
 * 交集几乎必然为空，句尾语气就永远被降级丢掉。
 *
 * 颜文字库整个文件不靠 '=' 分割 —— 猫脸的眼睛就是 '='，迟早会切错。
 * 段落头用方括号、降级版用竖线，这两个字符在全部一百多张脸里都没出现过。
 *
 * 选择流程：
 *   1. 扫原文找情绪标签（左到右最长优先，取最后一个命中）
 *   2. 句尾标点对应一个符号标签
 *   3. 候选 = 情绪标签命中的 ∩ 符号标签命中的
 *      交集为空 → 查情绪合成表 → 只看情绪 → 只看符号 → 全库
 *   4. 从候选里排除上次用过的那个，再按内容哈希定选
 */
public final class KaomojiLib {

    /** 一条颜文字：本体、可选的 ASCII 降级版、情绪标签、符号标签 */
    public static final class Entry {
        public final String kao;
        /** 纯 ASCII 的替身，字体缺字时用；没配就等于 kao 本身 */
        public final String ascii;
        /** 情绪标签：这张脸表达什么心情 */
        public final List<String> tags;
        /** 符号标签：这张脸适合什么句尾 */
        public final List<String> symbols;

        Entry(String kao, String ascii, List<String> tags, List<String> symbols) {
            this.kao = kao;
            this.ascii = ascii;
            this.tags = tags;
            this.symbols = symbols;
        }

        boolean hasAny(List<String> want) {
            for (String t : want) {
                if (tags.contains(t)) {
                    return true;
                }
            }
            return false;
        }

        boolean fits(String symbol) {
            return symbols.contains(symbol);
        }
    }

    /** 一条关键词规则。tags 为空表示「排除短语」：命中即弃权。 */
    public static final class KeywordRule {
        public final String key;
        public final List<String> tags;

        KeywordRule(String key, List<String> tags) {
            this.key = key;
            this.tags = tags;
        }

        public boolean isExclusion() {
            return tags.isEmpty();
        }
    }

    /**
     * 一条情绪合成规则：情绪标签 + 符号标签 = 结果情绪标签。
     *
     * 存在的理由是「什么意思！」这种句子 —— 关键词说它困惑，而困惑组的脸
     * 只配了句号/问号/问叹/省略号，没有一张挂叹号，交集是空的。它真正的
     * 语气是难以置信，也就是惊讶。这种语义叠加没法从标签本身推出来，只能列表。
     */
    public static final class Merge {
        public final String kwTag;
        public final String symbol;
        public final String result;

        Merge(String kwTag, String symbol, String result) {
            this.kwTag = kwTag;
            this.symbol = symbol;
            this.result = result;
        }
    }

    /**
     * 选颜文字所需的全部数据，打包传递。
     *
     * 单独拎出来是因为这些东西总是同生同死：解析同一批原文、一起缓存、
     * 一起传给 MeowCommitter。以后再加词表也只动这里，不用改一路上的签名。
     */
    public static final class Pack {
        public final List<Entry> lib;
        public final List<Merge> merges;
        public final List<KeywordRule> keywords;

        public Pack(List<Entry> lib, List<Merge> merges, List<KeywordRule> keywords) {
            this.lib = lib;
            this.merges = merges;
            this.keywords = keywords;
        }

        /** 从两份原文解析；颜文字库为空时退回内置兜底，保证任何时候都有东西可选 */
        public static Pack of(String libRaw, String kwRaw) {
            List<Entry> lib = parseLib(libRaw);
            if (lib.isEmpty()) {
                lib = fallbackLib();
            }
            return new Pack(lib, parseMerges(libRaw), parseKeywords(kwRaw));
        }
    }

    /** 省略号：连续两个以上的中文句号会被折算成它 */
    public static final String ELLIPSIS = "...";

    /**
     * 句尾标点 → 符号标签。
     *
     * 注意空串对应「句号」而不是「没有标点」—— 中文句号是确认键、会被吃掉，
     * 所以留下空串恰恰说明用户打的是句号，语气上就是一句陈述。
     */
    public static String symbolOf(String punct) {
        if (punct == null || punct.isEmpty()) {
            return "句号";
        }
        // 省略号先判 —— 它里面既没有 ？也没有 ！，落到下面会被当成句号
        if (punct.indexOf('.') >= 0) {
            return "省略号";
        }
        boolean q = punct.indexOf('？') >= 0 || punct.indexOf('?') >= 0;
        boolean e = punct.indexOf('！') >= 0 || punct.indexOf('!') >= 0;
        if (q && e) {
            return "问叹";
        }
        if (q) {
            return "问号";
        }
        if (e) {
            return "叹号";
        }
        return "句号";
    }

    private KaomojiLib() {
    }

    // ------------------------------------------------------------------ 解析

    private static List<String> splitTags(String s) {
        List<String> out = new ArrayList<>();
        for (String t : s.split(",")) {
            String v = t.trim();
            if (!v.isEmpty()) {
                out.add(v);
            }
        }
        return out;
    }

    private static final String MERGE_PREFIX = "@merge";

    /**
     * 解析颜文字库。
     *
     * 段落头 {@code [情绪,... | 符号,...]} 声明标签，之后每一行是一张脸，
     * 直到下一个段落头。脸后面可以跟 {@code | ASCII降级版}。
     *
     * 段落头之前出现的脸会被丢掉 —— 没有标签的脸永远选不出来，留着只会
     * 让人以为它生效了。
     */
    public static List<Entry> parseLib(String raw) {
        List<Entry> out = new ArrayList<>();
        if (raw == null) {
            return out;
        }
        List<String> tags = null;
        List<String> symbols = null;
        for (String line : raw.split("\n")) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#") || t.startsWith("@")) {
                continue;
            }
            if (t.startsWith("[") && t.endsWith("]")) {
                String body = t.substring(1, t.length() - 1);
                int bar = body.indexOf('|');
                if (bar < 0) {
                    // 只写了情绪没写符号：当成「什么句尾都能用」
                    tags = splitTags(body);
                    symbols = Arrays.asList(ALL_SYMBOLS);
                } else {
                    tags = splitTags(body.substring(0, bar));
                    symbols = splitTags(body.substring(bar + 1));
                }
                continue;
            }
            if (tags == null || tags.isEmpty() || symbols.isEmpty()) {
                continue;   // 还没遇到段落头，或者段落头是空的
            }
            String kao = t;
            String ascii = null;
            int bar = t.indexOf('|');
            if (bar > 0) {
                kao = t.substring(0, bar).trim();
                ascii = t.substring(bar + 1).trim();
            }
            if (kao.isEmpty()) {
                continue;
            }
            out.add(new Entry(kao, (ascii == null || ascii.isEmpty()) ? kao : ascii,
                    tags, symbols));
        }
        return out;
    }

    /** 全部符号标签。段落头省略符号那一半时用它兜底。 */
    static final String[] ALL_SYMBOLS =
            {"句号", "问号", "叹号", "问叹", "省略号", "波浪", "爱心"};

    /** 从同一份颜文字库原文里挑出 {@code @merge 情绪 + 符号 = 情绪} 这类行 */
    public static List<Merge> parseMerges(String raw) {
        List<Merge> out = new ArrayList<>();
        if (raw == null) {
            return out;
        }
        for (String line : raw.split("\n")) {
            String t = line.trim();
            if (!t.startsWith(MERGE_PREFIX)) {
                continue;
            }
            String rest = t.substring(MERGE_PREFIX.length());
            int eq = rest.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String left = rest.substring(0, eq);
            String result = rest.substring(eq + 1).trim();
            int plus = left.indexOf('+');
            if (plus <= 0 || result.isEmpty()) {
                continue;
            }
            String kwTag = left.substring(0, plus).trim();
            String sym = left.substring(plus + 1).trim();
            if (!kwTag.isEmpty() && !sym.isEmpty()) {
                out.add(new Merge(kwTag, sym, result));
            }
        }
        return out;
    }

    /** 解析关键词表。按关键词长度倒序，保证「别难过」先于「难过」被命中。 */
    public static List<KeywordRule> parseKeywords(String raw) {
        List<KeywordRule> out = new ArrayList<>();
        if (raw == null) {
            return out;
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
            String key = t.substring(0, eq).trim();
            if (key.isEmpty()) {
                continue;
            }
            // 右边留空 = 排除短语
            out.add(new KeywordRule(key, splitTags(t.substring(eq + 1))));
        }
        Collections.sort(out, (a, b) -> Integer.compare(b.key.length(), a.key.length()));
        return out;
    }

    // ---------------------------------------------------------------- 扫描

    /**
     * 左到右最长优先扫描，返回最后一个命中的规则。
     *
     * 两条规则必须这样组合才不打架：
     *   最长优先 决定「同一位置上谁赢」——「别难过」吃掉「难过」
     *   最后命中 决定「不同位置谁赢」——「刚才难过，现在开心」取开心
     * 如果只按起始位置取最靠后的，「别难过」会输给从第 1 位开始的「难过」。
     *
     * @return 命中的规则；没有命中或最后命中的是排除短语时返回 null
     */
    public static KeywordRule scanLastKeyword(String body, List<KeywordRule> rules) {
        if (body == null || body.isEmpty() || rules == null || rules.isEmpty()) {
            return null;
        }
        KeywordRule last = null;
        int i = 0;
        while (i < body.length()) {
            KeywordRule hit = null;
            for (KeywordRule r : rules) {   // 已按长度倒序，第一个匹配即最长
                if (body.startsWith(r.key, i)) {
                    hit = r;
                    break;
                }
            }
            if (hit != null) {
                last = hit;
                i += hit.key.length();
            } else {
                i++;
            }
        }
        // 最后命中的是排除短语 → 弃权，交给标点判断
        return (last == null || last.isExclusion()) ? null : last;
    }

    // ---------------------------------------------------------------- 选择

    /**
     * 情绪合成表命中时的候选；没有可用规则时返回空表。
     *
     * 合成出来的结果情绪仍然优先和句尾符号取交集 —— 合成只是换了个情绪，
     * 不代表可以无视句尾。交集空了才退而求其次用整组。
     */
    private static List<Entry> byMerge(List<Entry> lib, List<Merge> merges,
                                       List<String> kwTags, String symbol) {
        if (merges == null) {
            return new ArrayList<>();
        }
        for (Merge m : merges) {
            if (!m.symbol.equals(symbol)) {
                continue;
            }
            // kwTag 写 * 表示「不管原本什么情绪」，用来兑现 ？！ 这种
            // 本身就覆盖语气的句尾；具体规则写在前面就能赢过它
            if (!"*".equals(m.kwTag) && !kwTags.contains(m.kwTag)) {
                continue;
            }
            List<Entry> all = new ArrayList<>();
            List<Entry> fitting = new ArrayList<>();
            for (Entry e : lib) {
                if (e.tags.contains(m.result)) {
                    all.add(e);
                    if (e.fits(symbol)) {
                        fitting.add(e);
                    }
                }
            }
            if (!fitting.isEmpty()) {
                return fitting;     // 先写的规则先赢
            }
            if (!all.isEmpty()) {
                return all;
            }
        }
        return new ArrayList<>();
    }

    /**
     * 挑一个颜文字。
     *
     * @param body   句子原文（未经关键词替换 —— 用户配的关键词是按自己打的字写的）
     * @param punct  保留下来的句尾标点，句号被吃掉后为空串
     * @param avoid  上次用过的颜文字，尽量避开，减少机械感；可为 null
     * @return 选中的颜文字；库为空时返回 ""
     */
    public static String select(String body, String punct, Pack pack, String avoid) {
        if (pack == null || pack.lib.isEmpty()) {
            return "";
        }
        List<Entry> lib = pack.lib;
        KeywordRule kw = scanLastKeyword(body, pack.keywords);
        String symbol = symbolOf(punct);

        List<Entry> byKw = new ArrayList<>();
        if (kw != null) {
            for (Entry e : lib) {
                if (e.hasAny(kw.tags)) {
                    byKw.add(e);
                }
            }
        }
        List<Entry> bySymbol = new ArrayList<>();
        for (Entry e : lib) {
            if (e.fits(symbol)) {
                bySymbol.add(e);
            }
        }

        // 五级降级，保证一定有结果
        List<Entry> pool = new ArrayList<>();
        for (Entry e : byKw) {
            if (bySymbol.contains(e)) {
                pool.add(e);
            }
        }
        if (pool.isEmpty() && kw != null) {
            // 交集为空时先问合成表：情绪和句尾叠起来往往是第三种情绪，
            // 直接保情绪或保句尾都会丢掉这层意思（「什么意思！」= 难以置信）
            pool = byMerge(lib, pack.merges, kw.tags, symbol);
        }
        if (pool.isEmpty()) {
            pool = byKw;            // 关键词比句尾具体，交集空时保情绪
        }
        if (pool.isEmpty()) {
            pool = bySymbol;
        }
        if (pool.isEmpty()) {
            pool = lib;
        }

        // 排除上次用过的那个（除非排完就没了）
        if (avoid != null && pool.size() > 1) {
            List<Entry> filtered = new ArrayList<>();
            for (Entry e : pool) {
                if (!avoid.equals(e.kao)) {
                    filtered.add(e);
                }
            }
            if (!filtered.isEmpty()) {
                pool = filtered;
            }
        }

        int n = pool.size();
        int idx = ((MeowEngine.hashSeed("kao|" + body) % n) + n) % n;
        return pool.get(idx).kao;
    }

    // ------------------------------------------------------------ 标点合并

    /** 颜文字末尾自带的标点：'?' / '!' / 0（没有） */
    static char trailingPunctOf(String kao) {
        if (kao == null || kao.isEmpty()) {
            return 0;
        }
        char c = kao.charAt(kao.length() - 1);
        if (c == '?' || c == '？') {
            return '?';
        }
        if (c == '!' || c == '！') {
            return '!';
        }
        return 0;
    }

    /**
     * 把正文、句尾标点、颜文字拼起来。
     *
     * 若颜文字自带的标点与句尾标点同类，就吃掉句尾那个由颜文字代劳，
     * 避免出现「？ (=･ｪ･=)?」这种双问号。
     * 但句尾是组合标点（？！）时一律保留 —— 连用本身就是语气，
     * 只吃掉其中一个会留下孤零零的另一个。
     */
    public static String assemble(String body, String punct, String kao) {
        String p = punct == null ? "" : punct;
        if (kao == null || kao.isEmpty()) {
            return body + p;
        }
        char kp = trailingPunctOf(kao);
        boolean combo = p.length() > 1;
        String keep = p;
        if (kp != 0 && !combo) {
            String sym = symbolOf(p);
            char pk = sym.equals("问号") ? '?' : (sym.equals("叹号") ? '!' : 0);
            if (pk == kp) {
                keep = "";
            }
        }
        return body + keep + " " + kao;
    }

    /**
     * 句尾是否已经带着库里的某个颜文字（防止重复叠加）。
     * ASCII 降级版也算 —— 用户可能是从别处粘来的降级写法。
     */
    public static boolean endsWithKnownKaomoji(String text, List<Entry> lib) {
        if (text == null || lib == null) {
            return false;
        }
        String t = text.trim();
        for (Entry e : lib) {
            if (!e.kao.isEmpty() && t.endsWith(e.kao)) {
                return true;
            }
            if (!e.ascii.isEmpty() && t.endsWith(e.ascii)) {
                return true;
            }
        }
        return false;
    }

    /** 内置库的兜底（res/raw 读不到时用），保证任何情况下都有东西可选。 */
    public static List<Entry> fallbackLib() {
        List<String> all = Arrays.asList(ALL_SYMBOLS);
        List<Entry> out = new ArrayList<>();
        out.add(new Entry("(=^･ω･^=)", "(=^.w.^=)", Arrays.asList("平静"), all));
        out.add(new Entry("ヽ(=^･ω･^=)丿", "\\(=^w^=)/", Arrays.asList("开心", "兴奋"), all));
        out.add(new Entry("(=ʘωʘ=)", "(=o.o=)", Arrays.asList("困惑"), all));
        out.add(new Entry("(=ﾟдﾟ=)", "(=O.O=)", Arrays.asList("惊讶"), all));
        return out;
    }
}
