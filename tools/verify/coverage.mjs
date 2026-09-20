/** 统计降级链各级的命中情况：交集能覆盖多少 (关键词情绪 × 句尾标点) 组合 */
import fs from 'node:fs';
import * as K from './kaomojilib.mjs';

const R = (p) => fs.readFileSync(p, 'utf8');
const PACK = K.pack(R('app/src/main/res/raw/kaomoji_lib.txt'),
                    R('app/src/main/res/raw/kaomoji_keywords.txt'));

const kwTags = [...new Set(PACK.keywords.flatMap((r) => r.tags))].sort();
const pTags = ['平静', '疑问', '兴奋', '惊讶', '无奈'];
const has = (tag) => PACK.lib.filter((e) => e.tags.includes(tag));

let n = 0, inter = 0, merged = 0, kwOnly = 0;
const holes = [];
for (const kt of kwTags) {
  for (const pt of pTags) {
    n++;
    const a = has(kt), b = has(pt);
    const both = a.filter((e) => b.includes(e));
    if (both.length) { inter++; continue; }
    const m = (PACK.merges || []).find((x) => x.kwTag === kt && x.punctTag === pt);
    if (m && has(m.result).length) { merged++; continue; }
    kwOnly++;
    holes.push(kt + ' × ' + pt + '  → 退回关键词组 ' + a.length + ' 条');
  }
}
console.log('关键词情绪 %d 个 × 句尾标点 %d 个 = %d 组合', kwTags.length, pTags.length, n);
console.log('  交集直接命中   %d  (%d%%)', inter, Math.round(inter / n * 100));
console.log('  靠合成表补上   %d', merged);
console.log('  退回关键词组   %d  ← 结果仍然有，只是不体现句尾语气', kwOnly);
console.log('  完全拿不到     0');
console.log('\n退回关键词组的组合：');
for (const h of holes) console.log('  ' + h);
