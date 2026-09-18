import { test, describe } from 'node:test';
import assert from 'node:assert/strict';
import {
  transform, defaultConfig, parseRules, splitSentences,
  applyReplacements, looksComposing, isSelfReferential, MeowRewriter,
} from './transcribe.mjs';

const KAO = ['(=^-w-^=)', '(=`w`=)'];

function cfg(over = {}) {
  return {
    ...defaultConfig(),
    rules: parseRules('你=主人\n我=本喵'),
    kaomoji: KAO,
    enableKaomoji: false,      // 默认关掉，避免干扰断言
    ...over,
  };
}

// ────────────────────────────── 原仓库 bug 的回归测试 ──────────────────────────────

describe('原仓库 bug：保护区缺失', () => {
  test('链接不再被拆坏', () => {
    const out = transform('这个文档你看一下 https://docs.qq.com/doc/abc123', cfg());
    assert.ok(out.includes('https://docs.qq.com/doc/abc123'), out);
    assert.ok(!out.includes('docs.喵'), '链接被插入了喵: ' + out);
  });

  test('邮箱不再被拆坏', () => {
    const out = transform('我的邮箱是 abc.def@qq.com', cfg());
    assert.ok(out.includes('abc.def@qq.com'), out);
    assert.ok(out.startsWith('本喵的邮箱是'), out);
  });

  test('小数不再被插入喵', () => {
    const out = transform('这批货单价 3.14 元，你算一下', cfg());
    assert.ok(out.includes('3.14'), '小数被拆坏: ' + out);
  });

  test('@提及 不再被替换', () => {
    const out = transform('@你好呀 帮我看下', cfg());
    assert.ok(out.startsWith('@你好呀'), '@提及被改坏: ' + out);
    assert.ok(out.includes('本喵'), out);
  });

  test('[表情] 码不再被替换', () => {
    const out = transform('哈哈[你好]我们走', cfg());
    assert.ok(out.includes('[你好]'), '表情码被改坏: ' + out);
  });

  test('反引号代码不被动', () => {
    const out = transform('执行 `git log --oneline` 你看下', cfg());
    assert.ok(out.includes('`git log --oneline`'), out);
  });
});

describe('原仓库 bug：多行被压成一行', () => {
  test('换行保留', () => {
    const out = transform('第一行你好\n第二行我在', cfg());
    assert.ok(out.includes('\n'), '换行丢失: ' + JSON.stringify(out));
    assert.equal(out.split('\n').length, 2);
  });

  test('多行各自加喵', () => {
    const out = transform('第一行内容\n第二行内容', cfg({ rules: [] }));
    assert.equal(out, '第一行内容喵\n第二行内容喵');
  });
});

describe('原仓库 bug：颜文字每次重抽', () => {
  test('同一文本永远选中同一个颜文字', () => {
    const c = cfg({ enableKaomoji: true });
    const first = transform('我很开心', c);
    for (let i = 0; i < 20; i++) {
      assert.equal(transform('我很开心', c), first, '颜文字在闪烁');
    }
  });

  test('不同文本可以选到不同颜文字（不是写死的）', () => {
    const c = cfg({ enableKaomoji: true });
    const seen = new Set();
    for (const s of ['我开心', '我难过', '我困了', '我饿了', '我走了', '我来了']) {
      const out = transform(s, c);
      for (const k of KAO) if (out.endsWith(k)) seen.add(k);
    }
    assert.ok(seen.size >= 2, '颜文字池没被用起来，只出现了: ' + [...seen]);
  });
});

// ────────────────────────────── 引擎核心行为 ──────────────────────────────

