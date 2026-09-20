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
    /** 上次重建缓存时的配置版本号，见 CatConfig#getVersion */
    private int cachedVersion = -1;
    /** 引擎参数也跟着缓存 —— 原来每次按键都重新读 6 项 prefs 并重解析自定义规则 */
    private MeowEngine.Config cachedCfg;
    private KaomojiLib.Pack pack;
    /** 上一次处理的软件包名，用来在切换软件时清掉封句状态 */
    private String lastPkg = "";

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

    /**
     * 按需重建词库与引擎参数。
     *
     * 这是按键热路径，每敲一个字都会走到，所以判断依据必须便宜：比一个 int。
     * 原来是把整份颜文字库读出来和缓存做字符串比较 —— 词库涨到 24 KB 之后
     * 每次按键都要读两个 raw 资源再比 24K 个字符，事件回调因此变慢，
     * 慢到一定程度系统就会把无障碍服务停用。
     */
    private void ensureLoaded() {
        int v = config.getVersion();
        if (pack != null && v == cachedVersion) {
            return;
        }
        cachedVersion = v;
        pack = KaomojiLib.Pack.of(config.getKaomojiLib(), config.getKaomojiKeywords());
        cachedCfg = processor.buildConfig();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // onAccessibilityEvent 里抛出的异常会直接搞死整个服务，系统随后
        // 把它停用 —— 用户看到的就是「用着用着无障碍自己关了，还打不开」。
        // 一次改写失败远不如整个服务活着重要。
        try {
            handleEvent(event);
        } catch (Throwable t) {
            Log.e(TAG, "处理事件时出错，已忽略", t);
            committer.reset();
        }
    }

    private void handleEvent(AccessibilityEvent event) {
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

        // 换了个聊天软件 → 上一个的冻结前缀跟这里的输入框没有关系了。
        // 内容对不上时 onTextChanged 本来也会自己重置，但那依赖「前缀刚好不匹配」，
        // 换应用这件事是确定的，直接清掉更稳。
        if (!pkg.equals(lastPkg)) {
            lastPkg = pkg;
            committer.reset();
        }

        if (event.getEventType() == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
            onTextChanged(pkg, event.getSource());
            return;
        }

        // 换窗口或换焦点 → 之前那个输入框的冻结前缀作废。
        //
        // 这里【不】去读输入框内容。原来会 findFocusedInputNode() 再看它是不是空的，
        // 那是一次整棵窗口树的深度优先遍历；而无条件 reset 的代价只是下一句重新
        // 开始封句，本来也就是想要的效果。
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                || event.getEventType() == AccessibilityEvent.TYPE_VIEW_FOCUSED) {
            committer.reset();
        }
    }

    /**
     * @param source 事件自带的、内容发生变化的那个节点。
     *               直接用它就省掉了一次整棵窗口树的遍历 —— 这是按键热路径上
     *               最贵的一步。只有拿不到或者它不可编辑时才退回去搜。
     */
    private void onTextChanged(String pkg, AccessibilityNodeInfo source) {
        AccessibilityNodeInfo input =
                (source != null && source.isEditable()) ? source : findFocusedInputNode();
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

        ensureLoaded();
        String out = committer.onTextChanged(raw, cachedCfg, pack);
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
