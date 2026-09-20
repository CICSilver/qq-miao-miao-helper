/**
 * KaomojiLib.java 的逐行 JS 转写。
 * 结构与 Java 一一对应，改 Java 记得同步改这里，否则测试就失去意义。
 */

import { hashSeed } from './transcribe.mjs';

const splitTags = (s) => s.split(',').map((x) => x.trim()).filter(Boolean);

export const ELLIPSIS = '...';

/** 全部符号标签。段落头省略符号那一半时用它兜底。 */
export const ALL_SYMBOLS = ['句号', '问号', '叹号', '问叹', '省略号', '波浪', '爱心'];

/**
 * 解析颜文字库。段落头 `[情绪,... | 符号,...]` 声明标签，之后每行一张脸，
 * 直到下一个段落头。脸后面可以跟 `| ASCII降级版`。
 */
export function parseLib(raw) {
  const out = [];
  if (!raw) return out;
  let tags = null;
  let symbols = null;
  for (const line of String(raw).split('\n')) {
    const t = line.trim();
    if (!t || t.startsWith('#') || t.startsWith('@')) continue;
    if (t.startsWith('[') && t.endsWith(']')) {
      const body = t.slice(1, -1);
      const bar = body.indexOf('|');
      if (bar < 0) {
        // 只写了情绪没写符号：当成「什么句尾都能用」
        tags = splitTags(body);
        symbols = ALL_SYMBOLS.slice();
      } else {
        tags = splitTags(body.slice(0, bar));
        symbols = splitTags(body.slice(bar + 1));
      }
      continue;
    }
    // 还没遇到段落头，或者段落头是空的 —— 没有标签的脸永远选不出来
    if (!tags || !tags.length || !symbols.length) continue;
    let kao = t;
    let ascii = null;
    const bar = t.indexOf('|');
    if (bar > 0) {
      kao = t.slice(0, bar).trim();
      ascii = t.slice(bar + 1).trim();
    }
    if (kao) out.push({ kao, ascii: ascii || kao, tags, symbols });
  }
  return out;
}

/** 从同一份颜文字库原文里挑出 `@merge 情绪 + 符号 = 情绪` 这类行 */
export function parseMerges(raw) {
  const out = [];
  if (!raw) return out;
  for (const line of String(raw).split('\n')) {
    const t = line.trim();
    if (!t.startsWith('@merge')) continue;
    const rest = t.slice('@merge'.length);
    const eq = rest.indexOf('=');
    if (eq <= 0) continue;
    const left = rest.slice(0, eq);
    const result = rest.slice(eq + 1).trim();
    const plus = left.indexOf('+');
    if (plus <= 0 || !result) continue;
    const kwTag = left.slice(0, plus).trim();
    const symbol = left.slice(plus + 1).trim();
    if (kwTag && symbol) out.push({ kwTag, symbol, result });
  }
  return out;
}

/** 选颜文字要用的全部数据，打包传递；库为空时退回兜底 */
export function pack(libRaw, kwRaw) {
  let lib = parseLib(libRaw);
  if (!lib.length) lib = fallbackLib();
  return { lib, merges: parseMerges(libRaw), keywords: parseKeywords(kwRaw) };
}

export function fallbackLib() {
  const all = ALL_SYMBOLS.slice();
  return [
    { kao: '(=^･ω･^=)', ascii: '(=^.w.^=)', tags: ['平静'], symbols: all },
    { kao: 'ヽ(=^･ω･^=)丿', ascii: '\\(=^w^=)/', tags: ['开心', '兴奋'], symbols: all },
    { kao: '(=ʘωʘ=)', ascii: '(=o.o=)', tags: ['困惑'], symbols: all },
    { kao: '(=ﾟдﾟ=)', ascii: '(=O.O=)', tags: ['惊讶'], symbols: all },
  ];
}

export function parseKeywords(raw) {
  const out = [];
  if (!raw) return out;
  for (const line of String(raw).split('\n')) {
    const t = line.trim();
    if (!t || t.startsWith('#')) continue;
    const eq = t.indexOf('=');
    if (eq <= 0) continue;
    const key = t.slice(0, eq).trim();
    if (!key) continue;
    out.push({ key, tags: splitTags(t.slice(eq + 1)) });   // tags 为空 = 排除短语
  }
  out.sort((a, b) => b.key.length - a.key.length);         // 最长优先
  return out;
}

export const isExclusion = (r) => r.tags.length === 0;

/**
 * 句尾标点 → 符号标签。
 * 空串对应「句号」而不是「没有标点」—— 中文句号是确认键、会被吃掉，
 * 所以留下空串恰恰说明用户打的是句号。
 */
