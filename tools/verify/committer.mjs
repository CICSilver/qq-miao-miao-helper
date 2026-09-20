/**
 * MeowCommitter.java 的逐行 JS 转写。
 * 结构与 Java 一一对应，改 Java 记得同步改这里。
 */

import { transform } from './transcribe.mjs';
import * as K from './kaomojilib.mjs';

const TERM_EAT = '。';
const TERM_KEEP = '？！';
const isTerm = (c) => c === TERM_EAT || TERM_KEEP.includes(c);

/** 整段都是句号？（连打的句号要折成省略号） */
const isAllEat = (s) => !!s && [...s].every((c) => c === TERM_EAT);

/**
 * 实际敲下的句尾标点 → 留在文本里的句尾标点。
 *   。      → 空串（吃掉）
 *   。。以上 → ...（省略号）
 *   其余     → 去掉句号后原样保留
 */
export function normalizePunct(punct) {
  if (!punct) return '';
  if (isAllEat(punct)) return punct.length >= 2 ? K.ELLIPSIS : '';
  return [...punct].filter((c) => c !== TERM_EAT).join('');
}

export class MeowCommitter {
  constructor() {
    this.committed = '';
    this.lastWritten = null;
    this.lastStart = -1;
    this.lastBody = null;
    this.lastRaw = '';
    this.lastKaomoji = null;
    this.prevKaomoji = null;
  }

  reset() {
    this.committed = '';
    this.lastWritten = null;
    this.lastStart = -1;
    this.lastBody = null;
    this.lastRaw = '';
    // lastKaomoji 刻意不清，跨消息也避开重复
  }

  isEcho(box) {
    return box !== null && box !== undefined && box === this.lastWritten;
  }

  onTextChanged(box, cfg, pack) {
    if (box === null || box === undefined) return null;
    if (this.isEcho(box)) return null;

    if (this._tryRollback(box, cfg, pack)) return this.lastWritten;

    if (!box.startsWith(this.committed)) {
      this.committed = '';
      this.lastStart = -1;
      this.lastBody = null;
      this.lastRaw = '';
    }

    let rest = box.slice(this.committed.length);
    let fired = 0;
    for (;;) {
      let i = -1;
      for (let k = 0; k < rest.length; k++) if (isTerm(rest[k])) { i = k; break; }
      if (i < 0) break;
      let j = i;
      while (j < rest.length && isTerm(rest[j])) j++;
      this._emit(rest.slice(0, i), rest.slice(i, j), cfg, pack);
      rest = rest.slice(j);
      fired++;
    }
    if (fired === 0) return null;

    const out = this.committed + rest;
    this.lastWritten = out;
    return out === box ? null : out;
  }

  /**
   * 刚封完一句又补了个标点时，撤销那次封句、按合并后的标点重做。
   *   ？ + ！（或反之） → ？！  难以置信
   *   。 + 。（可再续）  → ...   省略号；第三个句号起结果不变，等于被吸收
   */
  _tryRollback(box, cfg, pack) {
    if (this.lastWritten === null || this.lastBody === null || this.lastStart < 0) return false;
    if (this.lastWritten !== this.committed) return false;   // 后面还有未封的尾巴
    if (box.length !== this.lastWritten.length + 1 || !box.startsWith(this.lastWritten)) return false;

    const added = box[box.length - 1];
    let redo = null;
    if (this.lastRaw.length === 1) {
      const had = this.lastRaw[0];
      if ((had === '？' && added === '！') || (had === '！' && added === '？')) redo = had + added;
    }
    if (redo === null && added === TERM_EAT && isAllEat(this.lastRaw)) redo = this.lastRaw + added;
    if (redo === null) return false;    // 已是组合标点，或补的标点凑不成一对

    // 颜文字的「避开上次」游标也要退回去，否则同一句只因多敲一个标点就换了张脸
    const body = this.lastBody;
    this.committed = this.committed.slice(0, this.lastStart);
    this.lastKaomoji = this.prevKaomoji;
    this._emit(body, redo, cfg, pack);
    this.lastWritten = this.committed;
    return true;
  }

  _emit(body, punct, cfg, pack) {
    const kept = normalizePunct(punct);
    this.lastStart = this.committed.length;
    this.lastBody = body;
    this.lastRaw = punct;

    if (!body.trim()) { this.committed += kept; return; }

    const meowed = transform(body, cfg);
    let kao = '';
    if (cfg.enableKaomoji && !K.endsWithKnownKaomoji(body, pack.lib)) {
      // 用【原文 body】检索关键词 —— 变换后「我」已成「本喵」，规则就匹配不到了
      this.prevKaomoji = this.lastKaomoji;
      kao = K.select(body, kept, pack, this.lastKaomoji);
      if (kao) this.lastKaomoji = kao;
    }
    this.committed += K.assemble(meowed, kept, kao);
  }
}
