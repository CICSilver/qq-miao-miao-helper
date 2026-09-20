package com.xianyunb.qqmiaohelper;

/**
 * 封句状态机：句号当确认键 + 冻结已处理前缀。
 *
 * 取代旧的 MeowRewriter（原文追踪）。根本区别：
 *   旧：每次触发都把整框基于「用户原文」重算 —— 删除或中途插入会污染原文，
 *       打字过程中文字会抖动、已显示的内容会被回溯修改。
 *   新：封过句的部分直接冻结、永不重算 —— 结构上不可能出现重复喵和回溯改写。
 *
 * 触发规则（只认中文全角标点）：
 *   。   封句 + 吃掉      —— 中文聊天不用句号结尾，它在这里只是「确认键」
 *   ？   封句 + 保留
 *   ！   封句 + 保留
 *   。。 封句 + 折成 ...  —— 连打的句号是省略号的意思，不是两个句号
 * 英文标点、回车一律不触发。
 *
 * 连用标点用【回滚重做】而不是延迟：打「？」立刻出疑问版，紧接着打「！」
 * 就把刚才那次封句撤销、按「？！」重做成惊讶版；第二个「。」同理，撤销后
 * 按省略号重做。这样保住了整个设计最值钱的性质 —— 输入框只在用户按键的
 * 瞬间变化，其余时候纹丝不动。
 */
public final class MeowCommitter {

    private static final char TERM_EAT = '。';
    private static final String TERM_KEEP = "？！";

    private static boolean isTerm(char c) {
        return c == TERM_EAT || TERM_KEEP.indexOf(c) >= 0;
    }

    /** 整段都是句号？（连打的句号要折成省略号） */
    private static boolean isAllEat(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        for (int k = 0; k < s.length(); k++) {
            if (s.charAt(k) != TERM_EAT) {
                return false;
            }
        }
        return true;
    }

    /**
     * 实际敲下的句尾标点 → 留在文本里的句尾标点。
     *   。      → 空串（吃掉）
     *   。。以上 → ...（省略号）
     *   其余     → 去掉句号后原样保留
     */
    static String normalizePunct(String punct) {
        if (punct == null || punct.isEmpty()) {
            return "";
        }
        if (isAllEat(punct)) {
            return punct.length() >= 2 ? KaomojiLib.ELLIPSIS : "";
        }
        StringBuilder kept = new StringBuilder();
        for (int k = 0; k < punct.length(); k++) {
            if (punct.charAt(k) != TERM_EAT) {
                kept.append(punct.charAt(k));
            }
        }
        return kept.toString();
    }

    /** 已冻结的前缀，永不重算 */
    private String committed = "";
    /** 最后一次写回输入框的内容，用于识别回声与回滚 */
    private String lastWritten;
    /** 最后一段在 committed 中的起始位置，回滚时截到这里 */
    private int lastStart = -1;
    /** 最后一段的原文（未变换），回滚重做时要用 */
    private String lastBody;
    /** 最后一段用户实际敲下的句尾标点（未折算），判定能否合并要看它 */
    private String lastRaw = "";
    /** 上次用过的颜文字，下次尽量避开 */
    private String lastKaomoji;
    /** 上上次用过的颜文字。回滚时要把 lastKaomoji 还原成它，否则重做会平白换一张脸 */
    private String prevKaomoji;

    public void reset() {
        committed = "";
        lastWritten = null;
        lastStart = -1;
        lastBody = null;
        lastRaw = "";
        // lastKaomoji 刻意不清，跨消息也避开重复
    }

    /** 读到的正是自己写进去的内容 —— 回声，应当忽略 */
    public boolean isEcho(String box) {
        return box != null && box.equals(lastWritten);
    }

