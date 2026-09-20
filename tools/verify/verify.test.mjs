/**
 * 引擎逻辑验证。
 *
 * 这里跑的是 MeowEngine / KaomojiLib / MeowCommitter 三个 Java 类的
 * 逐行 JS 转写。验证的是【逻辑】，不是 Java 代码本身 —— 改了 Java
 * 必须同步改转写，否则测试就失去意义。
 */

import { test, describe } from 'node:test';
import assert from 'node:assert/strict';
import { transform, parseRules, splitSentences, applyReplacements, isSelfReferential } from './transcribe.mjs';
import * as K from './kaomojilib.mjs';
import { MeowCommitter } from './committer.mjs';

const RULES = parseRules('我们=我们这群喵\n我=本喵\n你=主人');

const CFG = {
  suffix: '喵', suffixBeforePunct: true, enableSuffix: true,
  maxSuffixPerMessage: 0, minSentenceLength: 2,
  rules: RULES, enableKaomoji: false, protectRegions: true,
};

const LIB_RAW = `
[平静 | 句号,问号,叹号,省略号]
(=^･ω･^=) | (=^.w.^=)
(=^･^=)

[开心,兴奋 | 叹号]
ヽ(=^･ω･^=)丿 | \(=^w^=)/

[困惑 | 句号,问号,省略号]
(=ʘωʘ=)

[困惑 | 问号]
(=･･=)?

[惊讶 | 叹号,问叹,问号]
(=ﾟдﾟ=)

[感激 | 句号,叹号]
(=^･ω･^=)♡

[感激,兴奋 | 叹号]
ゾ(=^･ω･^=)ノ

[安慰 | 句号,叹号,省略号]
(づ=^･ω･^=)づ

[难过,委屈 | 句号,问号,叹号,省略号]
(=；ω；=)

[无奈,困倦 | 句号,省略号]
(=＿ω＿=)
(=_ _=)

@default 平静

@merge 困惑 + 叹号 = 惊讶
@merge 困惑 + 问叹 = 惊讶
@merge * + 问叹 = 惊讶
`;

const KW_RAW = `
谢谢 = 感激
别难过 = 安慰
不开心 = 难过
难过 = 难过
开心 = 开心
不喜欢 =
喜欢 = 喜欢
什么意思 = 困惑
好累 = 困倦
真的假的 = 困惑
`;

const PACK = K.pack(LIB_RAW, KW_RAW);
const LIB = PACK.lib;
const KWS = PACK.keywords;

/** 按颜文字文本反查它在库里的条目 */
const entryOf = (kao) => LIB.find((e) => e.kao === kao);

/** 模拟输入框：按步骤喂给 committer，返回最终内容与写回次数 */
function play(steps, cfg = CFG, pack = PACK) {
  const c = new MeowCommitter();
  let box = '';
  let writes = 0;
  for (const s of steps) {
    if (typeof s === 'string') box += s;
    else if (s.back) box = box.slice(0, -s.back);
    const out = c.onTextChanged(box, cfg, pack);
    if (out !== null) { box = out; writes++; }
  }
  return { box, writes };
}

// ════════════════════════════════════ 引擎核心

describe('保护区', () => {
  test('链接不被破坏', () => {
    const out = transform('看这个 https://docs.qq.com/doc/abc 你说呢', CFG);
    assert.ok(out.includes('https://docs.qq.com/doc/abc'), out);
    assert.ok(out.includes('主人说呢喵'), out);
  });

  test('邮箱、小数、@提及、表情码、代码', () => {
    assert.ok(transform('邮箱 abc.def@qq.com', CFG).includes('abc.def@qq.com'));
    assert.ok(transform('单价 3.14 元', CFG).includes('3.14'));
    assert.ok(transform('@你好呀 在吗', CFG).startsWith('@你好呀'));
    assert.ok(transform('哈哈[你好]走', CFG).includes('[你好]'));
    assert.ok(transform('执行 `npm test` 吧', CFG).includes('`npm test`'));
  });

  test('纯链接消息完全不动', () => {
    const url = 'https://github.com/foo/bar';
    assert.equal(transform(url, CFG), url);
  });
});

