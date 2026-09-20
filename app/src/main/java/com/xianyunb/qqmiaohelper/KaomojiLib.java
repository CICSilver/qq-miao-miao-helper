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
 *   颜文字库    标签1,标签2 = 颜文字
 *               @merge 关键词情绪 + 标点情绪 = 结果情绪    ← 情绪合成表，同一份文件里
 *   关键词表    关键词 = 情绪标签      （右边留空 = 排除短语）
 *
 * 标签写在左边是必须的 —— 颜文字本身含 '='（猫脸的眼睛就是 =），
 * 放右边的话 indexOf('=') 会切在脸中间。标签永远不含 '='。
 *
 * 选择流程：
 *   1. 扫原文找情绪标签（左到右最长优先，取最后一个命中）
 *   2. 句尾标点也对应一个标签
 *   3. 候选 = 关键词标签 ∩ 标点标签
 *      交集为空 → 查情绪合成表 → 只用关键词标签 → 只用标点标签 → 全库
 *   4. 从候选里排除上次用过的那个，再按内容哈希定选
 */
public final class KaomojiLib {

    /** 一条颜文字及其标签 */
    public static final class Entry {
        public final String kao;
        public final List<String> tags;

        Entry(String kao, List<String> tags) {
            this.kao = kao;
            this.tags = tags;
        }

        boolean hasAny(List<String> want) {
            for (String t : want) {
                if (tags.contains(t)) {
                    return true;
                }
            }
            return false;
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
     * 一条情绪合成规则：关键词情绪 + 标点情绪 = 结果情绪。
     *
     * 存在的理由是「什么意思！」这种句子 —— 关键词说它困惑、标点说它兴奋，
     * 两个池子毫无交集，可单独保哪一边都不对（困惑组自带问号，兴奋组太欢快）。
     * 它真正的语气是难以置信，也就是惊讶。这种语义叠加没法从标签本身推出来，
     * 只能列表。
     */
    public static final class Merge {
        public final String kwTag;
        public final String punctTag;
        public final String result;

        Merge(String kwTag, String punctTag, String result) {
            this.kwTag = kwTag;
            this.punctTag = punctTag;
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
     * 句尾标点 → 情绪标签。
     * 组合标点（？！ / ！？）归入「惊讶」，省略号归入「无奈」。
     */
    public static String punctTag(String punct) {
        if (punct == null || punct.isEmpty()) {
            return "平静";
        }
        // 省略号单独一档 —— 它里面既没有 ？也没有 ！，落到下面会被当成平静
        if (punct.indexOf('.') >= 0) {
            return "无奈";
        }
        boolean q = punct.indexOf('？') >= 0 || punct.indexOf('?') >= 0;
        boolean e = punct.indexOf('！') >= 0 || punct.indexOf('!') >= 0;
        if (q && e) {
            return "惊讶";
        }
        if (q) {
            return "疑问";
        }
        if (e) {
            return "兴奋";
        }
        return "平静";
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

    public static List<Entry> parseLib(String raw) {
        List<Entry> out = new ArrayList<>();
        if (raw == null) {
            return out;
        }
        for (String line : raw.split("\n")) {
            String t = line.trim();
            // @ 开头的是指令行（目前只有 @merge），它也含 '='，不跳过会被当成颜文字
            if (t.isEmpty() || t.startsWith("#") || t.startsWith("@")) {
                continue;
            }
            int eq = t.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            List<String> tags = splitTags(t.substring(0, eq));
            String kao = t.substring(eq + 1).trim();
            if (!kao.isEmpty() && !tags.isEmpty()) {
                out.add(new Entry(kao, tags));
            }
        }
        return out;
    }

    /** 从同一份颜文字库原文里挑出 {@code @merge 甲 + 乙 = 丙} 这类行 */
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
            String pTag = left.substring(plus + 1).trim();
            if (!kwTag.isEmpty() && !pTag.isEmpty()) {
                out.add(new Merge(kwTag, pTag, result));
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

    /** 情绪合成表命中时的候选；没有可用规则时返回空表 */
    private static List<Entry> byMerge(List<Entry> lib, List<Merge> merges,
                                       List<String> kwTags, String pTag) {
        List<Entry> out = new ArrayList<>();
        if (merges == null) {
            return out;
        }
        for (Merge m : merges) {
            if (!m.punctTag.equals(pTag) || !kwTags.contains(m.kwTag)) {
                continue;
            }
            for (Entry e : lib) {
                if (e.tags.contains(m.result)) {
                    out.add(e);
                }
            }
            if (!out.isEmpty()) {
                return out;     // 先写的规则先赢
            }
        }
        return out;
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
        String pTag = punctTag(punct);

        List<Entry> byKw = new ArrayList<>();
        if (kw != null) {
            for (Entry e : lib) {
                if (e.hasAny(kw.tags)) {
                    byKw.add(e);
                }
            }
        }
        List<Entry> byPunct = new ArrayList<>();
        for (Entry e : lib) {
            if (e.tags.contains(pTag)) {
                byPunct.add(e);
            }
        }

        // 五级降级，保证一定有结果
        List<Entry> pool = new ArrayList<>();
        for (Entry e : byKw) {
            if (byPunct.contains(e)) {
                pool.add(e);
            }
        }
        if (pool.isEmpty() && kw != null) {
            // 交集为空时先问合成表：两种情绪叠起来往往是第三种情绪，
            // 直接保关键词或保标点都会丢掉这层意思（「什么意思！」= 难以置信）
            pool = byMerge(lib, pack.merges, kw.tags, pTag);
        }
        if (pool.isEmpty()) {
            pool = byKw;            // 关键词比标点具体，交集空时保关键词
        }
        if (pool.isEmpty()) {
            pool = byPunct;
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
            char pk = punctTag(p).equals("疑问") ? '?' : (punctTag(p).equals("兴奋") ? '!' : 0);
            if (pk == kp) {
                keep = "";
            }
        }
        return body + keep + " " + kao;
    }

    /** 句尾是否已经带着库里的某个颜文字（防止重复叠加） */
    public static boolean endsWithKnownKaomoji(String text, List<Entry> lib) {
        if (text == null || lib == null) {
            return false;
        }
        String t = text.trim();
        for (Entry e : lib) {
            if (!e.kao.isEmpty() && t.endsWith(e.kao)) {
                return true;
            }
        }
        return false;
    }

    /** 内置库的兜底（res/raw 读不到时用），保证任何情况下都有东西可选。 */
    public static List<Entry> fallbackLib() {
        List<Entry> out = new ArrayList<>();
        out.add(new Entry("(=^･ω･^=)", Arrays.asList("平静", "日常")));
        out.add(new Entry("ヽ(=^･ω･^=)丿", Arrays.asList("兴奋", "开心")));
        out.add(new Entry("(=ʘωʘ=)", Arrays.asList("疑问")));
        out.add(new Entry("(=ﾟдﾟ=)", Arrays.asList("惊讶")));
        return out;
    }
}
