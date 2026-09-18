/**
 * 渲染进程：设置面板 + 把持久化配置推给 preload。
 *
 * 真正的改写发生在 preload 的 IPC 钩子里，这里只管配置和预览。
 */

const SLUG = 'qq_miao_nt';
const api = window.qq_miao_nt;

function loadConfig() {
  const defaults = api ? api.defaults : {};
  try {
    return Object.assign({}, defaults, LiteLoader.api.config.get(SLUG, defaults));
  } catch (e) {
    return Object.assign({}, defaults);
  }
}

function saveConfig(cfg) {
  try {
    LiteLoader.api.config.set(SLUG, cfg);
  } catch (e) {
    console.error('[喵喵助手] 保存配置失败', e);
  }
  if (api) api.setConfig(cfg);
}

// 插件一加载就把配置推给 preload —— preload 先于渲染层运行，
// 在这之前它用的是默认配置。
let config = loadConfig();
if (api) api.setConfig(config);

// ------------------------------------------------------------------ 设置面板

const CSS = `
.miao-sec { margin-bottom: 16px; }
.miao-row { display:flex; align-items:center; justify-content:space-between; gap:12px; padding:8px 0; }
.miao-row label { flex:none; }
.miao-hint { font-size:12px; opacity:.6; margin-top:4px; line-height:1.6; white-space:pre-wrap; }
.miao-ta { width:100%; min-height:96px; box-sizing:border-box; padding:8px 10px;
  font-family:ui-monospace,Consolas,monospace; font-size:13px; line-height:1.6;
  border-radius:4px; border:1px solid var(--border_01,#ddd);
  background:var(--bg_bottom_standard,#fff); color:inherit; resize:vertical; }
.miao-out { min-height:40px; padding:8px 10px; border-radius:4px;
  border:1px solid var(--border_01,#ddd); background:var(--bg_bottom_standard,#fff);
  white-space:pre-wrap; word-break:break-word; font-size:13px; line-height:1.6; }
.miao-range { flex:1; }
.miao-val { font-family:ui-monospace,Consolas,monospace; font-size:12px; opacity:.7; min-width:44px; text-align:right; }
.miao-logs { max-height:180px; overflow:auto; font-family:ui-monospace,Consolas,monospace;
  font-size:11px; line-height:1.6; white-space:pre-wrap; opacity:.8; }
.miao-warn { font-size:12px; line-height:1.7; padding:10px 12px; border-radius:4px;
  border-left:3px solid #c06a22; background:rgba(192,106,34,.08); }
`;

function h(tag, attrs, ...kids) {
  const el = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs || {})) {
    if (k === 'class') el.className = v;
    else if (k.startsWith('on')) el.addEventListener(k.slice(2).toLowerCase(), v);
    else el.setAttribute(k, v);
  }
  for (const kid of kids.flat()) {
    if (kid == null) continue;
    el.appendChild(typeof kid === 'string' ? document.createTextNode(kid) : kid);
  }
  return el;
}

function section(title, ...body) {
  return h('setting-section', { 'data-title': title },
    h('setting-panel', {}, h('setting-list', { 'data-direction': 'column' }, ...body)));
}

function switchRow(label, key, onChange) {
  const sw = h('setting-switch', {});
  if (config[key]) sw.setAttribute('is-active', '');
  sw.addEventListener('click', () => {
    const on = !sw.hasAttribute('is-active');
    sw.toggleAttribute('is-active', on);
    config[key] = on;
    saveConfig(config);
    if (onChange) onChange(on);
  });
  return h('setting-item', { class: 'miao-row' }, h('label', {}, label), sw);
}

function rangeRow(label, key, fmt) {
  const val = h('span', { class: 'miao-val' }, fmt(config[key]));
  const input = h('input', {
    type: 'range', min: '0', max: '100', class: 'miao-range',
    value: String(Math.round((config[key] ?? 0) * 100)),
  });
  input.addEventListener('input', () => {
    config[key] = Number(input.value) / 100;
    val.textContent = fmt(config[key]);
    saveConfig(config);
  });
  return h('setting-item', { class: 'miao-row' }, h('label', {}, label), input, val);
}

