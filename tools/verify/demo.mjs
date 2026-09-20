import fs from 'node:fs';
import { parseRules } from './transcribe.mjs';
import * as K from './kaomojilib.mjs';
import { MeowCommitter } from './committer.mjs';

const R = (p) => fs.readFileSync(p, 'utf8');
const PACK = K.pack(R('app/src/main/res/raw/kaomoji_lib.txt'),
                    R('app/src/main/res/raw/kaomoji_keywords.txt'));
const CFG = {
  suffix: '喵', suffixBeforePunct: true, enableSuffix: true,
  maxSuffixPerMessage: 0, minSentenceLength: 2,
  rules: parseRules('我们=我们这群喵\n我=本喵\n你=主人'),
  enableKaomoji: true, protectRegions: true,
};

// 逐键喂进去，模拟真实打字
function type(s) {
  const c = new MeowCommitter();
  let box = '';
  for (const ch of s) {
    box += ch;
    const out = c.onTextChanged(box, CFG, PACK);
    if (out !== null) box = out;
  }
  return box;
}

console.log('库：' + PACK.lib.length + ' 条颜文字 / ' + PACK.merges.length
          + ' 条合成规则 / ' + PACK.keywords.length + ' 条关键词\n');
for (const s of ['什么意思！', '这什么意思？', '真的假的？！', '我好累。。',
                 '今天下雨。。', '谢谢你。', '别难过。', '我今天很开心。',
                 '在吗？', '我不知道...', '等我一下。']) {
  console.log(s.padEnd(12) + '→  ' + type(s));
}
