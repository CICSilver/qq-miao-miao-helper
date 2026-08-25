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
        String text = raw;

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

        // 4. 末尾随机颜文字
        if (config.isEnableEmoticon()) {
            text = text + " " + pickEmoticon();
        }

        return text;
    }

    /**
     * 在标点句末添加"喵"。
     */
    private String addMeowAfterPunctuation(String text) {
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
        List<String> pool = new ArrayList<>();
        String custom = config.getCustomEmoticons();
        if (custom != null && !custom.trim().isEmpty()) {
            // 每行一个颜文字
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
        return pool.get(random.nextInt(pool.size()));
    }
}
