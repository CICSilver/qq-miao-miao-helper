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
    private Spinner spinnerMode;
    private EditText edCustom;
    private EditText edRules;
    private SwitchMaterial swMeowBefore;
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
        root.addView(makeCard("处理模式", buildModeSection()));

        // 自定义颜文字
        root.addView(makeCard("自定义替换规则", buildRulesSection()));
        root.addView(makeCard("自定义颜文字", buildCustomSection()));

        // 测试
        root.addView(makeCard("测试当前配置", buildTestSection()));

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

    private View buildStatusCardBody() {
        LinearLayout inner = new LinearLayout(this);
        inner.setOrientation(LinearLayout.VERTICAL);

        tvStatus = new TextView(this);
        tvStatus.setTextSize(15);
        tvStatus.setTypeface(null, Typeface.BOLD);
        inner.addView(tvStatus);

        Button btnOpen = roundBtn("前往开启无障碍服务", false);
        btnOpen.setOnClickListener(v -> openAccessibilitySettings());
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(46));
        p.topMargin = dp(14);
        inner.addView(btnOpen, p);
        return inner;
    }

    private View buildAppsSection() {
        LinearLayout inner = new LinearLayout(this);
        inner.setOrientation(LinearLayout.VERTICAL);
        appSwitches.clear();
        for (ChatApps app : ChatApps.values()) {
            SwitchMaterial sw = addSwitch(inner, app.getDisplayName());
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
        swMeow = addSwitch(inner, "断句加喵");
        swEmoticon = addSwitch(inner, "消息末尾随机颜文字");
        swMeowBefore = addSwitch(inner, "喵放在标点之前（真好喵！/ 真好！喵）");
        return inner;
    }

    private View buildModeSection() {
        LinearLayout inner = new LinearLayout(this);
        inner.setOrientation(LinearLayout.VERTICAL);

        spinnerMode = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item,
                new String[]{"标点触发（推荐）", "实时处理"});
        spinnerMode.setAdapter(adapter);
        inner.addView(spinnerMode);

        TextView hint = new TextView(this);
        hint.setText("标点触发：在标点处立即处理（体验更顺滑）\n实时处理：每输入一个字立即处理");
        hint.setTextSize(12);
        hint.setTextColor(0xFF99AFC5);
        hint.setPadding(0, dp(8), 0, 0);
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

    private View buildCustomSection() {
        LinearLayout inner = new LinearLayout(this);
        inner.setOrientation(LinearLayout.VERTICAL);
        edCustom = new EditText(this);
        setTransparentInput(edCustom, "每行一个颜文字，留空用内置库");
        inner.addView(edCustom);
        return inner;
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

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);

        Button btnTest = roundBtn("预览", false);
        btnTest.setOnClickListener(v -> runTest());
        row.addView(btnTest, new LinearLayout.LayoutParams(0, dp(46), 1f));

        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(0, dp(46), 1f);
        sp.setMargins(dp(12), 0, 0, 0);
        Button btnSave = roundBtn("保存设置", true);
        btnSave.setOnClickListener(v -> saveSettings());
        row.addView(btnSave, sp);
        inner.addView(row);
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

    private void loadSettings() {
        swNi.setChecked(config.isEnableNi());
        swWo.setChecked(config.isEnableWo());
        swMeow.setChecked(config.isEnableMeow());
        swEmoticon.setChecked(config.isEnableEmoticon());
        spinnerMode.setSelection(config.getProcessingMode());
        edCustom.setText(config.getCustomEmoticons());
        edRules.setText(config.getCustomRules());
        swMeowBefore.setChecked(config.isMeowBeforePunct());
        // 加载聊天软件开关
        java.util.Set<String> enabledApps = config.getEnabledApps();
        ChatApps[] apps = ChatApps.values();
        for (int i = 0; i < apps.length && i < appSwitches.size(); i++) {
            appSwitches.get(i).setChecked(enabledApps.contains(apps[i].name()));
        }
    }

    private void saveSettings() {
        config.setEnableNi(swNi.isChecked());
        config.setEnableWo(swWo.isChecked());
        config.setEnableMeow(swMeow.isChecked());
        config.setEnableEmoticon(swEmoticon.isChecked());
        config.setProcessingMode(spinnerMode.getSelectedItemPosition() == 1
                ? CatConfig.MODE_REALTIME : CatConfig.MODE_PUNCTUATION);
        config.setCustomEmoticons(edCustom.getText().toString());
        config.setCustomRules(edRules.getText().toString());
        config.setMeowBeforePunct(swMeowBefore.isChecked());
        // 保存聊天软件开关
        java.util.Set<String> enabledApps = new java.util.HashSet<>();
        ChatApps[] apps = ChatApps.values();
        for (int i = 0; i < apps.length && i < appSwitches.size(); i++) {
            if (appSwitches.get(i).isChecked()) {
                enabledApps.add(apps[i].name());
            }
        }
        config.setEnabledApps(enabledApps);
        Toast.makeText(this, "设置已保存", Toast.LENGTH_SHORT).show();
        updateStatus();
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
        cfg.kaomoji = MeowEngine.parseKaomoji(
                edCustom.getText().toString(),
                CatConfig.BUILTIN_EMOTICONS.toArray(new String[0]));
        return cfg;
    }

    private void runTest() {
        String raw = edTest.getText().toString();
        if (raw.isEmpty()) {
            Toast.makeText(this, "请先输入要测试的文字", Toast.LENGTH_SHORT).show();
            return;
        }
        // 用界面上的当前状态预览，而不是已保存的配置 ——
        // 否则「先测试、满意再保存」这个流程根本走不通。
        String result = MeowEngine.transform(raw, configFromUi());
        tvPreview.setText("原始：\n" + raw + "\n\n处理后：\n" + result);
    }

    private void updateStatus() {
        boolean on = isAccessibilityEnabled();
        if (on) {
            tvStatus.setText("● 服务状态：已开启");
            tvStatus.setTextColor(0xFF2ECC71);
        } else {
            tvStatus.setText("○ 服务状态：未开启");
            tvStatus.setTextColor(0xFFE74C3C);
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
