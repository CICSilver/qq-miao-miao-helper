package com.xianyunb.qqmiaohelper;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

/**
 * 无障碍服务：监听 QQ 聊天界面的输入框。
 * 当用户输入文本（或文本变化）时，自动将文本转换为喵喵语气并回写输入框。
 */
public class QQAccessibilityService extends AccessibilityService {

    private static final String TAG = "QQMiao";
    private static final int MAX_DEPTH = 30;

    private TextProcessor processor;
    // 上一次我们处理并写入的文本。用于两件事：
    // 1. 防回声：setText 触发的事件若读到的文本==此值，说明是回显，跳过。
    // 2. 标点触发：仅在文本比这里"新增了标点"时才处理。
    private String lastProcessedText;
    private int lastProcessedPunctCount;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        CatConfig config = new CatConfig(this);
        processor = new TextProcessor(config);
        Log.d(TAG, "无障碍服务已连接");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (processor == null) {
            return;
        }

        int type = event.getEventType();
        if (type != AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
                && type != AccessibilityEvent.TYPE_VIEW_FOCUSED
                && type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            return;
        }

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            return;
        }

        AccessibilityNodeInfo input = findEditableNode(root);
        if (input == null) {
            return;
        }

        CharSequence text = input.getText();
        if (text == null || text.length() == 0) {
            // 清空输入框时重置状态
            if (lastProcessedText != null) {
                lastProcessedText = null;
                lastProcessedPunctCount = 0;
            }
            return;
        }
        String raw = text.toString();

        // 防回声：读到的正是上次写入的内容，跳过
        if (raw.equals(lastProcessedText)) {
            return;
        }

        int mode = getProcessingMode();
        if (mode == CatConfig.MODE_PUNCTUATION) {
            // 标点触发：仅在文本新增标点时处理，打字阶段不处理
            int punctCount = countPunctuation(raw);
            if (punctCount == lastProcessedPunctCount) {
                return;
            }
            lastProcessedPunctCount = punctCount;
        }
        // 实时模式：直接处理（只靠防回声避免循环）

        String processed = processor.process(raw);
        if (processed != null && !processed.equals(raw)) {
            Log.d(TAG, "处理: '" + raw + "' -> '" + processed + "'");
            lastProcessedText = processed;
            setNodeText(input, processed);
        }
    }

    /**
     * 读取当前处理模式（每次事件读取，便于主界面改动即时生效）。
     */
    private int getProcessingMode() {
        try {
            return new CatConfig(this).getProcessingMode();
        } catch (Exception e) {
            return CatConfig.MODE_PUNCTUATION;
        }
    }

    /**
     * 统计文本中出现的标点（。！？!?等）数量，用于标点触发判断。
     */
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

    /**
     * 深度优先查找处于可编辑状态的输入框节点。
     */
    private AccessibilityNodeInfo findEditableNode(AccessibilityNodeInfo node) {
        return dfsEditable(node, 0);
    }

    private AccessibilityNodeInfo dfsEditable(AccessibilityNodeInfo node, int depth) {
        if (node == null || depth > MAX_DEPTH) {
            return null;
        }
        if (node.isEditable()) {
            return node;
        }
        if (node.isFocused() && isEditText(node)) {
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

    private boolean isEditText(AccessibilityNodeInfo node) {
        CharSequence cn = node.getClassName();
        return cn != null && cn.toString().toLowerCase().contains("edittext");
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
        Log.d(TAG, "无障碍服务解绑");
        return super.onUnbind(intent);
    }
}
