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
