/**
 * 喵化引擎（PC QQNT 版）。
 *
 * 与 Android 版最大的不同：这里在**发送那一刻**只变换一次，而不是每敲一个字
 * 就重写整个输入框。所以 Android 版里那堆麻烦事 —— 幂等、自指规则套娃、
 * 拼音组词保护 —— 在这里统统不存在，引擎可以干净很多。
 *
 * CommonJS：preload.js 要 require 它。
 */

'use strict';

// 占位符用私用区字符：不含标点故不参与断句，不含中文故不被规则命中
const MASK_OPEN = String.fromCharCode(0xe000);
const MASK_CLOSE = String.fromCharCode(0xe001);
const MASK_RE = new RegExp(MASK_OPEN + '(\\d+)' + MASK_CLOSE, 'g');
const PUA_STRIP = new RegExp('[' + MASK_OPEN + MASK_CLOSE + ']', 'g');

/** 句末终止符。刻意不含 '.'：否则链接和小数会被拆坏。 */
const TERMINATORS = '。！？!?…～~；;\n';
const isTerminator = (c) => TERMINATORS.indexOf(c) >= 0;

/** 保护区，顺序即优先级。代码块最先，URL 次之。 */
const PROTECT = [
  /```[\s\S]*?```|`[^`\n]+`/g,
  /\b(?:https?:\/\/|www\.)[^\s一-鿿，。！？；：）】]+/gi,
  /[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}/g,
  /@[^\s@，。！？,.!?]{1,32}/g,
  /\[[^\[\]\n]{1,12}\]/g,
];

const DEFAULT_CONFIG = {
  enabled: true,
  suffix: '喵',
  /** true: 今天真好喵！  false: 今天真好！喵 */
  suffixBeforePunct: true,
  /** 每句加语气词的概率 0..1 */
  suffixProbability: 0.8,
  /** 一条消息最多加几个，<=0 不限制 */
  maxSuffixPerMessage: 3,
  minSentenceLength: 2,
  /** 每行 原文=替换 */
  rules: '我们=我们这群喵\n我=本喵\n你=主人',
  kaomoji: '(=^･ω･^=)\n(=｀ω´=)\nฅ^•ﻌ•^ฅ\n(*^ω^*)',
  kaomojiProbability: 0.3,
};

// ------------------------------------------------------------------ 解析

/** 每行 `原文=替换`；空行与 # 开头的行忽略。 */
function parseRules(raw) {
  const rules = [];
  if (!raw) return rules;
  for (const line of String(raw).split('\n')) {
    const t = line.trim();
    if (!t || t.startsWith('#')) continue;
    const eq = t.indexOf('=');
    if (eq <= 0) continue;
    const from = t.slice(0, eq).trim();
    const to = t.slice(eq + 1).trim();
    if (from) rules.push({ from, to });
  }
  return rules;
}

function parseLines(raw) {
  if (!raw) return [];
  return String(raw).split('\n').map((s) => s.trim()).filter(Boolean);
}

// ------------------------------------------------------------ 保护区遮罩

function mask(input) {
  const vault = [];
  let text = input.replace(PUA_STRIP, '');
  for (const p of PROTECT) {
    text = text.replace(p, (m) => {
      const token = MASK_OPEN + vault.length + MASK_CLOSE;
      vault.push(m);
      return token;
    });
  }
  return { text, vault };
}

function unmask(text, vault) {
  return text.replace(MASK_RE, (_, i) => {
    const idx = Number(i);
    return idx >= 0 && idx < vault.length ? vault[idx] : '';
  });
}

// --------------------------------------------------------------- 替换

function buildTrie(rules) {
  const root = { next: new Map(), to: null };
  for (const r of rules) {
    if (!r.from) continue;
    let node = root;
    for (const ch of r.from) {
      let child = node.next.get(ch);
      if (!child) {
        child = { next: new Map(), to: null };
        node.next.set(ch, child);
      }
      node = child;
    }
    node.to = r.to;
  }
  return root;
}

/**
 * 单趟、最长优先。输出缓冲区永不回扫，所以 {我=本喵, 喵=呜} 不会把刚生成的
 * 「本喵」又改成「本呜」；「我们」也自然优先于「我」。
 */
function applyReplacements(text, trie) {
  let out = '';
  let i = 0;
  while (i < text.length) {
    if (text[i] === MASK_OPEN) {
      const close = text.indexOf(MASK_CLOSE, i + 1);
      if (close >= 0) {
        out += text.slice(i, close + 1);
        i = close + 1;
        continue;
      }
    }
    let node = trie;
    let bestTo = null;
    let bestLen = 0;
    let j = i;
    while (j < text.length) {
      const child = node.next.get(text[j]);
      if (!child) break;
      node = child;
      j++;
      if (node.to !== null) {
        bestTo = node.to;
        bestLen = j - i;
      }
    }
    if (bestTo !== null && bestLen > 0) {
      out += bestTo;
      i += bestLen;
    } else {
      out += text[i];
      i++;
    }
  }
  return out;
}

// --------------------------------------------------------------- 断句

function splitSentences(text) {
  const out = [];
  let body = '';
  let punct = '';
  const flush = () => {
    if (!body && !punct) return;
    out.push({ body, punct });
    body = '';
    punct = '';
  };
  for (let i = 0; i < text.length; i++) {
    const ch = text[i];
    if (isTerminator(ch)) {
      punct += ch;
      const last = i + 1 >= text.length;
      if (last || !isTerminator(text[i + 1])) flush();
    } else {
      if (punct) flush();
      body += ch;
    }
  }
  flush();
  return out;
}

const trimEnd = (s) => s.replace(/\s+$/, '');

function canSuffix(body, cfg) {
  const t = body.trim();
  if (t.length < cfg.minSentenceLength) return false;
  if (cfg.suffix && t.endsWith(cfg.suffix)) return false;
  return t.length === 0 || t[t.length - 1] !== MASK_CLOSE;
}

/** 去掉占位符后还有没有字母数字。纯链接 / 纯表情消息不加工。 */
function hasProse(masked) {
  return /[\p{L}\p{N}]/u.test(masked.replace(MASK_RE, ''));
}

// --------------------------------------------------------------- 主入口

/**
 * @param {string} input 用户原文
 * @param {object} userCfg 配置
 * @param {() => number} rng 随机源（测试时注入固定值）
 */
function transform(input, userCfg, rng) {
  const cfg = Object.assign({}, DEFAULT_CONFIG, userCfg || {});
  const rand = rng || Math.random;
  if (!cfg.enabled || typeof input !== 'string' || !input.trim()) return input;

  const m = mask(input);
  if (!hasProse(m.text)) return input;   // 纯链接 / 纯表情码，原样放行

  let text = applyReplacements(m.text, buildTrie(parseRules(cfg.rules)));

  if (cfg.suffix && cfg.suffixProbability > 0) {
    let added = 0;
    let sb = '';
    for (const s of splitSentences(text)) {
      const capped = cfg.maxSuffixPerMessage > 0 && added >= cfg.maxSuffixPerMessage;
      if (capped || !canSuffix(s.body, cfg) || rand() >= cfg.suffixProbability) {
        sb += s.body + s.punct;
        continue;
      }
      added++;
      if (cfg.suffixBeforePunct) {
        const core = trimEnd(s.body);
        sb += core + cfg.suffix + s.body.slice(core.length) + s.punct;
      } else {
        sb += s.body + s.punct + cfg.suffix;
      }
    }
    text = sb;
  }

  const pool = parseLines(cfg.kaomoji);
  if (pool.length && cfg.kaomojiProbability > 0 && rand() < cfg.kaomojiProbability) {
    const pick = pool[Math.floor(rand() * pool.length) % pool.length];
    if (pick) text = text + (/\s$/.test(text) ? '' : ' ') + pick;
  }

  return unmask(text, m.vault);
}

module.exports = {
  DEFAULT_CONFIG,
  transform,
  parseRules,
  parseLines,
  splitSentences,
  applyReplacements,
  buildTrie,
};
