/**
 * MeowCommitter.java 的逐行 JS 转写。
 * 结构与 Java 一一对应，改 Java 记得同步改这里。
 */

import { transform } from './transcribe.mjs';
import * as K from './kaomojilib.mjs';

const TERM_EAT = '。';
const TERM_KEEP = '？！';
const isTerm = (c) => c === TERM_EAT || TERM_KEEP.includes(c);

export class MeowCommitter {
  constructor() {
    this.committed = '';
    this.lastWritten = null;
    this.lastStart = -1;
    this.lastBody = null;
    this.lastPunct = '';
    this.lastKaomoji = null;
  }

  reset() {
    this.committed = '';
    this.lastWritten = null;
    this.lastStart = -1;
    this.lastBody = null;
    this.lastPunct = '';
    // lastKaomoji 刻意不清，跨消息也避开重复
  }

  isEcho(box) {
    return box !== null && box !== undefined && box === this.lastWritten;
  }

  onTextChanged(box, cfg, lib, keywords) {
    if (box === null || box === undefined) return null;
    if (this.isEcho(box)) return null;

    if (this._tryRollback(box, cfg, lib, keywords)) return this.lastWritten;

    if (!box.startsWith(this.committed)) {
      this.committed = '';
      this.lastStart = -1;
      this.lastBody = null;
      this.lastPunct = '';
    }

    let rest = box.slice(this.committed.length);
    let fired = 0;
    for (;;) {
      let i = -1;
      for (let k = 0; k < rest.length; k++) if (isTerm(rest[k])) { i = k; break; }
      if (i < 0) break;
      let j = i;
      while (j < rest.length && isTerm(rest[j])) j++;
      this._emit(rest.slice(0, i), rest.slice(i, j), cfg, lib, keywords);
      rest = rest.slice(j);
      fired++;
    }
    if (fired === 0) return null;

    const out = this.committed + rest;
    this.lastWritten = out;
    return out === box ? null : out;
  }

  /** 「？」后紧跟「！」（或反之）时撤销上次封句、按组合标点重做 */
  _tryRollback(box, cfg, lib, keywords) {
    if (this.lastWritten === null || this.lastBody === null || this.lastStart < 0) return false;
    if (this.lastWritten !== this.committed) return false;   // 后面还有未封的尾巴
    if (this.lastPunct.length !== 1) return false;           // 已是组合标点
    if (box.length !== this.lastWritten.length + 1 || !box.startsWith(this.lastWritten)) return false;

    const added = box[box.length - 1];
    const had = this.lastPunct[0];
    const complement = (had === '？' && added === '！') || (had === '！' && added === '？');
    if (!complement) return false;

    const body = this.lastBody;
    this.committed = this.committed.slice(0, this.lastStart);
    this._emit(body, had + added, cfg, lib, keywords);
    this.lastWritten = this.committed;
    return true;
  }

  _emit(body, punct, cfg, lib, keywords) {
    const kept = [...punct].filter((c) => c !== TERM_EAT).join('');
    this.lastStart = this.committed.length;
    this.lastBody = body;
    this.lastPunct = kept;

    if (!body.trim()) { this.committed += kept; return; }

    const meowed = transform(body, cfg);
    let kao = '';
    if (cfg.enableKaomoji && !K.endsWithKnownKaomoji(body, lib)) {
      // 用【原文 body】检索关键词 —— 变换后「我」已成「本喵」，规则就匹配不到了
      kao = K.select(body, kept, lib, keywords, this.lastKaomoji);
      if (kao) this.lastKaomoji = kao;
    }
    this.committed += K.assemble(meowed, kept, kao);
  }
}