describe('替换与断句', () => {
  test('单趟扫描，产物不被后续规则再改', () => {
    const r = parseRules('我=本喵\n喵=呜');
    assert.equal(transform('我', { ...CFG, rules: r, enableSuffix: false }), '本喵');
  });

  test('最长优先', () => {
    assert.ok(transform('我们走吧', { ...CFG, enableSuffix: false }).startsWith('我们这群喵'));
  });

  test('喵插在标点之前', () => {
    assert.equal(transform('今天真好', { ...CFG, rules: [] }), '今天真好喵');
  });

  test('已有喵不重复加', () => {
    assert.equal(transform('你好喵', { ...CFG, rules: [] }), '你好喵');
  });

  test('断句无损', () => {
    for (const c of ['a。b！！c', '你好\n世界', '！！！', 'abc', '']) {
      assert.equal(splitSentences(c).map((s) => s.body + s.punct).join(''), c);
    }
  });

  test('识别自指规则', () => {
    assert.equal(isSelfReferential({ from: '我们', to: '我们这群喵' }), true);
    assert.equal(isSelfReferential({ from: '我', to: '本喵' }), false);
  });
});

// ════════════════════════════════════ 触发规则

describe('只有中文句号/问号/叹号触发', () => {
  test('中文句号触发并被吃掉', () => {
    assert.equal(play(['我今天很开心。']).box, '本喵今天很开心喵');
  });

  test('中文问号、叹号触发并保留', () => {
    assert.equal(play(['你在吗？']).box, '主人在吗喵？');
    assert.equal(play(['太好了！']).box, '太好了喵！');
  });

  test('英文标点一律不触发', () => {
    for (const s of ['我今天很开心,', '我今天很开心.', '你在吗?', '太好了!', '我不知道...']) {
      assert.equal(play([s]).box, s, '不该触发: ' + s);
    }
  });

  test('回车与中文逗号不触发', () => {
    assert.equal(play(['第一行\n第二行']).box, '第一行\n第二行');
    assert.equal(play(['我今天很开心，你呢']).box, '我今天很开心，你呢');
  });
});

describe('打字过程零打扰', () => {
  test('逐字打字只在句号那一下写回', () => {
    const r = play(['我', '今', '天', '很', '开', '心', '。']);
    assert.equal(r.writes, 1);
    assert.equal(r.box, '本喵今天很开心喵');
  });

  test('逐词打字同样只写回一次', () => {
    const r = play(['我们', '明天', '一起', '去看', '电影', '好吗', '。']);
    assert.equal(r.writes, 1);
  });

  test('没有终止符时一次都不写', () => {
    assert.equal(play(['我们明天一起去看电影好吗']).writes, 0);
  });
});

describe('冻结前缀', () => {
  test('后续句子不影响已封存的句子', () => {
    const r = play(['我今天很开心。', '你呢？']);
    assert.equal(r.box, '本喵今天很开心喵主人呢喵？');
  });

  test('封句后继续打字不重复加喵', () => {
    const r = play(['我今天很开心。', '啊哈', '。']);
    assert.equal(r.box, '本喵今天很开心喵啊哈喵');
  });

  test('删进冻结区会重置', () => {
    const r = play(['我今天很开心。', { back: 1 }, '啊', '。']);
    assert.ok(!/喵[^喵]*喵[^喵]*喵[^喵]*喵/.test(r.box), '喵过多: ' + r.box);
  });
});

describe('？！ 回滚重做', () => {
  test('打？再打！ 变成惊讶版', () => {
    const c = new MeowCommitter();
    let box = '真的假的';
    box = c.onTextChanged(box, CFG, PACK) ?? box;
    box += '？';
    box = c.onTextChanged(box, CFG, PACK) ?? box;
    assert.equal(box, '真的假的喵？');
    box += '！';
    const out = c.onTextChanged(box, CFG, PACK);
    assert.equal(out, '真的假的喵？！', '没有回滚重做');
  });

  test('反向 ！? 也认', () => {
    const c = new MeowCommitter();
    let box = '什么！';
    box = c.onTextChanged(box, CFG, PACK) ?? box;
    box += '？';
    assert.equal(c.onTextChanged(box, CFG, PACK), '什么喵！？');
  });

  test('打？之后继续打别的字不回滚', () => {
    const r = play(['你在吗？', '我等你', '。']);
    // 第一句封存为疑问版且不被后续影响；第二句独立封存，句号被吃掉
    assert.equal(r.box, '主人在吗喵？本喵等主人喵');
  });
});

// ════════════════════════════════════ 颜文字

