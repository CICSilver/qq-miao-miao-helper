/**
 * MeowEngine.java / MeowRewriter.java 的逐行 JS 转写。
 *
 * 目的：在没有 JDK 的环境下验证移植逻辑。能抓出正则写错、顺序颠倒、
 * 边界差一这类问题；抓不出 Java 编译错误。
 * 结构刻意与 Java 保持一一对应，便于对照。
 */

const MASK_OPEN = String.fromCharCode(0xE000);
const MASK_CLOSE = String.fromCharCode(0xE001);
const MASK_RE = new RegExp(MASK_OPEN + '(\\d+)' + MASK_CLOSE, 'g');
const PUA_STRIP = new RegExp('[' + MASK_OPEN + MASK_CLOSE + ']', 'g');

const TERMINATORS = '。！？!?…～~；;\n';
const isTerminator = (c) => TERMINATORS.indexOf(c) >= 0;

const PROTECT = [
  /```[\s\S]*?```|`[^`\n]+`/g,
  /\b(?:https?:\/\/|www\.)[^\s一-鿿，。！？；：）】]+/gi,
  /[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}/g,
  /@[^\s@，。！？,.!?]{1,32}/g,
  /\[[^\[\]\n]{1,12}\]/g,
];

export function hashSeed(s) {
  let h = 2166136261 | 0;
  for (let i = 0; i < s.length; i++) {
    h = (h ^ s.charCodeAt(i)) | 0;
    h = Math.imul(h, 16777619) | 0;
  }
  return h;
}

export function defaultConfig() {
  return {
    suffix: '喵',
    suffixBeforePunct: true,
    enableSuffix: true,
    maxSuffixPerMessage: 0,
    minSentenceLength: 2,
    rules: [],
    enableKaomoji: true,
    kaomoji: [],
    protectRegions: true,
  };
}

function mask(input, cfg) {
  const vault = [];
  let text = input.replace(PUA_STRIP, '');
  if (!cfg.protectRegions) return { text, vault };
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

function buildTrie(rules) {
  const root = { next: new Map(), to: null };
  for (const r of rules || []) {
    if (!r || !r.from) continue;
    let node = root;
    for (const ch of r.from) {
      let child = node.next.get(ch);
      if (!child) { child = { next: new Map(), to: null }; node.next.set(ch, child); }
      node = child;
    }
    node.to = r.to;
  }
  return root;
}

export function applyReplacements(text, trie) {
  let out = '';
  let i = 0;
  while (i < text.length) {
    if (text[i] === MASK_OPEN) {
      const close = text.indexOf(MASK_CLOSE, i + 1);
      if (close >= 0) { out += text.slice(i, close + 1); i = close + 1; continue; }
    }
    let node = trie, bestTo = null, bestLen = 0, j = i;
    while (j < text.length) {
      const child = node.next.get(text[j]);
      if (!child) break;
      node = child; j++;
      if (node.to !== null) { bestTo = node.to; bestLen = j - i; }
    }
    if (bestTo !== null && bestLen > 0) { out += bestTo; i += bestLen; }
    else { out += text[i]; i++; }
  }
  return out;
}

export function splitSentences(text) {
  const out = [];
  let body = '', punct = '';
  const flush = () => {
    if (body.length === 0 && punct.length === 0) return;
    out.push({ body, punct });
    body = ''; punct = '';
  };
  for (let i = 0; i < text.length; i++) {
    const ch = text[i];
    if (isTerminator(ch)) {
      punct += ch;
      const lastChar = i + 1 >= text.length;
      const nextIsTerm = !lastChar && isTerminator(text[i + 1]);
      if (lastChar || !nextIsTerm) flush();
    } else {
      if (punct.length > 0) flush();
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

export function stripTrailingKaomoji(text, pool) {
  if (!pool) return text;
  const trimmed = trimEnd(text);
  for (const k of pool) {
    if (k && trimmed.endsWith(k)) return trimEnd(trimmed.slice(0, trimmed.length - k.length));
  }
  return text;
}

function hasProse(masked) {
  const bare = masked.replace(MASK_RE, '');
  for (const ch of bare) {
    if (/[\p{L}\p{N}]/u.test(ch)) return true;   // 对应 Character.isLetterOrDigit
  }
  return false;
}

export function transform(input, cfg) {
  if (input === null || input === undefined || !input.trim()) return input;

  const m = mask(input, cfg);
  if (!hasProse(m.text)) return input;

  let text = applyReplacements(m.text, buildTrie(cfg.rules));

  if (cfg.enableSuffix && cfg.suffix) {
    let sb = '';
    let added = 0;
    for (const s of splitSentences(text)) {
      const capped = cfg.maxSuffixPerMessage > 0 && added >= cfg.maxSuffixPerMessage;
      if (capped || !canSuffix(s.body, cfg)) { sb += s.body + s.punct; continue; }
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

  if (cfg.enableKaomoji && cfg.kaomoji && cfg.kaomoji.length > 0) {
    const n = cfg.kaomoji.length;
    const idx = ((hashSeed('kao|' + m.text) % n) + n) % n;   // Math.floorMod
    const pick = cfg.kaomoji[idx];
    if (pick) text = text + (/\s$/.test(text) ? '' : ' ') + pick;
  }

  return unmask(text, m.vault);
}

export function parseRules(raw) {
  const rules = [];
  if (!raw || !raw.trim()) return rules;
  for (const line of raw.split('\n')) {
    const t = line.trim();
    if (!t || t.startsWith('#')) continue;
    const eq = t.indexOf('=');
    if (eq <= 0) continue;
    const from = t.slice(0, eq).trim();
    const to = t.slice(eq + 1).trim();
    if (from) rules.push({ from, to });
  }
  rules.sort((a, b) => b.from.length - a.from.length);
  return rules;
}

export const isSelfReferential = (r) => !!r.from && r.to.includes(r.from);

// ------------------------------------------------------- MeowRewriter 转写

const COMPOSING_TAIL = /[A-Za-z']{2,}$/;
const HAS_CJK = /[一-鿿]/;

export function looksComposing(text) {
  if (!text) return false;
  const m = text.match(COMPOSING_TAIL);
  if (!m) return false;
  return HAS_CJK.test(text.slice(0, text.length - m[0].length));
}

export class MeowRewriter {
  constructor() { this.lastOriginal = null; this.lastWritten = null; }
  reset() { this.lastOriginal = null; this.lastWritten = null; }
  isEcho(cur) { return cur !== null && cur === this.lastWritten; }

  rewrite(current, cfg, skipComposingCheck = false) {
    if (current === null || current === undefined || !current.trim()) { this.reset(); return null; }
    if (this.isEcho(current)) return null;
    if (!skipComposingCheck && looksComposing(current)) return null;

    let original, reconstructed;
    if (this.lastWritten === null) {
      original = current; reconstructed = true;
    } else if (current.startsWith(this.lastWritten)) {
      original = (this.lastOriginal === null ? '' : this.lastOriginal)
        + current.slice(this.lastWritten.length);
      reconstructed = true;
    } else {
      original = current; reconstructed = false;
    }

    const effective = reconstructed ? cfg : { ...cfg, rules: cfg.rules.filter((r) => !isSelfReferential(r)) };
    const out = transform(original, effective);

    this.lastOriginal = original;
    this.lastWritten = out;
    return out === current ? null : out;
  }
}