describe('引擎核心', () => {
  test('喵默认插在标点之前', () => {
    assert.equal(transform('今天天气真好！', cfg({ rules: [] })), '今天天气真好喵！');
  });

  test('可切回原版行为：喵在标点之后', () => {
    assert.equal(
      transform('今天天气真好！', cfg({ rules: [], suffixBeforePunct: false })),
      '今天天气真好！喵',
    );
  });

  test('连续标点算同一句尾巴', () => {
    assert.equal(transform('太好了！！！', cfg({ rules: [] })), '太好了喵！！！');
  });

  test('已经以喵结尾不重复加', () => {
    assert.equal(transform('你好喵！', cfg({ rules: [] })), '你好喵！');
  });

  test('过短的句子跳过', () => {
    assert.equal(transform('嗯。好的呀。', cfg({ rules: [] })), '嗯。好的呀喵。');
  });

  test('单趟替换：产物不被后续规则再改', () => {
    // 我->本喵，喵->呜。链式 replace 会得到「本呜」
    const out = transform('我', cfg({ rules: parseRules('我=本喵\n喵=呜') }));
    assert.equal(out, '本喵');
  });

  test('最长优先：我们 胜过 我', () => {
    const out = transform('我们走吧', cfg({ rules: parseRules('我们=我们这群喵\n我=本喵') }));
    assert.ok(out.startsWith('我们这群喵'), out);
  });

  test('纯链接消息完全不动', () => {
    const url = 'https://github.com/foo/bar';
    assert.equal(transform(url, cfg({ enableKaomoji: true })), url);
  });

  test('空串与纯空白原样返回', () => {
    assert.equal(transform('', cfg()), '');
    assert.equal(transform('   ', cfg()), '   ');
  });

  test('断句无损：拼回去等于原文', () => {
    for (const c of ['a。b！！c', '你好\n世界', '！！！', 'abc', '']) {
      assert.equal(splitSentences(c).map((s) => s.body + s.punct).join(''), c);
    }
  });

  test('maxSuffixPerMessage 限流（0 表示不限）', () => {
    const four = '第一句话。第二句话。第三句话。第四句话。';
    assert.equal((transform(four, cfg({ rules: [] })).match(/喵/g) || []).length, 4);
    const capped = transform(four, cfg({ rules: [], maxSuffixPerMessage: 2 }));
    assert.equal((capped.match(/喵/g) || []).length, 2);
  });
});

describe('规则解析', () => {
  test('每行 原文=替换，忽略空行与注释', () => {
    const r = parseRules('你=主人\n\n# 注释\n我=本喵\n坏行\n');
    assert.deepEqual(r.map((x) => x.from + '>' + x.to).sort(), ['你>主人', '我>本喵'].sort());
  });

  test('按原文长度倒序，长的在前', () => {
    const r = parseRules('我=本喵\n我们=我们这群喵');
    assert.equal(r[0].from, '我们');
  });

  test('识别自指规则', () => {
    assert.equal(isSelfReferential({ from: '我们', to: '我们这群喵' }), true);
    assert.equal(isSelfReferential({ from: '我', to: '本喵' }), false);
  });
});

// ────────────────────────────── 增量重写状态机 ──────────────────────────────

describe('MeowRewriter', () => {
  test('回声被识别，不会无限重写', () => {
    const r = new MeowRewriter();
    const c = cfg();
    const out1 = r.rewrite('我今天很开心', c);
    assert.ok(out1 && out1.includes('本喵'), out1);
    assert.equal(r.rewrite(out1, c), null, '回声没被吃掉');
    assert.equal(r.rewrite(out1, c), null);
  });

  test('继续打字：基于原文重算，自指规则不套娃', () => {
    const r = new MeowRewriter();
    const c = cfg({ rules: parseRules('我们=我们这群喵\n你=主人') });
    let box = '我们明天见';
    let out = r.rewrite(box, c);
    assert.ok(out.includes('我们这群喵'), out);
    box = out;
    for (const seg of ['，', '记得', '带上', '你的', '本子']) {
      box += seg;
      const next = r.rewrite(box, c);
      if (next !== null) box = next;
    }
    assert.equal((box.match(/这群喵/g) || []).length, 1, '自指规则套娃了: ' + box);
    assert.ok(box.includes('主人的本子'), box);
  });

  test('退化路径（中途插字）也必须收敛', () => {
    const r = new MeowRewriter();
    const c = cfg({ rules: parseRules('我们=我们这群喵') });
    let box = r.rewrite('我们明天见', c);
    for (let i = 0; i < 6; i++) {
      box = '嗨' + box;                     // 在开头插字 → 不再以 lastWritten 开头
      const next = r.rewrite(box, c);
      if (next !== null) box = next;
    }
    assert.ok((box.match(/这群喵/g) || []).length <= 1, '退化路径发散: ' + box);
  });

  test('组词状态下不动手', () => {
    const r = new MeowRewriter();
    assert.equal(r.rewrite('我想说nihao', cfg()), null, '在组词时改写了');
    assert.ok(r.rewrite('我想说你好', cfg()) !== null, '上屏后应当处理');
  });

  test('无需改动时返回 null，不产生无意义写入', () => {
    const r = new MeowRewriter();
    assert.equal(r.rewrite('好的喵', cfg({ rules: [] })), null);
  });

  test('空框重置状态', () => {
    const r = new MeowRewriter();
    r.rewrite('我很开心', cfg());
    assert.equal(r.rewrite('   ', cfg()), null);
    const out = r.rewrite('我很开心', cfg());
    assert.ok(out && out.includes('本喵'), '重置后应能重新处理: ' + out);
  });
});
