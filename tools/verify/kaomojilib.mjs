/**
 * KaomojiLib.java 的逐行 JS 转写。
 * 结构与 Java 一一对应，改 Java 记得同步改这里，否则测试就失去意义。
 */

import { hashSeed } from './transcribe.mjs';

const splitTags = (s) => s.split(',').map((x) => x.trim()).filter(Boolean);

export const ELLIPSIS = '...';

export function parseLib(raw) {
  const out = [];
  if (!raw) return out;
  for (const line of String(raw).split('\n')) {
    const t = line.trim();
    // @ 开头是指令行（目前只有 @merge），它也含 '='，不跳过会被当成颜文字
    if (!t || t.startsWith('#') || t.startsWith('@')) continue;
    const eq = t.indexOf('=');
    if (eq <= 0) continue;
    // 标签在左、颜文字在右 —— 颜文字含 '='（猫脸的眼睛），
    // 放右边 indexOf('=') 才不会切在脸中间。
    const tags = splitTags(t.slice(0, eq));
    const kao = t.slice(eq + 1).trim();
    if (kao && tags.length) out.push({ kao, tags });
  }
  return out;
}

/** 从同一份颜文字库原文里挑出 `@merge 甲 + 乙 = 丙` 这类行 */
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
    const punctTag = left.slice(plus + 1).trim();
    if (kwTag && punctTag) out.push({ kwTag, punctTag, result });
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
  return [
    { kao: '(=^･ω･^=)', tags: ['平静', '日常'] },
    { kao: 'ヽ(=^･ω･^=)丿', tags: ['兴奋', '开心'] },
    { kao: '(=ʘωʘ=)', tags: ['疑问'] },
    { kao: '(=ﾟдﾟ=)', tags: ['惊讶'] },
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

/** 句尾标点 → 情绪标签。组合标点归「惊讶」，省略号归「无奈」 */
export function punctTag(punct) {
  if (!punct) return '平静';
  // 省略号单独一档 —— 它里面既没有 ？也没有 ！，落到下面会被当成平静
  if (punct.includes('.')) return '无奈';
  const q = punct.includes('？') || punct.includes('?');
  const e = punct.includes('！') || punct.includes('!');
  if (q && e) return '惊讶';
  if (q) return '疑问';
  if (e) return '兴奋';
  return '平静';
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

/** 情绪合成表命中时的候选；没有可用规则时返回空表 */
function byMerge(lib, merges, kwTags, pTag) {
  for (const m of merges || []) {
    if (m.punctTag !== pTag || !kwTags.includes(m.kwTag)) continue;
    const hit = lib.filter((e) => e.tags.includes(m.result));
    if (hit.length) return hit;    // 先写的规则先赢
  }
  return [];
}

export function select(body, punct, pk, avoid) {
  if (!pk || !pk.lib.length) return '';
  const lib = pk.lib;
  const kw = scanLastKeyword(body, pk.keywords);
  const pTag = punctTag(punct);

  const byKw = kw ? lib.filter((e) => hasAny(e, kw.tags)) : [];
  const byPunct = lib.filter((e) => e.tags.includes(pTag));

  let pool = byKw.filter((e) => byPunct.includes(e));   // 交集
  // 交集为空时先问合成表：两种情绪叠起来往往是第三种（「什么意思！」= 难以置信）
  if (!pool.length && kw) pool = byMerge(lib, pk.merges, kw.tags, pTag);
  if (!pool.length) pool = byKw;                        // 关键词比标点具体
  if (!pool.length) pool = byPunct;
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
    const tag = punctTag(p);
    const pk = tag === '疑问' ? '?' : (tag === '兴奋' ? '!' : null);
    if (pk === kp) keep = '';
  }
  return body + keep + ' ' + kao;
}

/** 句尾是否已带库里的颜文字（防重复叠加） */
export function endsWithKnownKaomoji(text, lib) {
  if (!text || !lib) return false;
  const t = text.trim();
  return lib.some((e) => e.kao && t.endsWith(e.kao));
}
