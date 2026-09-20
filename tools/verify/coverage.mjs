/** 统计降级链各级的命中情况：交集能覆盖多少 (关键词情绪 × 句尾标点) 组合 */
import fs from 'node:fs';
import * as K from './kaomojilib.mjs';

const R = (p) => fs.readFileSync(p, 'utf8');
const PACK = K.pack(R('app/src/main/res/raw/kaomoji_lib.txt'),
                    R('app/src/main/res/raw/kaomoji_keywords.txt'));

const kwTags = [...new Set(PACK.keywords.flatMap((r) => r.tags))].sort();
const symbols = ['句号', '问号', '叹号', '问叹', '省略号'];
const byTag = (t) => PACK.lib.filter((e) => e.tags.includes(t));
const bySym = (t) => PACK.lib.filter((e) => e.symbols.includes(t));

let n = 0, inter = 0, merged = 0, kwOnly = 0;
const holes = [];
for (const kt of kwTags) {
  for (const pt of symbols) {
    n++;
    const a = byTag(kt), b = bySym(pt);
    const both = a.filter((e) => b.includes(e));
    if (both.length) { inter++; continue; }
    const m = (PACK.merges || []).find((x) => (x.kwTag === kt || x.kwTag === '*') && x.symbol === pt);
    if (m && byTag(m.result).length) { merged++; continue; }
    kwOnly++;
    holes.push(kt + ' × ' + pt + '  → 退回情绪组 ' + a.length + ' 条');
  }
}
console.log('情绪标签 %d 个 × 符号标签 %d 个 = %d 组合', kwTags.length, symbols.length, n);
console.log('  交集直接命中   %d  (%d%%)', inter, Math.round(inter / n * 100));
console.log('  靠合成表补上   %d', merged);
console.log('  退回情绪组     %d  ← 结果仍然有，只是不体现句尾语气', kwOnly);
console.log('  完全拿不到     0');
console.log('\n退回关键词组的组合：');
for (const h of holes) console.log('  ' + h);

// 没有关键词是最常见的情况，单独报一下 —— 这一级出过 bug：
// 拆轴之后它从「平静组」变成了「所有配得上句尾的脸」，平常话会抽到哭脸。
console.log('\n没有情绪关键词时（最常见的情况）：');
if (!PACK.defaultTag) {
  console.log('  ✗ 没配 @default，会退到符号组 —— 哭脸怒脸都在候选里');
} else {
  const d = byTag(PACK.defaultTag);
  console.log('  默认组 = %s，%d 条', PACK.defaultTag, d.length);
  for (const s of symbols) {
    const fit = d.filter((e) => e.symbols.includes(s)).length;
    const via = fit ? '' : '  （靠合成表或整组兜底）';
    console.log('    句尾 ' + s.padEnd(4) + ' → ' + fit + ' 条可选' + via);
  }
}
