package com.xianyunb.qqmiaohelper;

/**
 * 支持的聊天软件枚举。集中管理显示名与对应包名，
 * 供主界面选择列表与服务端按包名过滤使用。
 */
public enum ChatApps {
    WECHAT("微信", new String[]{"com.tencent.mm"}),
    QQ("QQ", new String[]{"com.tencent.mobileqq", "com.tencent.mobileqqi"}),
    TELEGRAM("Telegram", new String[]{"org.telegram.messenger"}),
    THREADS("Threads", new String[]{"com.instagram.barcelona"}),
    DINGTALK("钉钉", new String[]{"com.alibaba.android.rimet"});

    private final String displayName;
    private final String[] packageNames;

    ChatApps(String displayName, String[] packageNames) {
        this.displayName = displayName;
        this.packageNames = packageNames;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String[] getPackageNames() {
        return packageNames;
    }

    /**
     * 判断给定的应用包名是否属于某个枚举项。
     */
    public boolean matches(String pkg) {
        if (pkg == null) {
            return false;
        }
        for (String name : packageNames) {
            if (name.equals(pkg)) {
                return true;
            }
        }
        return false;
    }
}
