package com.xianyunb.qqmiaohelper;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

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
    public static final String KEY_ENABLED_APPS = "enabled_apps";
    /** 用户「见过」哪些聊天软件的开关。升级后新出现的默认打开，见 adoptNewApps */
    public static final String KEY_KNOWN_APPS = "known_apps";
    /** 自定义替换规则，每行 原文=替换 */
    /** 总开关。关掉后无障碍服务立刻变成空操作，不碰任何输入框。 */
    public static final String KEY_MASTER_ENABLED = "master_enabled";
    /** 颜文字库（标签在左、颜文字在右），留空则用 res/raw 的内置库 */
    public static final String KEY_KAOMOJI_LIB = "kaomoji_lib";
    /** 颜文字关键词表（关键词=情绪标签，右边留空为排除短语） */
    public static final String KEY_KAOMOJI_KEYWORDS = "kaomoji_keywords";
    public static final String KEY_CUSTOM_RULES = "custom_rules";
    /** true: 今天真好喵！  false: 今天真好！喵（原版行为） */
    public static final String KEY_MEOW_BEFORE_PUNCT = "meow_before_punct";

    // 处理模式已移除：现在只有一种交互 —— 中文句号作确认键，
    // 打字过程中输入框完全不动。原来的「实时处理」会在打字时反复重写整框，
    // 语气词跟着光标跑，已废弃。

    // 内置颜文字库改放 res/raw/kaomoji_lib.txt，纯文本便于修改扩充。

    private final SharedPreferences prefs;
    private final Context appContext;

    /**
     * 配置版本号，任何一项改动都 +1。
     *
     * 无障碍服务每次按键都要确认配置有没有变。原来的办法是把整份颜文字库
     * 读出来和缓存做字符串比较 —— 词库涨到 24 KB 之后，每次按键都要读两个
     * raw 资源、解码、再比 24K 个字符，纯属白烧。事件回调太慢会被系统判定
     * 无响应并停用服务。现在比一个 int 就够了。
     */
    private volatile int version = 1;

    /** 必须持有强引用：SharedPreferences 只保存监听器的弱引用 */
    private final SharedPreferences.OnSharedPreferenceChangeListener prefListener =
            new SharedPreferences.OnSharedPreferenceChangeListener() {
                @Override
                public void onSharedPreferenceChanged(SharedPreferences sp, String key) {
                    version++;
                }
            };

    public CatConfig(Context context) {
        appContext = context.getApplicationContext();
        prefs = appContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        prefs.registerOnSharedPreferenceChangeListener(prefListener);
        adoptNewApps();
    }

    /** 配置快照编号；变了才需要重新解析，见 {@link #version} */
    public int getVersion() {
        return version;
    }

    /**
     * 版本升级时新加的聊天软件默认打开。
     *
     * 没有这一步的话，老用户升级上来 enabled_apps 里只有 QQ，新加的微信
     * 会是关的 —— 看起来就像「支持微信」这件事没生效。用「见过哪些」而不是
     * 版本号来判断，以后再加软件也不用改这里。
     */
    private void adoptNewApps() {
        java.util.Set<String> known = prefs.getStringSet(KEY_KNOWN_APPS, null);
        if (known == null && !prefs.contains(KEY_ENABLED_APPS)) {
            return;     // 全新安装，走 getEnabledApps 的默认值就行
        }
        java.util.Set<String> seen = new java.util.HashSet<>(
                known == null ? java.util.Collections.<String>emptySet() : known);
        java.util.Set<String> enabled = new java.util.HashSet<>(
                prefs.getStringSet(KEY_ENABLED_APPS, java.util.Collections.<String>emptySet()));
        boolean changed = false;
        for (ChatApps app : ChatApps.values()) {
            if (!seen.contains(app.name())) {
                seen.add(app.name());
                enabled.add(app.name());
                changed = true;
            }
        }
        if (changed) {
            prefs.edit()
                    .putStringSet(KEY_KNOWN_APPS, seen)
                    .putStringSet(KEY_ENABLED_APPS, enabled)
                    .apply();
        }
    }

    /**
     * res/raw 的内容缓存。这些是编译进包里的常量，一个进程里读一次就够了 ——
     * 原来每次按键都重读一遍 24 KB，是服务被系统判定无响应的主要原因。
     */
    private final java.util.Map<Integer, String> rawCache = new java.util.HashMap<>();

    /** 读 res/raw 里的纯文本默认值。读不到时返回空串，调用方自行兜底。 */
    private synchronized String readRaw(int resId) {
        String hit = rawCache.get(resId);
        if (hit != null) {
            return hit;
        }
        String v = readRawUncached(resId);
        rawCache.put(resId, v);
        return v;
    }

    private String readRawUncached(int resId) {
        InputStream in = null;
        try {
            in = appContext.getResources().openRawResource(resId);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return new String(bos.toByteArray(), "UTF-8");
        } catch (Exception e) {
            return "";
        } finally {
            try {
                if (in != null) {
                    in.close();
                }
            } catch (Exception ignored) {
                // 关闭失败无所谓
            }
        }
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

    /** 颜文字库原文。用户没填就用内置的。 */
    public String getKaomojiLib() {
        String v = prefs.getString(KEY_KAOMOJI_LIB, "");
        return v.trim().isEmpty() ? readRaw(R.raw.kaomoji_lib) : v;
    }

    public void setKaomojiLib(String v) {
        prefs.edit().putString(KEY_KAOMOJI_LIB, v == null ? "" : v).apply();
    }

    /** 界面上「恢复默认」用 */
    public String getDefaultKaomojiLib() {
        return readRaw(R.raw.kaomoji_lib);
    }

    public String getKaomojiKeywords() {
        String v = prefs.getString(KEY_KAOMOJI_KEYWORDS, "");
        return v.trim().isEmpty() ? readRaw(R.raw.kaomoji_keywords) : v;
    }

    public void setKaomojiKeywords(String v) {
        prefs.edit().putString(KEY_KAOMOJI_KEYWORDS, v == null ? "" : v).apply();
    }

    public String getDefaultKaomojiKeywords() {
        return readRaw(R.raw.kaomoji_keywords);
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

    /**
     * 获取已启用的软件（按枚举 name 存储）。默认全开。
     *
     * 默认全开是有意的：总开关在最上面，不想用随时关掉；反过来
     * 「装了却发现在微信里没反应」会让人以为是坏了。
     */
    public java.util.Set<String> getEnabledApps() {
        java.util.Set<String> set = prefs.getStringSet(KEY_ENABLED_APPS, null);
        // 只认 null（从没存过）。存过的空集合是用户把勾全取消了，得照办 ——
        // 把空集合也当成「没设置」的话，全部取消反而会变回全开。
        if (set == null) {
            java.util.Set<String> def = new java.util.HashSet<>();
            for (ChatApps app : ChatApps.values()) {
                def.add(app.name());
            }
            return def;
        }
        return new java.util.HashSet<>(set);
    }

    public boolean isAppEnabled(ChatApps app) {
        return getEnabledApps().contains(app.name());
    }

    public void setEnabledApps(java.util.Set<String> appNames) {
        // 同时记下「这些软件用户都见过了」，否则下次启动 adoptNewApps
        // 会把刚被取消勾选的又打开
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (ChatApps app : ChatApps.values()) {
            seen.add(app.name());
        }
        prefs.edit()
                .putStringSet(KEY_ENABLED_APPS, new java.util.HashSet<>(appNames))
                .putStringSet(KEY_KNOWN_APPS, seen)
                .apply();
    }
}
