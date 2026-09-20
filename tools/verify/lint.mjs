/** 词库自检：关键词引用的情绪标签、合成表引用的标签，库里是否都有 */
import fs from 'node:fs';
import * as K from './kaomojilib.mjs';

const R = (p) => fs.readFileSync(p, 'utf8');
const libRaw = R('app/src/main/res/raw/kaomoji_lib.txt');
const kwRaw = R('app/src/main/res/raw/kaomoji_keywords.txt');
const PACK = K.pack(libRaw, kwRaw);

const emo = new Set(PACK.lib.flatMap((e) => e.tags));
const sym = new Set(PACK.lib.flatMap((e) => e.symbols));
let bad = 0;

for (const r of PACK.keywords) {
  for (const t of r.tags) {
    if (!emo.has(t)) { console.log('关键词「%s」引用了库里没有的情绪标签：%s', r.key, t); bad++; }
  }
}
for (const m of PACK.merges) {
  if (m.kwTag !== '*' && !emo.has(m.kwTag)) { console.log('@merge 左边的情绪不存在：%s', m.kwTag); bad++; }
  if (!K.ALL_SYMBOLS.includes(m.symbol)) { console.log('@merge 的符号不合法：%s', m.symbol); bad++; }
  if (!emo.has(m.result)) { console.log('@merge 的结果情绪不存在：%s', m.result); bad++; }
}
for (const s of sym) {
  if (!K.ALL_SYMBOLS.includes(s)) { console.log('段落头里有不认识的符号标签：%s', s); bad++; }
}
const dup = new Map();
for (const e of PACK.lib) dup.set(e.kao, (dup.get(e.kao) || 0) + 1);
for (const [k, n] of dup) if (n > 1) { console.log('颜文字重复 %d 次：%s', n, k); bad++; }

const counts = {};
for (const s of K.ALL_SYMBOLS) counts[s] = PACK.lib.filter((e) => e.symbols.includes(s)).length;
console.log('颜文字 %d / 情绪标签 %d / 符号标签 %d / 关键词 %d（其中排除短语 %d）/ 合成规则 %d',
  PACK.lib.length, emo.size, sym.size, PACK.keywords.length,
  PACK.keywords.filter(K.isExclusion).length, PACK.merges.length);
console.log('每个符号标签下的脸数：', counts);
console.log(bad ? '✗ ' + bad + ' 处问题' : '✓ 全部对得上');
process.exit(bad ? 1 : 0);