function textareaBlock(key, placeholder, hint) {
  const ta = h('textarea', { class: 'miao-ta', placeholder, spellcheck: 'false' });
  ta.value = config[key] || '';
  ta.addEventListener('change', () => {
    config[key] = ta.value;
    saveConfig(config);
  });
  return h('div', { class: 'miao-sec' }, ta, hint ? h('div', { class: 'miao-hint' }, hint) : null);
}

export function onSettingWindowCreated(view) {
  view.appendChild(h('style', {}, CSS));

  // —— 开关
  const switches = section('功能开关',
    switchRow('启用（发送时自动喵化）', 'enabled'),
    switchRow('喵放在标点之前（真好喵！/ 真好！喵）', 'suffixBeforePunct'),
    rangeRow('每句加喵的概率', 'suffixProbability', (v) => Math.round(v * 100) + '%'),
    rangeRow('颜文字概率', 'kaomojiProbability', (v) => Math.round(v * 100) + '%'),
  );

  // —— 规则
  const rules = section('替换规则',
    textareaBlock('rules', '每行一条：原文=替换',
      '每行一条，格式 原文=替换，# 开头的行会被忽略。\n'
      + '规则按最长优先匹配且只扫描一趟 ——「我们=我们这群喵」不会把自己的结果再替换一遍。'),
  );

  const kaomoji = section('颜文字库',
    textareaBlock('kaomoji', '每行一个颜文字', '留空则不加颜文字。'),
  );

  // —— 测试
  const testIn = h('textarea', {
    class: 'miao-ta', spellcheck: 'false',
    placeholder: '在这里输入，看看发出去会变成什么样',
  });
  testIn.value = '我今天下班早，你要不要一起吃饭\n这个文档你看一下 https://docs.qq.com/doc/abc';
  const testOut = h('div', { class: 'miao-out' });
  const run = () => {
    testOut.textContent = api ? api.preview(testIn.value, config) : '(preload 未加载)';
  };
  testIn.addEventListener('input', run);
  const testSec = section('测试',
    h('div', { class: 'miao-sec' }, testIn,
      h('div', { class: 'miao-hint' }, '概率项每次结果可能不同，多点几次看看。'),
      h('div', { style: 'height:8px' }),
      testOut,
      h('div', { style: 'height:8px' }),
      h('button', { class: 'q-button q-button--small', onclick: run }, '重新生成')),
  );

  // —— 调试
  const logBox = h('div', { class: 'miao-logs' });
  const refreshLogs = () => {
    logBox.textContent = api ? api.getLogs().slice(-60).join('\n') : '';
    logBox.scrollTop = logBox.scrollHeight;
  };
  let timer = null;
  const dbgSwitch = h('setting-switch', {});
  dbgSwitch.addEventListener('click', () => {
    const on = !dbgSwitch.hasAttribute('is-active');
    dbgSwitch.toggleAttribute('is-active', on);
    if (api) api.setDebug(on);
    if (on) {
      refreshLogs();
      timer = setInterval(refreshLogs, 1000);
    } else if (timer) {
      clearInterval(timer);
      timer = null;
    }
  });

  const debugSec = section('调试',
    h('setting-item', { class: 'miao-row' },
      h('label', {}, '打印 IPC 调用（找不到钩子时用）'), dbgSwitch),
    h('div', { class: 'miao-hint' },
      '打开后发一条消息，下面会列出经过的 cmdName。若始终看不到含 sendMsg 的调用，\n'
      + '说明你这个 QQ 版本的通道名不同，把日志发给开发者即可对症修改。'),
    h('div', { style: 'height:8px' }),
    logBox,
    h('div', { style: 'height:8px' }),
    h('button', {
      class: 'q-button q-button--small',
      onclick: () => { if (api) api.clearLogs(); refreshLogs(); },
    }, '清空日志'),
  );

  const notice = section('说明',
    h('div', { class: 'miao-warn' },
      '改写发生在消息发送的那一刻，输入框里始终是你自己打的原文。\n'
      + '另：修改 QQ 客户端行为违反腾讯用户协议，QQ 安全中心可能判定为外挂。'
      + '自用请酌情，不建议分发。'),
  );

  view.appendChild(switches);
  view.appendChild(rules);
  view.appendChild(kaomoji);
  view.appendChild(testSec);
  view.appendChild(debugSec);
  view.appendChild(notice);
  run();
}