describe('颜文字：关键词扫描', () => {
  test('最长优先：别难过 盖过 难过', () => {
    assert.equal(K.scanLastKeyword('别难过，我陪着你', KWS).key, '别难过');
    assert.equal(K.scanLastKeyword('今天有点不开心', KWS).key, '不开心');
  });

  test('最后命中：转折后面的赢', () => {
    assert.equal(K.scanLastKeyword('刚才还有点难过，不过现在很开心', KWS).key, '开心');
  });

  test('排除短语弃权', () => {
    assert.equal(K.scanLastKeyword('我不喜欢', KWS), null);
  });

  test('排除短语只吃掉自己，后面的仍生效', () => {
    assert.equal(K.scanLastKeyword('我不喜欢，好难过', KWS).key, '难过');
  });
});

describe('颜文字：标签交集', () => {
  test('谢谢！取【感激】且【配得上叹号】的脸', () => {
    const e = entryOf(K.select('谢谢', '！', PACK, null));
    assert.ok(e.tags.includes('感激'), e.kao);
    assert.ok(e.symbols.includes('叹号'), e.kao);
  });

  test('交集为空时保情绪、舍句尾', () => {
    // 感激组没配问号的脸，也没有对应的合成规则 → 退回情绪组
    const e = entryOf(K.select('谢谢', '？', PACK, null));
    assert.ok(e.tags.includes('感激'), '应当保情绪标签: ' + e.kao);
  });

  test('没有关键词时只看句尾符号', () => {
    const e = entryOf(K.select('你在吗', '？', PACK, null));
    assert.ok(e.symbols.includes('问号'), e.kao);
  });

  test('避开上次用过的那个', () => {
    const first = K.select('你在吗', '？', PACK, null);
    const second = K.select('你在吗', '？', PACK, first);
    assert.notEqual(second, first);
  });

  test('同一句在同样条件下结果稳定', () => {
    const a = K.select('我今天很开心', '', PACK, null);
    for (let i = 0; i < 10; i++) {
      assert.equal(K.select('我今天很开心', '', PACK, null), a);
    }
  });
});

describe('颜文字：与标点的合并', () => {
  test('颜文字自带问号时吃掉句尾问号', () => {
    assert.equal(K.assemble('在吗喵', '？', '(=･ｪ･=)?'), '在吗喵 (=･ｪ･=)?');
  });

  test('颜文字不带问号时保留句尾问号', () => {
    assert.equal(K.assemble('在吗喵', '？', '(=ʘωʘ=)'), '在吗喵？ (=ʘωʘ=)');
  });

  test('组合标点一律保留', () => {
    assert.equal(K.assemble('真的喵', '？！', '(=･ｪ･=)?'), '真的喵？！ (=･ｪ･=)?');
  });

  test('句尾已有库里的颜文字则不叠加', () => {
    assert.equal(K.endsWithKnownKaomoji('你好 (=^･ω･^=)', LIB), true);
    assert.equal(K.endsWithKnownKaomoji('你好', LIB), false);
  });
});

