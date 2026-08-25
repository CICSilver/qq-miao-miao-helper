package com.xianyunb.qqmiaohelper;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.HashSet;
import java.util.Set;

/**
 * 无障碍服务：监听已勾选聊天软件的输入框。
 * 按处理模式对用户输入的文本做喵喵语气转换并回写输入框。
 * - 标点触发：仅在文本新增标点时处理，打字阶段不处理
 * - 实时处理：停顿(去抖)后自动转换一次，不打断输入法组词推荐
 */
public class QQAccessibilityService extends AccessibilityService {

    private static final String TAG = "QQMiao";
    private static final int MAX_DEPTH = 30;

    // 实时处理模式的停顿时间（毫秒）：停这么长时间没有新输入才转换一次。
    private static final long DEBOUNCE_MS = 700;

    private TextProcessor processor;
    private CatConfig config;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable debounceRunnable = this::processIfApplicable;

    // 最近一个待处理的文本（去抖期间捕获的最新状态）
    private CharSequence pendingText;
    private String pendingPkg;

    // 状态：上一次处理写入的文本与标点计数
    private String lastProcessedText;
    private int lastProcessedPunctCount;

    // 预取软件包名集合，减少重复读取配置
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

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (processor == null || config == null) {
            return;
        }

        // 按来源包名过滤：只处理已勾选的聊天软件
        String pkg = event.getPackageName() == null ? "" : event.getPackageName().toString();
        if (!enabledPackages.contains(pkg)) {
            return;
        }

        // 文本变化：实时模式进入去抖；标点模式进行标点触发判断。
        if (event.getEventType() == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
            onTextChanged(pkg);
            return;
        }

        // 窗口状态/内容变化：用于初次定位输入框或更新状态
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                || event.getEventType() == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                || event.getEventType() == AccessibilityEvent.TYPE_VIEW_FOCUSED) {
            // 记录当前文本为空时清空状态
            AccessibilityNodeInfo input = findFocusedInputNode();
            if (input == null) {
                return;
            }
            CharSequence t = input.getText();
            if (t == null || t.length() == 0) {
                if (lastProcessedText != null) {
                    lastProcessedText = null;
                    lastProcessedPunctCount = 0;
                }
            }
        }
    }

    private void onTextChanged(String pkg) {
        int mode = config.getProcessingMode();
        if (mode == CatConfig.MODE_REALTIME) {
            // 实时模式：记录最新状态，重启去抖计时器
            capturePending(pkg);
            handler.removeCallbacks(debounceRunnable);
            handler.postDelayed(debounceRunnable, DEBOUNCE_MS);
        } else {
            // 标点触发：立即检查标点是否新增
            processImmediateIfPunctuation(pkg);
        }
    }

    private void capturePending(String pkg) {
        AccessibilityNodeInfo input = findFocusedInputNode();
        if (input == null) {
            pendingText = null;
            pendingPkg = pkg;
            return;
        }
        pendingText = input.getText();
        pendingPkg = pkg;
    }

    private void processIfApplicable() {
        if (pendingText == null) {
            return;
        }
        String raw = pendingText.toString();
        if (raw.isEmpty()) {
            idleReset();
            return;
        }
        // 防回声：读到的正是上次写入的内容，跳过
        if (raw.equals(lastProcessedText)) {
            return;
        }
        // 处理时重新定位输入框（避免缓存节点失效），用包名辅助过滤
        AccessibilityNodeInfo input = findFocusedInputNode();
        if (input == null) {
            return;
        }
        transformAndWrite(input, raw, pendingPkg);
    }

    private void processImmediateIfPunctuation(String pkg) {
        AccessibilityNodeInfo input = findFocusedInputNode();
        if (input == null) {
            return;
        }
        CharSequence t = input.getText();
        if (t == null || t.length() == 0) {
            idleReset();
            return;
        }
        String raw = t.toString();
        if (raw.equals(lastProcessedText)) {
            return;
        }
        int punctCount = countPunctuation(raw);
        if (punctCount == lastProcessedPunctCount) {
            return;
        }
        lastProcessedPunctCount = punctCount;
        transformAndWrite(input, raw, pkg);
    }

    private void transformAndWrite(AccessibilityNodeInfo input, String raw, String pkg) {
        String processed = processor.process(raw);
        if (processed != null && !processed.equals(raw)) {
            Log.d(TAG, "处理[" + pkg + "]: '" + raw + "' -> '" + processed + "'");
            lastProcessedText = processed;
            setNodeText(input, processed);
        }
    }

    private void idleReset() {
        lastProcessedText = null;
        lastProcessedPunctCount = 0;
    }

    /**
     * 优先查找当前聚焦的输入框；找不到则退回深度优先查找可编辑节点。
     * 这样能避免在不同的聊天软件中抓到错误的输入控件（如搜索框）。
     */
    private AccessibilityNodeInfo findFocusedInputNode() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            return null;
        }
        // 1) 优先当前聚焦的可编辑节点
        AccessibilityNodeInfo focused = dfsFocusedEditable(root, 0);
        if (focused != null) {
            return focused;
        }
        // 2) 兜底：第一个可编辑节点
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

    private int countPunctuation(String s) {
        int c = 0;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '。' || ch == '！' || ch == '？' || ch == '!' || ch == '?' || ch == '，' || ch == ',' || ch == '；' || ch == ';') {
                c++;
            }
        }
        return c;
    }

    private void setNodeText(AccessibilityNodeInfo node, String text) {
        try {
            Bundle args = new Bundle();
            args.putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
            args.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0);
            args.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, text.length());
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
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
        handler.removeCallbacks(debounceRunnable);
        Log.d(TAG, "无障碍服务解绑");
        return super.onUnbind(intent);
    }
}
