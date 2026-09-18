package com.xianyunb.qqmiaohelper;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Arrays;
import java.util.List;

/**
 * 配置存储。所有开关和选项均持久化到 SharedPreferences。
 */
public class CatConfig {

    private static final String PREF_NAME = "cat_config";

    // 键
    public static final String KEY_ENABLE_NI = "enable_ni";
    public static final String KEY_ENABLE_WO = "enable_wo";
    public static final String KEY_ENABLE_MEOW = "enable_meow";
    public static final String KEY_ENABLE_EMOTICON = "enable_emoticon";
    public static final String KEY_PROCESSING_MODE = "processing_mode";
    public static final String KEY_CUSTOM_EMOTICONS = "custom_emoticons";
    public static final String KEY_ENABLED_APPS = "enabled_apps";
    /** 自定义替换规则，每行 原文=替换 */
    /** 总开关。关掉后无障碍服务立刻变成空操作，不碰任何输入框。 */
    public static final String KEY_MASTER_ENABLED = "master_enabled";
    public static final String KEY_CUSTOM_RULES = "custom_rules";
    /** true: 今天真好喵！  false: 今天真好！喵（原版行为） */
    public static final String KEY_MEOW_BEFORE_PUNCT = "meow_before_punct";

    // 处理模式
    public static final int MODE_PUNCTUATION = 0; // 标点触发
    public static final int MODE_REALTIME = 1;    // 实时处理

    // 内置颜文字库
    public static final List<String> BUILTIN_EMOTICONS = Arrays.asList(
            "(=^･ω･^=)", "ヽ(=^･ω･^=)丿", "(=^ω^=)", "ฅ^•ﻌ•^ฅ",
            "(=｀ω´=)", "(*^ω^*)", "(=^･^=)", "（=ʘωʘ=）",
            "(=^̮^=)", "(๑=^･ω･^=๑)", "(=˃̶᷅ω˂̶᷄=)", "喵~", "喵喵~"
    );

    private final SharedPreferences prefs;

    public CatConfig(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    /**
     * 总开关。
     *
     * 这是「暂停」的唯一正确入口：关掉之后服务收到事件就立刻返回，
     * 不读输入框、不改任何文本。注意它只是暂停 —— 无障碍权限仍然授予着，
     * 要彻底停止请去系统设置里关闭无障碍服务，或卸载本应用。
     */
    public boolean isMasterEnabled() {
        return prefs.getBoolean(KEY_MASTER_ENABLED, true);
    }

    public void setMasterEnabled(boolean v) {
        prefs.edit().putBoolean(KEY_MASTER_ENABLED, v).apply();
    }

    public boolean isEnableNi() {
        return prefs.getBoolean(KEY_ENABLE_NI, true);
    }

    public boolean isEnableWo() {
        return prefs.getBoolean(KEY_ENABLE_WO, true);
    }

    public boolean isEnableMeow() {
        return prefs.getBoolean(KEY_ENABLE_MEOW, true);
    }

    public boolean isEnableEmoticon() {
        return prefs.getBoolean(KEY_ENABLE_EMOTICON, true);
    }

    public int getProcessingMode() {
        return prefs.getInt(KEY_PROCESSING_MODE, MODE_PUNCTUATION);
    }

    public String getCustomEmoticons() {
        return prefs.getString(KEY_CUSTOM_EMOTICONS, "");
    }

    /** 自定义替换规则原文（每行 原文=替换）。默认留空，只用 你/我 两个开关。 */
    public String getCustomRules() {
        return prefs.getString(KEY_CUSTOM_RULES, "");
    }

    public void setCustomRules(String v) {
        prefs.edit().putString(KEY_CUSTOM_RULES, v == null ? "" : v).apply();
    }

    /**
     * 语气词相对标点的位置。
     * 默认 true（今天真好喵！）—— 比原版的「今天真好！喵」读起来自然。
     */
    public boolean isMeowBeforePunct() {
        return prefs.getBoolean(KEY_MEOW_BEFORE_PUNCT, true);
    }

    public void setMeowBeforePunct(boolean v) {
        prefs.edit().putBoolean(KEY_MEOW_BEFORE_PUNCT, v).apply();
    }

    public void setEnableNi(boolean v) {
        prefs.edit().putBoolean(KEY_ENABLE_NI, v).apply();
    }

    public void setEnableWo(boolean v) {
        prefs.edit().putBoolean(KEY_ENABLE_WO, v).apply();
    }

    public void setEnableMeow(boolean v) {
        prefs.edit().putBoolean(KEY_ENABLE_MEOW, v).apply();
    }

    public void setEnableEmoticon(boolean v) {
        prefs.edit().putBoolean(KEY_ENABLE_EMOTICON, v).apply();
    }

    public void setProcessingMode(int mode) {
        prefs.edit().putInt(KEY_PROCESSING_MODE, mode).apply();
    }

    public void setCustomEmoticons(String s) {
        prefs.edit().putString(KEY_CUSTOM_EMOTICONS, s).apply();
    }

    /**
     * 获取已启用的软件（按枚举 name 存储）。默认启用 QQ。
     */
    public java.util.Set<String> getEnabledApps() {
        java.util.Set<String> set = prefs.getStringSet(KEY_ENABLED_APPS, null);
        if (set == null || set.isEmpty()) {
            java.util.Set<String> def = new java.util.HashSet<>();
            def.add(ChatApps.QQ.name());
            return def;
        }
        return new java.util.HashSet<>(set);
    }

    public boolean isAppEnabled(ChatApps app) {
        return getEnabledApps().contains(app.name());
    }

    public void setEnabledApps(java.util.Set<String> appNames) {
        prefs.edit().putStringSet(KEY_ENABLED_APPS, new java.util.HashSet<>(appNames)).apply();
    }
}
