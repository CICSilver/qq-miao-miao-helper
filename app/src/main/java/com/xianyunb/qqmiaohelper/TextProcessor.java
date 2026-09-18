package com.xianyunb.qqmiaohelper;

import java.util.ArrayList;
import java.util.List;

/**
 * 配置 → 引擎的适配层。真正的文本变换在 {@link MeowEngine}。
 *
 * 拆开是为了让引擎不依赖 Android，可以单独测试 —— 原版把正则、随机、
 * 配置读取全糊在一个类里，任何一处改动都只能靠装到手机上肉眼验。
 */
public class TextProcessor {

    private final CatConfig config;

    public TextProcessor(CatConfig config) {
        this.config = config;
    }

    /**
     * 按当前配置组装引擎参数。
     *
     * 规则顺序：你/我 两个内置开关在前，自定义规则在后。
     * 同一个原文出现两次时后者覆盖前者，所以用户可以用自定义规则
     * 覆盖掉内置的「你 = 主人」。
     */
    public MeowEngine.Config buildConfig() {
        MeowEngine.Config cfg = new MeowEngine.Config();

        List<MeowEngine.Rule> rules = new ArrayList<>();
        if (config.isEnableNi()) {
            rules.add(new MeowEngine.Rule("你", "主人"));
        }
        if (config.isEnableWo()) {
            rules.add(new MeowEngine.Rule("我", "本喵"));
        }
        rules.addAll(MeowEngine.parseRules(config.getCustomRules()));
        cfg.rules = rules;

        cfg.enableSuffix = config.isEnableMeow();
        cfg.suffixBeforePunct = config.isMeowBeforePunct();

        cfg.enableKaomoji = config.isEnableEmoticon();
        cfg.kaomoji = MeowEngine.parseKaomoji(
                config.getCustomEmoticons(),
                CatConfig.BUILTIN_EMOTICONS.toArray(new String[0]));

        return cfg;
    }

    /**
     * 一次性变换，供主界面「测试当前配置」使用。
     *
     * 注意：输入框的实时改写走 {@link MeowRewriter}，不要直接用这个方法 ——
     * 它每次都从头变换，反复作用在自己的输出上会让自指规则（我们 = 我们这群喵）无限膨胀。
     */
    public String process(String raw) {
        return MeowEngine.transform(raw, buildConfig());
    }
}
