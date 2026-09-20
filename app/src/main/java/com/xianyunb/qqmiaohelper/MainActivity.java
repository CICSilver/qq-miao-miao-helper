package com.xianyunb.qqmiaohelper;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.BlurMaskFilter;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Path;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.RoundRectShape;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.accessibility.AccessibilityManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.switchmaterial.SwitchMaterial;

import java.util.List;

/**
 * 主界面：控制面板，蓝鲸公益 (LJGY) 玻璃拟态风格。
 */
public class MainActivity extends AppCompatActivity {

    private static final int COLOR_PRIMARY = 0xFF3498DB;
    private static final int COLOR_DEEP = 0xFF2980B9;
    private static final int COLOR_TEXT = 0xFF1A1A2E;
    private static final int COLOR_BODY = 0xFF2D2D44;
    private static final int COLOR_GLASS = 0x40FFFFFF;

    private CatConfig config;

    private SwitchMaterial swNi;
    private SwitchMaterial swWo;
    private SwitchMaterial swMeow;
    private SwitchMaterial swEmoticon;
    private EditText edKaomojiLib;
    private EditText edKaomojiKw;
    private EditText edRules;
    private SwitchMaterial swMeowBefore;
    private SwitchMaterial swMaster;
    private Button btnAccess;
    /** 正在把配置回填到界面上：此时 setChecked 触发的回调应当忽略 */
    private boolean bindingUi;
    private EditText edTest;
    private TextView tvStatus;
    private TextView tvPreview;
    // 聊天软件多选开关（与 ChatApps.values() 顺序对应）
    private final java.util.List<SwitchMaterial> appSwitches = new java.util.ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setupWindow();
        config = new CatConfig(this);
        setContentView(buildUi());
        loadSettings();
        updateStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatus();
    }

    private void setupWindow() {
        Window w = getWindow();
        w.setStatusBarColor(Color.TRANSPARENT);
        w.setNavigationBarColor(Color.TRANSPARENT);
        w.setBackgroundDrawable(makeBackground());
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackground(makeBackground());

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(30), dp(20), dp(30));

        // 标题
        TextView logo = new TextView(this);
        logo.setText("QQ喵喵助手");
        logo.setTextSize(30);
        logo.setTypeface(null, Typeface.BOLD);
        logo.setTextColor(Color.WHITE);
        logo.setGravity(Gravity.CENTER);
        root.addView(logo);

        TextView subtitle = new TextView(this);
        subtitle.setText("蓝鲸公益 · 让聊天变成猫猫口气 ♪");
        subtitle.setTextSize(14);
        subtitle.setTextColor((int) 0xB3FFFFFF);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setPadding(0, dp(6), 0, dp(24));
        root.addView(subtitle);

        // 服务卡片 1：状态
        root.addView(makeCard("服务状态", buildStatusCardBody()));

        // 聊天软件
        root.addView(makeCard("聊天软件", buildAppsSection()));

        // 功能开关
        root.addView(makeCard("功能开关", buildSwitches()));

        // 处理模式

        // 自定义颜文字
        root.addView(makeCard("自定义替换规则", buildRulesSection()));
        root.addView(makeCard("颜文字库", buildKaomojiLibSection()));
        root.addView(makeCard("颜文字关键词", buildKaomojiKwSection()));

        // 测试
        root.addView(makeCard("测试当前配置", buildTestSection()));

        root.addView(makeCard("如何彻底停用", buildStopSection()));

        // 铭牌
        TextView footer = new TextView(this);
        footer.setText("Powered by LJGY · 蓝鲸公益");
        footer.setTextSize(12);
        footer.setTextColor(0xFF99AFC5);
        footer.setGravity(Gravity.CENTER);
        footer.setPadding(0, dp(24), 0, dp(10));
        root.addView(footer);

        scroll.addView(root);
        return scroll;
    }

    private View buildStopSection() {
        LinearLayout inner = new LinearLayout(this);
        inner.setOrientation(LinearLayout.VERTICAL);
        TextView tv = new TextView(this);
        tv.setText("暂停：关掉上方的总开关，或下拉通知栏点「喵化开关」。\n\n彻底停止：系统设置 → 无障碍 → 已安装的服务 → QQ喵喵助手 → 关闭。\n\n完全移除：直接卸载本应用。\n\n注意：卸载 QQ 没有任何作用。改写输入框的是本应用的无障碍服务，它不属于 QQ，卸载 QQ 不会把它一起带走。");
        tv.setTextSize(13);
        tv.setTextColor(0xFFD8E4EE);
        tv.setLineSpacing(0f, 1.3f);
        inner.addView(tv);
        return inner;
    }

    private View buildStatusCardBody() {
        LinearLayout inner = new LinearLayout(this);
        inner.setOrientation(LinearLayout.VERTICAL);

        tvStatus = new TextView(this);
        tvStatus.setTextSize(15);
        tvStatus.setTypeface(null, Typeface.BOLD);
        inner.addView(tvStatus);

        // 总开关：立即生效，不需要点「保存设置」。
        // 这是「暂停」的入口 —— 之前只有分项开关，想停下来没有顺手的办法。
        swMaster = addSwitch(inner, "启用喵化（关闭即暂停）");
        swMaster.setOnCheckedChangeListener((btn, checked) -> {
            if (bindingUi) {
                return;   // 回填界面时不要当成用户操作
            }
            config.setMasterEnabled(checked);
            Toast.makeText(this, checked ? "已启用" : "已暂停", Toast.LENGTH_SHORT).show();
            updateStatus();
        });

        TextView hint = new TextView(this);
        hint.setText("暂停只是让服务不再改写文字，无障碍权限仍然授予着。"
                + "要彻底停止，见下方「如何彻底停用」。");
        hint.setTextSize(12);
        hint.setTextColor(0xFF99AFC5);
        hint.setPadding(0, dp(6), 0, 0);
        inner.addView(hint);

        btnAccess = roundBtn("前往无障碍设置", false);
        btnAccess.setOnClickListener(v -> openAccessibilitySettings());
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(46));
        p.topMargin = dp(14);
        inner.addView(btnAccess, p);
        return inner;
    }

    private View buildAppsSection() {
        LinearLayout inner = new LinearLayout(this);
        inner.setOrientation(LinearLayout.VERTICAL);
        appSwitches.clear();
        for (ChatApps app : ChatApps.values()) {
            SwitchMaterial sw = addSwitch(inner, app.getDisplayName());
            sw.setOnCheckedChangeListener((b, c) -> saveEnabledApps());
            appSwitches.add(sw);
        }
        TextView hint = new TextView(this);
        hint.setText("勾选要启用喵喵语气的聊天软件");
        hint.setTextSize(12);
        hint.setTextColor(0xFF99AFC5);
        hint.setPadding(0, dp(6), 0, 0);
        inner.addView(hint);
        return inner;
    }

    private View buildSwitches() {
        LinearLayout inner = new LinearLayout(this);
        inner.setOrientation(LinearLayout.VERTICAL);
        swNi = addSwitch(inner, "将「你」替换为「主人」");
        swWo = addSwitch(inner, "将「我」替换为「本喵」");
        swMeow = addSwitch(inner, "句尾加喵");
        swEmoticon = addSwitch(inner, "每句末尾配一个颜文字");
        swMeowBefore = addSwitch(inner, "喵放在标点之前（真好喵！/ 真好！喵）");

        // 即时生效，不需要「保存设置」
        onToggle(swNi, config::setEnableNi);
        onToggle(swWo, config::setEnableWo);
        onToggle(swMeow, config::setEnableMeow);
        onToggle(swEmoticon, config::setEnableEmoticon);
        onToggle(swMeowBefore, config::setMeowBeforePunct);

        TextView hint = new TextView(this);
        hint.setText("提示：中文句号「。」是确认键 —— 打字过程中输入框不会变，"
                + "打出句号才会把这句转成喵喵语气，句号本身会被吃掉。"
                + "「？」「！」也会触发，但会保留。");
        hint.setTextSize(12);
        hint.setTextColor(0xFF99AFC5);
        hint.setPadding(0, dp(10), 0, 0);
        inner.addView(hint);
        return inner;
    }


    private View buildRulesSection() {
        LinearLayout inner = new LinearLayout(this);
        inner.setOrientation(LinearLayout.VERTICAL);
        edRules = new EditText(this);
        setTransparentInput(edRules, "每行一条，格式：原文=替换\n例如  我们=我们这群喵\n留空则只用上面的两个开关");
        inner.addView(edRules);

        TextView tip = new TextView(this);
        tip.setText("规则按最长优先匹配，且只扫描一趟 —— 「我们=我们这群喵」不会把自己的结果再替换一遍。");
        tip.setTextSize(12);
        tip.setTextColor(0xFF99AFC5);
        tip.setPadding(0, dp(8), 0, 0);
        inner.addView(tip);
        return inner;
    }

    private View buildKaomojiLibSection() {
        LinearLayout inner = new LinearLayout(this);
        inner.setOrientation(LinearLayout.VERTICAL);
        edKaomojiLib = new EditText(this);
        setTransparentInput(edKaomojiLib, "留空使用内置库");
        edKaomojiLib.setText(config.getKaomojiLib());
        edKaomojiLib.addTextChangedListener(watcher(
                () -> config.setKaomojiLib(edKaomojiLib.getText().toString())));
        inner.addView(edKaomojiLib);
        inner.addView(hintText("格式：标签1,标签2 = 颜文字\n一个颜文字可挂多个标签，标签是自由文本，加新情绪不用改代码。\n注意标签必须写在左边 —— 颜文字自己就含 = （猫脸的眼睛）。"));
        inner.addView(resetButton("恢复内置颜文字库", () -> {
            edKaomojiLib.setText(config.getDefaultKaomojiLib());
            config.setKaomojiLib("");
        }));
        return inner;
    }

    private View buildKaomojiKwSection() {
        LinearLayout inner = new LinearLayout(this);
        inner.setOrientation(LinearLayout.VERTICAL);
        edKaomojiKw = new EditText(this);
        setTransparentInput(edKaomojiKw, "留空使用内置关键词表");
        edKaomojiKw.setText(config.getKaomojiKeywords());
        edKaomojiKw.addTextChangedListener(watcher(
                () -> config.setKaomojiKeywords(edKaomojiKw.getText().toString())));
        inner.addView(edKaomojiKw);
        inner.addView(hintText("格式：关键词 = 情绪标签\n右边留空 = 排除短语（命中即弃权，交给标点判断）。\n匹配用最长优先，所以「别难过」会盖过「难过」。"));
        inner.addView(resetButton("恢复内置关键词表", () -> {
            edKaomojiKw.setText(config.getDefaultKaomojiKeywords());
            config.setKaomojiKeywords("");
        }));
        return inner;
    }

    /** 文本框改动即存，不需要「保存设置」 */
    private android.text.TextWatcher watcher(Runnable onChange) {
        return new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(android.text.Editable e) {
                if (!bindingUi) {
                    onChange.run();
                }
            }
        };
    }

    private TextView hintText(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(12);
        tv.setTextColor(0xFF99AFC5);
        tv.setPadding(0, dp(8), 0, 0);
        return tv;
    }

    private View resetButton(String label, Runnable action) {
        Button b = roundBtn(label, false);
        b.setOnClickListener(v -> {
            bindingUi = true;
            action.run();
            bindingUi = false;
            Toast.makeText(this, "已恢复默认", Toast.LENGTH_SHORT).show();
        });
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(42));
        p.topMargin = dp(10);
        b.setLayoutParams(p);
        return b;
    }

    private View buildTestSection() {
        LinearLayout inner = new LinearLayout(this);
        inner.setOrientation(LinearLayout.VERTICAL);
        edTest = new EditText(this);
        setTransparentInput(edTest, "例如：你去上班了吗");
        inner.addView(edTest);

        tvPreview = new TextView(this);
        tvPreview.setTextSize(14);
        tvPreview.setTextColor(Color.WHITE);
        tvPreview.setPadding(0, dp(10), 0, dp(12));
        inner.addView(tvPreview);

        // 没有「保存设置」按钮：所有配置改动即时生效，与顶部总开关一致。
        Button btnTest = roundBtn("预览", true);
        btnTest.setOnClickListener(v -> runTest());
        inner.addView(btnTest, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(46)));
        return inner;
    }

    private View makeCard(String title, View body) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(22), dp(20), dp(22), dp(20));
        card.setBackground(glassDrawable());

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(16);
        t.setTypeface(null, Typeface.BOLD);
        t.setTextColor(COLOR_TEXT);
        t.setPadding(0, 0, 0, dp(16));
        card.addView(t);

        if (body != null) {
            card.addView(body);
        }

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(6), 0, dp(18));
        card.setLayoutParams(lp);
        return card;
    }

    private SwitchMaterial addSwitch(LinearLayout parent, String label) {
        SwitchMaterial sw = new SwitchMaterial(this);
        sw.setText(label);
        sw.setTextSize(15);
        sw.setTextColor(COLOR_BODY);
        sw.setPadding(0, dp(8), 0, dp(8));
        parent.addView(sw, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return sw;
    }

    private void setTransparentInput(EditText ed, String hint) {
        ed.setHint(hint);
        ed.setHintTextColor(0xFF9FB3C8);
        ed.setTextSize(15);
        ed.setTextColor(Color.WHITE);
        ed.setBackground(roundBg(COLOR_GLASS, dp(14)));
        ed.setPadding(dp(16), dp(12), dp(16), dp(12));
    }

    private Button roundBtn(String text, boolean primary) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(15);
        b.setTypeface(null, Typeface.BOLD);
        if (primary) {
            b.setBackground(roundSolid(COLOR_PRIMARY, dp(46)));
            b.setTextColor(Color.WHITE);
        } else {
            b.setBackground(roundStroke(COLOR_PRIMARY, COLOR_PRIMARY, dp(46)));
            b.setTextColor(COLOR_PRIMARY);
        }
        b.setAllCaps(false);
        return b;
    }

    private GradientDrawable glassDrawable() {
        GradientDrawable d = new GradientDrawable();
        d.setColor(COLOR_GLASS);
        d.setCornerRadius(dp(20));
        d.setStroke(dp(1), 0x4DFFFFFF);
        return d;
    }

    private GradientDrawable roundBg(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }

    private GradientDrawable roundSolid(int color, int h) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(40));
        return d;
    }

    private GradientDrawable roundStroke(int stroke, int fill, int h) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(Color.TRANSPARENT);
        d.setStroke(dp(2), stroke);
        d.setCornerRadius(dp(40));
        return d;
    }

    private android.graphics.drawable.Drawable makeBackground() {
        GradientDrawable d = new GradientDrawable();
        d.setOrientation(GradientDrawable.Orientation.TL_BR);
        d.setColors(new int[]{0xFF1B4A78, 0xFF17406B, 0xFF12324F});
        return d;
    }

    /**
     * 开关改动即存；回填界面时不算用户操作。
     *
     * 不用 java.util.function.Consumer —— 它是 API 24+，本项目 minSdk 是 23
     * 且未开启 core library desugaring，会在 Android 6.0 上抛 NoSuchMethodError。
     */
    private interface BoolSetter {
        void set(boolean value);
    }

    private void onToggle(SwitchMaterial sw, BoolSetter setter) {
        sw.setOnCheckedChangeListener((b, checked) -> {
            if (!bindingUi) {
                setter.set(checked);
            }
        });
    }

    private void loadSettings() {
        bindingUi = true;
        swNi.setChecked(config.isEnableNi());
        swWo.setChecked(config.isEnableWo());
        swMeow.setChecked(config.isEnableMeow());
        swEmoticon.setChecked(config.isEnableEmoticon());
        edRules.setText(config.getCustomRules());
        swMeowBefore.setChecked(config.isMeowBeforePunct());
        if (swMaster != null) {
            swMaster.setChecked(config.isMasterEnabled());
        }
        // 加载聊天软件开关
        java.util.Set<String> enabledApps = config.getEnabledApps();
        ChatApps[] apps = ChatApps.values();
        for (int i = 0; i < apps.length && i < appSwitches.size(); i++) {
            appSwitches.get(i).setChecked(enabledApps.contains(apps[i].name()));
        }
        bindingUi = false;
    }

    /** 聊天软件勾选项即时保存 */
    private void saveEnabledApps() {
        if (bindingUi) {
            return;
        }
        java.util.Set<String> enabled = new java.util.HashSet<>();
        ChatApps[] apps = ChatApps.values();
        for (int i = 0; i < apps.length && i < appSwitches.size(); i++) {
            if (appSwitches.get(i).isChecked()) {
                enabled.add(apps[i].name());
            }
        }
        config.setEnabledApps(enabled);
    }

    /** 按界面当前（可能尚未保存的）状态组装引擎参数。 */
    private MeowEngine.Config configFromUi() {
        MeowEngine.Config cfg = new MeowEngine.Config();
        java.util.List<MeowEngine.Rule> rules = new java.util.ArrayList<>();
        if (swNi.isChecked()) {
            rules.add(new MeowEngine.Rule("你", "主人"));
        }
        if (swWo.isChecked()) {
            rules.add(new MeowEngine.Rule("我", "本喵"));
        }
        rules.addAll(MeowEngine.parseRules(edRules.getText().toString()));
        cfg.rules = rules;
        cfg.enableSuffix = swMeow.isChecked();
        cfg.suffixBeforePunct = swMeowBefore.isChecked();
        cfg.enableKaomoji = swEmoticon.isChecked();
        return cfg;
    }

    private void runTest() {
        String raw = edTest.getText().toString();
        if (raw.isEmpty()) {
            Toast.makeText(this, "请先输入要测试的文字", Toast.LENGTH_SHORT).show();
            return;
        }
        // 走和真实场景完全相同的封句路径（句号确认键 + 冻结前缀），
        // 这样预览里看到的就是实际会发生的事，包括颜文字的挑选。
        java.util.List<KaomojiLib.Entry> lib =
                KaomojiLib.parseLib(edKaomojiLib.getText().toString());
        if (lib.isEmpty()) {
            lib = KaomojiLib.fallbackLib();
        }
        java.util.List<KaomojiLib.KeywordRule> kws =
                KaomojiLib.parseKeywords(edKaomojiKw.getText().toString());

        MeowCommitter c = new MeowCommitter();
        MeowEngine.Config cfg = configFromUi();
        String result = raw;
        String out = c.onTextChanged(raw, cfg, lib, kws);
        if (out != null) {
            result = out;
        }
        tvPreview.setText("原始：\n" + raw + "\n\n发出去会是：\n" + result
                + (out == null ? "\n\n（没有中文句号，所以不会触发）" : ""));
    }

    private void updateStatus() {
        boolean granted = isAccessibilityEnabled();
        boolean running = granted && config.isMasterEnabled();

        if (!granted) {
            tvStatus.setText("○ 未开启：尚未授予无障碍权限");
            tvStatus.setTextColor(0xFFE74C3C);
        } else if (!running) {
            tvStatus.setText("⏸ 已暂停：权限已授予，但不会改写文字");
            tvStatus.setTextColor(0xFFF39C12);
        } else {
            tvStatus.setText("● 运行中：发送前会自动喵化");
            tvStatus.setTextColor(0xFF2ECC71);
        }

        if (btnAccess != null) {
            btnAccess.setText(granted ? "前往系统设置（可在此彻底关闭）" : "前往开启无障碍服务");
        }
        if (swMaster != null && swMaster.isChecked() != config.isMasterEnabled()) {
            bindingUi = true;
            swMaster.setChecked(config.isMasterEnabled());
            bindingUi = false;
        }
    }

    private boolean isAccessibilityEnabled() {
        AccessibilityManager am =
                (AccessibilityManager) getSystemService(Context.ACCESSIBILITY_SERVICE);
        if (am == null) {
            return false;
        }
        String expected = getPackageName() + "/" + QQAccessibilityService.class.getName();
        List<AccessibilityServiceInfo> services = am.getEnabledAccessibilityServiceList(
                AccessibilityServiceInfo.FEEDBACK_GENERIC);
        for (AccessibilityServiceInfo info : services) {
            if (info.getResolveInfo() != null && info.getResolveInfo().serviceInfo != null) {
                String id = new android.content.ComponentName(
                        info.getResolveInfo().serviceInfo.packageName,
                        info.getResolveInfo().serviceInfo.name).flattenToString();
                if (id.equals(expected)) {
                    return true;
                }
            }
        }
        return false;
    }

    private void openAccessibilitySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        } catch (Exception e) {
            Toast.makeText(this, "无法打开设置", Toast.LENGTH_SHORT).show();
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
