package com.xianyunb.qqmiaohelper;

/**
 * 支持的聊天软件枚举。集中管理显示名与对应包名，
 * 供主界面选择列表与服务端按包名过滤使用。
 *
 * 加一款新软件只要在这里添一行：引擎读的是「聚焦且可编辑」的节点，
 * 不认任何软件专属的 view id，所以服务端和界面都不用改。
 *
 * 注意无障碍配置里【没有】声明 packageNames —— vivo 会拦截声明监听
 * QQ 包名的无障碍服务（A/B 验证过）。过滤全靠这里 + enabledPackages，
 * 服务本身收全部应用的事件然后立即丢弃。
 */
public enum ChatApps {
    QQ("QQ", new String[]{"com.tencent.mobileqq", "com.tencent.mobileqqi"}),
    WECHAT("微信", new String[]{"com.tencent.mm"});

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