export function symbolOf(punct) {
  if (!punct) return '句号';
  // 省略号先判 —— 它里面既没有 ？也没有 ！，落到下面会被当成句号
  if (punct.includes('.')) return '省略号';
  const q = punct.includes('？') || punct.includes('?');
  const e = punct.includes('！') || punct.includes('!');
  if (q && e) return '问叹';
  if (q) return '问号';
  if (e) return '叹号';
  return '句号';
}

/**
 * 左到右最长优先扫描，取最后一个命中。
 * 最长优先决定「同一位置谁赢」，最后命中决定「不同位置谁赢」；
 * 只按起始位置取最靠后的话，「别难过」会输给从第 1 位开始的「难过」。
 */
export function scanLastKeyword(body, rules) {
  if (!body || !rules || !rules.length) return null;
  let last = null;
  let i = 0;
  while (i < body.length) {
    let hit = null;
    for (const r of rules) {             // 已按长度倒序
      if (body.startsWith(r.key, i)) { hit = r; break; }
    }
    if (hit) { last = hit; i += hit.key.length; } else i++;
  }
  return (!last || isExclusion(last)) ? null : last;
}

const hasAny = (entry, want) => want.some((t) => entry.tags.includes(t));

/**
 * 情绪合成表命中时的候选；没有可用规则时返回空表。
 * 合成出来的情绪仍然优先和句尾符号取交集 —— 换了情绪不代表可以无视句尾。
 */
function byMerge(lib, merges, kwTags, symbol) {
  for (const m of merges || []) {
    if (m.symbol !== symbol) continue;
    // kwTag 写 * 表示「不管原本什么情绪」；具体规则写在前面就能赢过它
    if (m.kwTag !== '*' && !kwTags.includes(m.kwTag)) continue;
    const all = lib.filter((e) => e.tags.includes(m.result));
    const fitting = all.filter((e) => e.symbols.includes(symbol));
    if (fitting.length) return fitting;    // 先写的规则先赢
    if (all.length) return all;
  }
  return [];
}

export function select(body, punct, pk, avoid) {
  if (!pk || !pk.lib.length) return '';
  const lib = pk.lib;
  const kw = scanLastKeyword(body, pk.keywords);
  const symbol = symbolOf(punct);

  const byKw = kw ? lib.filter((e) => hasAny(e, kw.tags)) : [];
  const bySymbol = lib.filter((e) => e.symbols.includes(symbol));

  let pool = byKw.filter((e) => bySymbol.includes(e));   // 交集
  // 交集为空时先问合成表：情绪和句尾叠起来往往是第三种（「什么意思！」= 难以置信）
  if (!pool.length && kw) pool = byMerge(lib, pk.merges, kw.tags, symbol);
  if (!pool.length) pool = byKw;                         // 关键词比句尾具体
  if (!pool.length) pool = bySymbol;
  if (!pool.length) pool = lib;

  if (avoid && pool.length > 1) {
    const filtered = pool.filter((e) => e.kao !== avoid);
    if (filtered.length) pool = filtered;
  }

  const n = pool.length;
  return pool[((hashSeed('kao|' + body) % n) + n) % n].kao;
}

/** 颜文字末尾自带的标点：'?' / '!' / null */
export function trailingPunctOf(kao) {
  if (!kao) return null;
  const c = kao[kao.length - 1];
  if (c === '?' || c === '？') return '?';
  if (c === '!' || c === '！') return '!';
  return null;
}

/**
 * 正文 + 句尾标点 + 颜文字。
 * 颜文字自带的标点与句尾同类时吃掉句尾那个，避免「？ (=･ｪ･=)?」双问号；
 * 但组合标点（？！）一律保留 —— 只吃一个会留下孤零零的另一个。
 */
export function assemble(body, punct, kao) {
  const p = punct || '';
  if (!kao) return body + p;
  const kp = trailingPunctOf(kao);
  const combo = p.length > 1;
  let keep = p;
  if (kp && !combo) {
    const sym = symbolOf(p);
    const pk = sym === '问号' ? '?' : (sym === '叹号' ? '!' : null);
    if (pk === kp) keep = '';
  }
  return body + keep + ' ' + kao;
}

/** 句尾是否已带库里的颜文字（防重复叠加）。ASCII 降级版也算。 */
export function endsWithKnownKaomoji(text, lib) {
  if (!text || !lib) return false;
  const t = text.trim();
  return lib.some((e) => (e.kao && t.endsWith(e.kao)) || (e.ascii && t.endsWith(e.ascii)));
}