    /**
     * 输入框内容变化时调用。
     *
     * @return 需要写回的新文本；无需改动返回 null
     */
    public String onTextChanged(String box, MeowEngine.Config cfg, KaomojiLib.Pack pack) {
        if (box == null) {
            return null;
        }
        if (isEcho(box)) {
            return null;
        }

        // ── 回滚重做：上一段刚封完，用户紧接着又补了一个能与之合并的标点
        if (tryRollback(box, cfg, pack)) {
            return lastWritten;
        }

        // 用户删进了冻结区 → 整体重置，之后的内容当作新原文
        if (!box.startsWith(committed)) {
            committed = "";
            lastStart = -1;
            lastBody = null;
            lastRaw = "";
        }

        String rest = box.substring(committed.length());
        int fired = 0;

        for (;;) {
            int i = -1;
            for (int k = 0; k < rest.length(); k++) {
                if (isTerm(rest.charAt(k))) {
                    i = k;
                    break;
                }
            }
            if (i < 0) {
                break;
            }
            int j = i;
            while (j < rest.length() && isTerm(rest.charAt(j))) {
                j++;
            }
            emit(rest.substring(0, i), rest.substring(i, j), cfg, pack);
            rest = rest.substring(j);
            fired++;
        }

        if (fired == 0) {
            return null;    // 没有新封句，什么都不做
        }
        String out = committed + rest;
        lastWritten = out;
        return out.equals(box) ? null : out;
    }

    /**
     * 用户刚封完一句又补了一个标点时，撤销那次封句、按合并后的标点重做。
     *
     * 能合并的两种：
     *   ？ + ！（或反之） → ？！  难以置信
     *   。 + 。（可再续）  → ...   省略号；第三个句号起结果不再变化，等于被吸收
     *
     * 只在「上一次封句刚好结束于文本末尾」时生效，这样判定简单且不会误伤 ——
     * 用户如果在后面接着打了别的字，就不再是「紧接着」了。
     */
    private boolean tryRollback(String box, MeowEngine.Config cfg, KaomojiLib.Pack pack) {
        if (lastWritten == null || lastBody == null || lastStart < 0) {
            return false;
        }
        if (!lastWritten.equals(committed)) {
            return false;   // 上次封句后面还有未封的尾巴，不算「紧接着」
        }
        if (box.length() != lastWritten.length() + 1 || !box.startsWith(lastWritten)) {
            return false;
        }
        char added = box.charAt(box.length() - 1);

        String redo = null;
        if (lastRaw.length() == 1) {
            char had = lastRaw.charAt(0);
            if ((had == '？' && added == '！') || (had == '！' && added == '？')) {
                redo = String.valueOf(had) + added;
            }
        }
        if (redo == null && added == TERM_EAT && isAllEat(lastRaw)) {
            redo = lastRaw + added;
        }
        if (redo == null) {
            return false;   // 已经是组合标点，或者补的标点凑不成一对
        }

        // 截掉上一段重做。颜文字的「避开上次」游标也要退回去，
        // 否则同一句话只因为多敲了一个标点就换了张脸。
        String body = lastBody;
        committed = committed.substring(0, lastStart);
        lastKaomoji = prevKaomoji;
        emit(body, redo, cfg, pack);
        lastWritten = committed;
        return true;
    }

    /** 变换一段并追加到冻结区 */
    private void emit(String body, String punct, MeowEngine.Config cfg, KaomojiLib.Pack pack) {
        String keptStr = normalizePunct(punct);

        lastStart = committed.length();
        lastBody = body;
        lastRaw = punct;

        if (body.trim().isEmpty()) {
            committed += keptStr;       // 空段（比如开头就打标点）只保留该留的标点
            return;
        }

        String meowed = MeowEngine.transform(body, cfg);

        String kao = "";
        // 句尾已经带着库里的颜文字就不再叠加
        if (cfg.enableKaomoji && !KaomojiLib.endsWithKnownKaomoji(body, pack.lib)) {
            // 注意：用【原文 body】检索关键词，不是变换后的结果 ——
            // 变换后「我」已经成了「本喵」，用户配的关键词就再也匹配不到了。
            prevKaomoji = lastKaomoji;
            kao = KaomojiLib.select(body, keptStr, pack, lastKaomoji);
            if (!kao.isEmpty()) {
                lastKaomoji = kao;
            }
        }
        committed += KaomojiLib.assemble(meowed, keptStr, kao);
    }
}
