package com.xianyunb.qqmiaohelper;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 增量重写状态机。
 *
 * 输入框每次内容变化都会重新变换一遍全文，这带来三个 MeowEngine 本身管不了的问题：
 *
 *  1. 自触发循环 —— 我们写回去的内容会再次触发 TYPE_VIEW_TEXT_CHANGED。
 *  2. 自指规则套娃 —— 自定义规则「我们 = 我们这群喵」的替换结果含有自身原文，
 *     反复变换会变成「我们这群喵这群喵这群喵…」无限膨胀。
 *  3. 拼音组词 —— 在输入法还没上屏时改写文本会把组词状态打乱。
 *
 * 解法是记住用户的**原文**：框里的内容若以我们上次写入的结果开头，说明用户只是
 * 在后面继续敲，把新敲的那截接回上次的原文即可。这样变换永远作用在纯用户输入上，
 * 自指规则自然不会套娃。
 */
public final class MeowRewriter {

    /** 疑似拼音缓冲：结尾是连续 ASCII 字母，且前文含中文。 */
    private static final Pattern COMPOSING_TAIL = Pattern.compile("[A-Za-z']{2,}$");
    private static final Pattern HAS_CJK = Pattern.compile("[\\u4e00-\\u9fff]");

    /** 用户真正打进去的原文（未经变换）。 */
    private String lastOriginal;
    /** 我们最后一次写进输入框的内容。 */
    private String lastWritten;

    /** 切换输入框、或框被清空时调用。 */
    public void reset() {
        lastOriginal = null;
        lastWritten = null;
    }

    /** 读到的内容正是我们自己写进去的 —— 回声，应当忽略。 */
    public boolean isEcho(String current) {
        return current != null && current.equals(lastWritten);
    }

    /**
     * 多数中文输入法把拼音缓冲放在自己的候选栏里，EditText 只收到已上屏的文字；
     * 但有些输入法用 setComposingText 把拼音直接写进 EditText（带下划线那种），
     * 这时候改写会打乱输入法状态。
     */
    public static boolean looksComposing(String text) {
        if (text == null) {
            return false;
        }
        Matcher m = COMPOSING_TAIL.matcher(text);
        if (!m.find()) {
            return false;
        }
        String head = text.substring(0, text.length() - m.group().length());
        return HAS_CJK.matcher(head).find();
    }

    /**
     * 计算这次应当写回输入框的内容。
     *
     * @return 需要写回的新文本；无需改动时返回 null
     */
    public String rewrite(String current, MeowEngine.Config cfg, boolean skipComposingCheck) {
        if (current == null || current.trim().isEmpty()) {
            reset();
            return null;
        }
        if (isEcho(current)) {
            return null;
        }
        if (!skipComposingCheck && looksComposing(current)) {
            return null;
        }

        String original;
        boolean reconstructed;
        if (lastWritten == null) {
            // 还没写过任何东西，框里就是用户原文
            original = current;
            reconstructed = true;
        } else if (current.startsWith(lastWritten)) {
            // 用户在我们的结果后面继续敲：把新敲的那截接回原文
            original = (lastOriginal == null ? "" : lastOriginal)
                    + current.substring(lastWritten.length());
            reconstructed = true;
        } else {
            // 用户在中间插入/删除了内容，原文无从还原，只能以框里现有内容为准。
            // 这条退化路径上必须摘掉自指规则，否则反复变换会发散。
            original = current;
            reconstructed = false;
        }

        MeowEngine.Config effective = reconstructed ? cfg : withoutSelfReferential(cfg);
        String out = MeowEngine.transform(original, effective);

        lastOriginal = original;
        lastWritten = out;

        return out.equals(current) ? null : out;
    }

    /** 退化路径专用：丢掉自指规则，保证反复变换一定收敛。 */
    private static MeowEngine.Config withoutSelfReferential(MeowEngine.Config cfg) {
        boolean any = false;
        for (MeowEngine.Rule r : cfg.rules) {
            if (r.isSelfReferential()) {
                any = true;
                break;
            }
        }
        if (!any) {
            return cfg;
        }
        List<MeowEngine.Rule> safe = new ArrayList<>();
        for (MeowEngine.Rule r : cfg.rules) {
            if (!r.isSelfReferential()) {
                safe.add(r);
            }
        }
        MeowEngine.Config copy = new MeowEngine.Config();
        copy.suffix = cfg.suffix;
        copy.suffixBeforePunct = cfg.suffixBeforePunct;
        copy.enableSuffix = cfg.enableSuffix;
        copy.maxSuffixPerMessage = cfg.maxSuffixPerMessage;
        copy.minSentenceLength = cfg.minSentenceLength;
        copy.enableKaomoji = cfg.enableKaomoji;
        copy.kaomoji = cfg.kaomoji;
        copy.protectRegions = cfg.protectRegions;
        copy.rules = safe;
        return copy;
    }
}
