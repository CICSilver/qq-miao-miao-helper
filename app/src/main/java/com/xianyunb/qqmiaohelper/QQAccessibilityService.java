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
    private CatConfig config;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        config = new CatConfig(this);
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
                && type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                && type != AccessibilityEvent.TYPE_VIEW_CLICKED) {
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
            return;
        }
        String raw = text.toString();

        String processed = processor.process(raw);
        if (processed != null && !processed.equals(raw)) {
            Log.d(TAG, "替换: '" + raw + "' -> '" + processed + "'");
            setNodeText(input, processed);
        }
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
