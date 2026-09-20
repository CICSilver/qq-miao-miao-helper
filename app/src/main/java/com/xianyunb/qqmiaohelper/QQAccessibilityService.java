package com.xianyunb.qqmiaohelper;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 无障碍服务：监听聊天软件的输入框，在用户打出中文句号时把该句转成喵喵语气。
 *
 * 交互就一条规则：<b>中文句号是确认键</b>。
 * 打字过程中输入框完全不动；打出「。」才封句（句号被吃掉），
 * 「？」「！」也封句但保留。英文标点、省略号、回车一律不触发。
 *
 * 封过的句子会被冻结、永不重算，所以不会出现「已显示的文字被回溯修改」
 * 或「重复加喵」——这两个问题在旧的整框重写模型下是结构性的。
 *
 * 注意这仍是「编辑时改写」，不是「发送时改写」：无障碍框架收到
 * TYPE_VIEW_CLICKED 时点击已经派发完毕，onKeyEvent() 又只收硬件按键，
 * 软键盘的发送键不走它。所以这里保证的是「按下发送前内容已经处理好」。
 */
public class QQAccessibilityService extends AccessibilityService {

    private static final String TAG = "QQMiao";
    private static final int MAX_DEPTH = 30;

    private TextProcessor processor;
    private CatConfig config;

    /** 封句状态机：冻结前缀 + ？！回滚重做 */
    private final MeowCommitter committer = new MeowCommitter();

    // 颜文字库与关键词表解析后缓存，原文变了才重新解析
    private String cachedLibRaw;
    private String cachedKwRaw;
    private List<KaomojiLib.Entry> lib;
    private List<KaomojiLib.KeywordRule> keywords;

    private Set<String> enabledPackages = new HashSet<>();

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        reloadConfig();
        Log.d(TAG, "无障碍服务已连接：监听软件包 = " + enabledPackages);
    }

    private void reloadConfig() {
        config = new CatConfig(this);
        processor = new TextProcessor(config);
        enabledPackages = collectEnabledPackages();
    }

    private Set<String> collectEnabledPackages() {
        Set<String> enabled = new HashSet<>();
        Set<String> appNames = config.getEnabledApps();
        for (ChatApps app : ChatApps.values()) {
            if (appNames.contains(app.name())) {
                for (String pkg : app.getPackageNames()) {
                    enabled.add(pkg);
                }
            }
        }
        return enabled;
    }

    /** 解析颜文字库与关键词表；原文没变就直接用缓存 */
    private void ensureKaomojiLoaded() {
        String libRaw = config.getKaomojiLib();
        if (lib == null || !libRaw.equals(cachedLibRaw)) {
            cachedLibRaw = libRaw;
            lib = KaomojiLib.parseLib(libRaw);
            if (lib.isEmpty()) {
                lib = KaomojiLib.fallbackLib();
            }
        }
        String kwRaw = config.getKaomojiKeywords();
        if (keywords == null || !kwRaw.equals(cachedKwRaw)) {
            cachedKwRaw = kwRaw;
            keywords = KaomojiLib.parseKeywords(kwRaw);
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (processor == null || config == null) {
            return;
        }

        // 总开关：关掉就什么都不做，连输入框都不去读
        if (!config.isMasterEnabled()) {
            committer.reset();
            return;
        }

        String pkg = event.getPackageName() == null ? "" : event.getPackageName().toString();
        if (!enabledPackages.contains(pkg)) {
            return;
        }

        if (event.getEventType() == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
            onTextChanged(pkg);
            return;
        }

        // 输入框被清空或切换 → 重置封句状态
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                || event.getEventType() == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                || event.getEventType() == AccessibilityEvent.TYPE_VIEW_FOCUSED) {
            AccessibilityNodeInfo input = findFocusedInputNode();
            if (input == null) {
                return;
            }
            CharSequence t = input.getText();
            if (t == null || t.length() == 0) {
                committer.reset();
            }
        }
    }

    private void onTextChanged(String pkg) {
        AccessibilityNodeInfo input = findFocusedInputNode();
        if (input == null) {
            return;
        }
        CharSequence t = input.getText();
        if (t == null || t.length() == 0) {
            committer.reset();
            return;
        }
        String raw = t.toString();
        if (committer.isEcho(raw)) {
            return;     // 自己写回去引发的回声
        }

        ensureKaomojiLoaded();
        String out = committer.onTextChanged(raw, processor.buildConfig(), lib, keywords);
        if (out == null) {
            return;     // 没有封句，输入框保持不动
        }
        Log.d(TAG, "封句[" + pkg + "]: '" + raw + "' -> '" + out + "'");
        setNodeText(input, out);
    }

    /**
     * 优先查找当前聚焦的输入框；找不到则退回深度优先查找可编辑节点。
     * 这样能避免抓到错误的输入控件（如搜索框）。
     */
    private AccessibilityNodeInfo findFocusedInputNode() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            return null;
        }
        AccessibilityNodeInfo focused = dfsFocusedEditable(root, 0);
        if (focused != null) {
            return focused;
        }
        return dfsEditable(root, 0);
    }

    private AccessibilityNodeInfo dfsFocusedEditable(AccessibilityNodeInfo node, int depth) {
        if (node == null || depth > MAX_DEPTH) {
            return null;
        }
        if (node.isEditable() && node.isFocused()) {
            return node;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo r = dfsFocusedEditable(node.getChild(i), depth + 1);
            if (r != null) {
                return r;
            }
        }
        return null;
    }

    private AccessibilityNodeInfo dfsEditable(AccessibilityNodeInfo node, int depth) {
        if (node == null || depth > MAX_DEPTH) {
            return null;
        }
        if (node.isEditable()) {
            return node;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo r = dfsEditable(node.getChild(i), depth + 1);
            if (r != null) {
                return r;
            }
        }
        return null;
    }

    /**
     * 写回文本并把光标放到末尾。
     *
     * 原版把 SELECTION_START/END 塞进 ACTION_SET_TEXT 的参数里 —— 那两个参数
     * 属于 ACTION_SET_SELECTION，在这里会被忽略。所以要单独再发一次。
     */
    private void setNodeText(AccessibilityNodeInfo node, String text) {
        try {
            Bundle args = new Bundle();
            args.putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
            if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
                Log.w(TAG, "ACTION_SET_TEXT 被拒绝");
                return;
            }
            Bundle sel = new Bundle();
            sel.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, text.length());
            sel.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, text.length());
            node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, sel);
        } catch (Exception e) {
            Log.e(TAG, "设置文本失败", e);
        }
    }

    @Override
    public void onInterrupt() {
        Log.d(TAG, "无障碍服务被中断");
    }

    @Override
    public boolean onUnbind(Intent intent) {
        Log.d(TAG, "无障碍服务解绑");
        return super.onUnbind(intent);
    }
}
