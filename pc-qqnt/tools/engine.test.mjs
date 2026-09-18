import { test, describe } from 'node:test';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
const engine = require('../src/engine.js');
const { transform, parseRules, splitSentences } = engine;

const always = () => 0;          // 所有概率判定都通过
const never = () => 0.999999;

const base = {
  rules: '我们=我们这群喵\n我=本喵\n你=主人',
  kaomoji: '(=^-w-^=)\n(=`w`=)',
  kaomojiProbability: 0,
  suffixProbability: 1,
  maxSuffixPerMessage: 0,
};
const cfg = (over) => Object.assign({}, base, over);

describe('保护区', () => {
  test('链接原样保留，内部不插喵', () => {
    const out = transform('这个文档你看一下 https://docs.qq.com/doc/abc123', cfg(), always);
    assert.ok(out.includes('https://docs.qq.com/doc/abc123'), out);
    assert.ok(!out.includes('docs.喵'), out);
  });

  test('邮箱保留', () => {
    const out = transform('我的邮箱是 abc.def@qq.com', cfg(), always);
    assert.ok(out.includes('abc.def@qq.com'), out);
    assert.ok(out.startsWith('本喵的邮箱'), out);
  });

  test('小数不被拆', () => {
    assert.ok(transform('单价 3.14 元，你算一下', cfg(), always).includes('3.14'));
  });

  test('@提及 与 [表情] 不被替换', () => {
    assert.ok(transform('@你好呀 在吗', cfg(), always).startsWith('@你好呀'));
    assert.ok(transform('哈哈[你好]我们走', cfg(), always).includes('[你好]'));
  });

  test('反引号代码不被动', () => {
    const out = transform('执行 `npm run test` 你看下', cfg(), always);
    assert.ok(out.includes('`npm run test`'), out);
  });

  test('纯链接消息完全不动', () => {
    const url = 'https://github.com/foo/bar';
    assert.equal(transform(url, cfg({ kaomojiProbability: 1 }), always), url);
  });
});

describe('语气词', () => {
  test('默认插在标点之前', () => {
    assert.equal(transform('今天天气真好！', cfg({ rules: '' }), always), '今天天气真好喵！');
  });

  test('可切换到标点之后', () => {
    assert.equal(
      transform('今天天气真好！', cfg({ rules: '', suffixBeforePunct: false }), always),
      '今天天气真好！喵',
    );
  });

  test('连续标点算同一句', () => {
    assert.equal(transform('太好了！！！', cfg({ rules: '' }), always), '太好了喵！！！');
  });

  test('已有喵不重复加', () => {
    assert.equal(transform('你好喵！', cfg({ rules: '' }), always), '你好喵！');
  });

  test('太短的句子跳过', () => {
    assert.equal(transform('嗯。好的呀。', cfg({ rules: '' }), always), '嗯。好的呀喵。');
  });

  test('换行保留且各自加喵', () => {
    assert.equal(
      transform('第一行内容\n第二行内容', cfg({ rules: '' }), always),
      '第一行内容喵\n第二行内容喵',
    );
  });

  test('概率不命中则不加', () => {
    assert.equal(
      transform('今天天气真好', cfg({ rules: '', suffixProbability: 0.5 }), never),
      '今天天气真好',
    );
  });

  test('maxSuffixPerMessage 限流', () => {
    const four = '第一句话。第二句话。第三句话。第四句话。';
    assert.equal((transform(four, cfg({ rules: '' }), always).match(/喵/g) || []).length, 4);
    const capped = transform(four, cfg({ rules: '', maxSuffixPerMessage: 2 }), always);
    assert.equal((capped.match(/喵/g) || []).length, 2);
  });
});

describe('替换规则', () => {
  test('基本替换', () => {
    assert.equal(transform('我喜欢你', cfg({ suffixProbability: 0 }), always), '本喵喜欢主人');
  });

  test('最长优先', () => {
    assert.ok(transform('我们走吧', cfg({ suffixProbability: 0 }), always).startsWith('我们这群喵'));
  });

  test('单趟扫描：产物不被后续规则再改', () => {
    const out = transform('我', cfg({ rules: '我=本喵\n喵=呜', suffixProbability: 0 }), always);
    assert.equal(out, '本喵');
  });

  test('解析忽略空行与注释', () => {
    const r = parseRules('你=主人\n\n# 注释\n我=本喵\n坏行');
    assert.equal(r.length, 2);
  });
});

describe('边界', () => {
  test('enabled=false 原样返回', () => {
    assert.equal(transform('我喜欢你', cfg({ enabled: false }), always), '我喜欢你');
  });

  test('空串与纯空白', () => {
    assert.equal(transform('', cfg(), always), '');
    assert.equal(transform('   ', cfg(), always), '   ');
  });

  test('非字符串输入不炸', () => {
    assert.equal(transform(null, cfg(), always), null);
    assert.equal(transform(undefined, cfg(), always), undefined);
  });

  test('断句无损', () => {
    for (const c of ['a。b！！c', '你好\n世界', '！！！', 'abc', '']) {
      assert.equal(splitSentences(c).map((s) => s.body + s.punct).join(''), c);
    }
  });

  test('颜文字命中时追加一个', () => {
    const out = transform('你好呀', cfg({ rules: '', kaomojiProbability: 1 }), always);
    assert.equal((out.match(/\(=/g) || []).length, 1, out);
  });
});
