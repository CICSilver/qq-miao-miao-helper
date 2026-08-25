package com.xianyunb.qqmiaohelper;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 文本处理器：将普通文本转换为喵喵语气。
 * - 你 -> 主人
 * - 我 -> 本喵
 * - 在标点旁加喵（可选）
 * - 在末尾附加随机颜文字（可选）
 *
 * 关键：处理前会先剥离文本中已有的颜文字，避免重复叠加（防止"疯狂加颜表情"）。
 */
public class TextProcessor {

    private static final Pattern PUNCTUATION_PATTERN =
            Pattern.compile("([。！？!?；;!?.~～]+)");

    private final CatConfig config;
    private final Random random = new Random();

    public TextProcessor(CatConfig config) {
        this.config = config;
    }

    /**
     * 处理文本，返回替换后的新文本。
     *
     * @param raw 用户原始输入
     * @return 处理后的文本
     */
    public String process(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return raw;
        }

        // 先剥离已有颜文字，避免重复叠加
        String text = stripEmoticons(raw);

        // 1. 替换你 -> 主人
        if (config.isEnableNi()) {
            text = text.replace("你", "主人");
        }

        // 2. 替换我 -> 本喵
        if (config.isEnableWo()) {
            text = text.replace("我", "本喵");
        }

        // 3. 断句加喵（标点后加喵）
        if (config.isEnableMeow()) {
            text = addMeowAfterPunctuation(text);
        }

        // 4. 末尾随机颜文字（只在处理才加一个）
        if (config.isEnableEmoticon()) {
            String trimmed = text.trim();
            String emoticon = pickEmoticon();
            if (emoticon.isEmpty()) {
                text = trimmed;
            } else {
                text = trimmed + " " + emoticon;
            }
        }

        return text;
    }

    /**
     * 剥离文本中出现的内置 + 自定义颜文字，返回去掉颜文字后的文本。
     */
    private String stripEmoticons(String text) {
        List<String> pool = emoticonPool();
        if (pool.isEmpty()) {
            return text;
        }
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (String e : pool) {
            if (e == null || e.isEmpty()) {
                continue;
            }
            if (!first) {
                sb.append('|');
            }
            sb.append(Pattern.quote(e));
            first = false;
        }
        if (sb.length() == 0) {
            return text;
        }
        Pattern p = Pattern.compile(sb.toString());
        Matcher m = p.matcher(text);
        return m.replaceAll(" ").replaceAll("\\s+", " ").trim();
    }

    /**
     * 在标点句末添加"喵"。
     */
    private String addMeowAfterPunctuation(String text) {
        if (text.isEmpty()) {
            return text;
        }
        Matcher matcher = PUNCTUATION_PATTERN.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String punct = matcher.group(1);
            matcher.appendReplacement(sb, Matcher.quoteReplacement(punct + "喵"));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /**
     * 抽取一个颜文字：优先用自定义库，否则用内置库。
     */
    private String pickEmoticon() {
        List<String> pool = emoticonPool();
        if (pool.isEmpty()) {
            pool.addAll(CatConfig.BUILTIN_EMOTICONS);
        }
        if (pool.isEmpty()) {
            return "";
        }
        return pool.get(random.nextInt(pool.size()));
    }

    /**
     * 汇总自定义 + 内置颜文字库。
     */
    private List<String> emoticonPool() {
        List<String> pool = new ArrayList<>();
        String custom = config.getCustomEmoticons();
        if (custom != null && !custom.trim().isEmpty()) {
            String[] lines = custom.split("\n");
            for (String line : lines) {
                String t = line.trim();
                if (!t.isEmpty()) {
                    pool.add(t);
                }
            }
        }
        if (pool.isEmpty()) {
            pool.addAll(CatConfig.BUILTIN_EMOTICONS);
        }
        return pool;
    }
}