describe('颜文字：端到端', () => {
  const kcfg = { ...CFG, enableKaomoji: true };

  test('每封一句配一个', () => {
    const r = play(['我很好。', '你呢？'], kcfg);
    assert.equal((r.box.match(/\(=|\(づ|ヾ\(|ヽ\(/g) || []).length, 2, r.box);
  });

  test('关键词生效：别难过 走安慰组', () => {
    const r = play(['别难过。'], kcfg);
    assert.ok(r.box.includes('(づ=^･ω･^=)づ'), r.box);
  });

  test('检索用原文而非替换后的结果', () => {
    // 「我不开心」里的「我」会被替换成「本喵」；若拿替换后的文本检索，
    // 「不开心」仍在，但这里验证的是整体行为正确。
    const r = play(['我不开心。'], kcfg);
    assert.ok(r.box.includes('(=；ω；=)'), '应当走难过组: ' + r.box);
  });
});

// ════════════════════════════════════ 情绪合成表

describe('情绪合成表', () => {
  test('困惑撞上叹号 = 惊讶（什么意思！）', () => {
    assert.equal(K.select('什么意思', '！', PACK, null), '(=ﾟдﾟ=)');
  });

  test('困惑撞上 ？！ 同样归惊讶', () => {
    assert.equal(K.select('什么意思', '？！', PACK, null), '(=ﾟдﾟ=)');
  });

  test('交集非空时轮不到合成表', () => {
    // 困惑 ∩ 问号 有货，不该被合成规则劫到惊讶组
    const e = entryOf(K.select('什么意思', '？', PACK, null));
    assert.ok(e.tags.includes('困惑'), e.kao);
    assert.ok(e.symbols.includes('问号'), e.kao);
  });

  test('没有对应规则时仍按老降级走', () => {
    // 难过 + ！ 没配合成规则，交集为空 → 保关键词
    const kao = K.select('好难过', '！', PACK, null);
    assert.ok(LIB.find((e) => e.kao === kao).tags.includes('难过'), kao);
  });

  test('@merge 行不会被当成颜文字混进库里', () => {
    assert.equal(PACK.merges.length, 3);
    assert.ok(!LIB.some((e) => e.kao.includes('惊讶')), '合成规则被当成颜文字了');
  });
});

// ════════════════════════════════════ 省略号

describe('省略号：连打的句号', () => {
  const kcfg = { ...CFG, enableKaomoji: true };
  // 不能按空格切 —— (=_ _=) 这类脸自己就含空格
  const faceOf = (s) => (LIB.find((e) => s.endsWith(e.kao)) || {}).kao;

  test('第二个句号把结果折成英文省略号', () => {
    assert.equal(play(['我好累。', '。']).box, '本喵好累喵...');
  });

  test('一次粘进两个句号也一样', () => {
    assert.equal(play(['我好累。。']).box, '本喵好累喵...');
  });

  test('第三个句号起被吸收，结果不再变化', () => {
    const r = play(['我好累。', '。', '。', '。']);
    assert.equal(r.box, '本喵好累喵...');
    assert.equal(r.writes, 4, '每次按键都该写回一次（把多余的句号抹掉）');
  });

  test('省略号选的脸得配得上省略号', () => {
    const e = entryOf(faceOf(play(['今天下雨。', '。'], kcfg).box));
    assert.ok(e && e.symbols.includes('省略号'), String(e && e.kao));
  });

  test('我好累。。 走 困倦 ∩ 省略号', () => {
    const e = entryOf(faceOf(play(['我好累。', '。'], kcfg).box));
    assert.ok(e.tags.includes('困倦'), e.kao);
    assert.ok(e.symbols.includes('省略号'), e.kao);
  });

  test('折成省略号不该平白换一张脸', () => {
    // 回滚时 lastKaomoji 要退回上一格，否则「避开上次」会把重做推到另一个候选
    const one = play(['我好累。'], kcfg).box;
    const two = play(['我好累。', '。'], kcfg).box;
    assert.ok(faceOf(one), '没挑到颜文字，这个测试就是空跑: ' + one);
    assert.equal(faceOf(two), faceOf(one), one + '  →  ' + two);
  });

  test('单个句号照旧被吃掉，不受影响', () => {
    assert.equal(play(['我今天很开心。']).box, '本喵今天很开心喵');
  });

  test('句号后打别的字就不再合并', () => {
    assert.equal(play(['我好累。', '真的', '。']).box, '本喵好累喵真的喵');
  });
});

// ════════════════════════════════════ 默认组

describe('没有情绪关键词的平常话', () => {
  // 回归：拆成情绪/符号两个轴之后，「只看符号」从「平静组」悄悄变成了
  // 「所有配得上这个句尾的脸」，于是一句技术描述抽到了 (=T_T=)。
  const plain = '加了个符号和关键词判断的语气检查';

  test('一个关键词都不命中', () => {
    assert.equal(K.scanLastKeyword(plain, KWS), null);
  });

  test('走默认组，不是全库乱抽', () => {
    for (const p of ['', '！', '？', '...']) {
      const e = entryOf(K.select(plain, p, PACK, null));
      assert.ok(e.tags.includes('平静'), '句尾「' + p + '」选了非平静的脸: ' + e.kao);
    }
  });

  test('平常话打 ？！ 走惊讶，不是平静', () => {
    // 通配合成规则的「不管什么情绪」包含「没有情绪」；
    // 而且平静组里本来就一张配得上问叹的脸都没有
    const e = entryOf(K.select(plain, '？！', PACK, null));
    assert.ok(e.tags.includes('惊讶'), e.kao);
  });

  test('默认组里优先挑配得上句尾的', () => {
    const e = entryOf(K.select(plain, '！', PACK, null));
    assert.ok(e.symbols.includes('叹号'), e.kao);
  });

  test('有关键词时轮不到默认组', () => {
    const e = entryOf(K.select('好难过', '', PACK, null));
    assert.ok(e.tags.includes('难过'), e.kao);
  });

  test('没配 @default 时退回老行为，仍然有结果', () => {
    const noDefault = { ...PACK, defaultTag: null };
    assert.ok(K.select(plain, '！', noDefault, null));
  });
});
