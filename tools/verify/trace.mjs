/** 把一句话的选择过程逐级打印出来，用来排查「为什么挑了这张脸」 */
import fs from 'node:fs';
import * as K from './kaomojilib.mjs';

const R = (p) => fs.readFileSync(p, 'utf8');
const PACK = K.pack(R('app/src/main/res/raw/kaomoji_lib.txt'),
                    R('app/src/main/res/raw/kaomoji_keywords.txt'));

for (const [body, punct] of [
  ['加了个符号和关键词判断的语气检查', '！'],
  ['加了个符号和关键词判断的语气检查', ''],
  ['改完了吗', '？！'],
]) {
  const kw = K.scanLastKeyword(body, PACK.keywords);
  const sym = K.symbolOf(punct);
  const byKw = kw ? PACK.lib.filter((e) => kw.tags.some((t) => e.tags.includes(t))) : [];
  const bySym = PACK.lib.filter((e) => e.symbols.includes(sym));
  const inter = byKw.filter((e) => bySym.includes(e));
  const picked = K.select(body, punct, PACK, null);
  console.log('句子：%s%s', body, punct || '。(被吃掉)');
  console.log('  命中关键词  : %s', kw ? kw.key + ' → ' + kw.tags.join(',') : '【没有】');
  console.log('  句尾符号    : %s', sym);
  console.log('  情绪组      : %d 条', byKw.length);
  console.log('  符号组      : %d 条', bySym.length);
  console.log('  交集        : %d 条', inter.length);
  // 通配规则不需要关键词也会生效，所以这里不能要求 kw 非空
  const kwTags = kw ? kw.tags : [];
  const merged = inter.length ? null
    : (PACK.merges || []).find((m) => m.symbol === sym
        && (m.kwTag === '*' || kwTags.includes(m.kwTag))
        && PACK.lib.some((e) => e.tags.includes(m.result)));
  const level = inter.length ? '交集'
    : merged ? '合成表 ' + merged.kwTag + '+' + merged.symbol + '=' + merged.result
    : byKw.length ? '只看情绪'
    : PACK.defaultTag ? '默认组 ' + PACK.defaultTag + ' ∩ 符号'
    : '只看符号 ← 全部情绪都在里面';
  console.log('  实际走的那级: %s', level);
  const e = PACK.lib.find((x) => x.kao === picked);
  console.log('  选中        : %s   情绪=%s  符号=%s', picked, e.tags.join(','), e.symbols.join(','));
  console.log();
}
