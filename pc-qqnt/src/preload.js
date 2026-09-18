/**
 * preload：在发送那一刻改写消息内容。
 *
 * 原理：QQNT 渲染进程通过 ipcRenderer.send('IPC_UP_<winId>', meta, [cmdName, params])
 * 把调用送到主进程。发消息走的是 cmdName 里含 sendMsg 的那一条，参数里带着
 * msgElements 数组。我们在 send 真正发出去之前把其中的文本元素改掉。
 *
 * 两条硬性原则：
 *   1. 绝不抛异常 —— 整个钩子包在 try/catch 里，出任何问题都放行原始调用。
 *      这个钩子挂在发消息的主路径上，崩了就等于 QQ 发不出消息。
 *   2. 不假设 payload 的确切形状 —— 用深度搜索找 msgElements，而不是写死
 *      args[1][1].msgElements 这种下标。QQ 版本一变下标就废。
 *
 * 找不到钩子？打开调试模式（设置面板里），发一条消息，看控制台打印的 cmdName
 * 和 payload，就知道你这个版本长什么样了。
 */

'use strict';

const { contextBridge, ipcRenderer } = require('electron');
const engine = require('./engine.js');

const SLUG = 'qq_miao_nt';

let config = Object.assign({}, engine.DEFAULT_CONFIG);
let debug = false;
/** 调试日志环形缓冲，供设置面板查看 */
const logs = [];

function log(...parts) {
  const line = '[' + new Date().toLocaleTimeString() + '] ' + parts
    .map((p) => (typeof p === 'string' ? p : safeJson(p)))
    .join(' ');
  logs.push(line);
  if (logs.length > 200) logs.shift();
  if (debug) {
    // eslint-disable-next-line no-console
    console.log('%c[喵喵助手]', 'color:#c06a22;font-weight:bold', ...parts);
  }
}

function safeJson(o) {
  try {
    return JSON.stringify(o, (k, v) => (typeof v === 'string' && v.length > 200 ? v.slice(0, 200) + '…' : v));
  } catch (e) {
    return String(o);
  }
}

// ------------------------------------------------------------ payload 处理

/**
 * 在任意深度的对象里找出所有 msgElements 数组。
 * 不写死下标，QQ 改版也不容易失效。
 */
function findMsgElements(node, found, depth) {
  if (!node || depth > 8) return found;
  if (Array.isArray(node)) {
    for (const item of node) findMsgElements(item, found, depth + 1);
    return found;
  }
  if (typeof node !== 'object') return found;
  if (Array.isArray(node.msgElements)) found.push(node.msgElements);
  for (const key of Object.keys(node)) {
    if (key === 'msgElements') continue;
    findMsgElements(node[key], found, depth + 1);
  }
  return found;
}

/**
 * 改写一组消息元素里的纯文本。
 *
 * elementType === 1 是文本元素。但 @某人 也是文本元素，靠 atType 区分 ——
 * 带 atType 的必须跳过，否则 @ 会失效（这正是安卓原版踩过的坑）。
 *
 * @returns {number} 实际改写了几个元素
 */
function rewriteElements(elements) {
  let changed = 0;
  for (const el of elements) {
    if (!el || el.elementType !== 1) continue;
    const te = el.textElement;
    if (!te || typeof te.content !== 'string' || !te.content) continue;
    if (te.atType) continue;                 // @提及，不动
    const out = engine.transform(te.content, config);
    if (out !== te.content) {
      log('改写:', te.content, '->', out);
      te.content = out;
      changed++;
    }
  }
  return changed;
}

/** payload 是不是一次发消息调用 */
function looksLikeSendMsg(args) {
  for (const a of args) {
    if (typeof a === 'string' && /sendMsg/i.test(a)) return true;
    if (Array.isArray(a)) {
      for (const x of a) if (typeof x === 'string' && /sendMsg/i.test(x)) return true;
    }
    if (a && typeof a === 'object') {
      const cmd = a.cmdName || a.eventName;
      if (typeof cmd === 'string' && /sendMsg/i.test(cmd)) return true;
    }
  }
  return false;
}

/** 从 payload 里挖出 cmdName，仅用于调试打印 */
function peekCmdName(args) {
  for (const a of args) {
    if (Array.isArray(a) && typeof a[0] === 'string') return a[0];
    if (a && typeof a === 'object' && typeof a.cmdName === 'string') return a.cmdName;
  }
  return '(未知)';
}

/**
 * 拦截入口。
 * @returns 永远不抛；出错只记录，调用照常放行。
 */
function interceptUp(channel, args) {
  try {
    if (!config.enabled) return;

    if (debug) {
      log('IPC_UP', channel, peekCmdName(args));
    }

    if (!looksLikeSendMsg(args)) return;

    const groups = findMsgElements(args, [], 0);
    if (groups.length === 0) {
      if (debug) log('命中 sendMsg 但没找到 msgElements，payload =', args);
      return;
    }
    let total = 0;
    for (const g of groups) total += rewriteElements(g);
    if (debug) log('本次改写元素数:', total);
  } catch (e) {
    // 绝不让钩子影响发送
    log('拦截器异常（已忽略，消息照常发送）:', String(e && e.stack ? e.stack : e));
  }
}

// ------------------------------------------------------------------ 挂钩

const IPC_UP = /^IPC_UP_\d+$/;

function install() {
  const origSend = ipcRenderer.send;
  ipcRenderer.send = function (channel, ...args) {
    if (typeof channel === 'string' && IPC_UP.test(channel)) {
      interceptUp(channel, args);
    }
    return origSend.apply(this, [channel, ...args]);
  };

  // 有的版本走 invoke，一并挂上
  const origInvoke = ipcRenderer.invoke;
  ipcRenderer.invoke = function (channel, ...args) {
    if (typeof channel === 'string' && IPC_UP.test(channel)) {
      interceptUp(channel, args);
    }
    return origInvoke.apply(this, [channel, ...args]);
  };

  log('钩子已安装');
}

try {
  install();
} catch (e) {
  // eslint-disable-next-line no-console
  console.error('[喵喵助手] 安装钩子失败:', e);
}

// -------------------------------------------------------------- 对渲染层

contextBridge.exposeInMainWorld('qq_miao_nt', {
  slug: SLUG,
  defaults: engine.DEFAULT_CONFIG,

  /** 渲染进程读到持久化配置后推给我们 */
  setConfig: (c) => {
    config = Object.assign({}, engine.DEFAULT_CONFIG, c || {});
    log('配置已更新');
  },
  getConfig: () => Object.assign({}, config),

  /** 设置面板的「测试」按钮：用传入的配置直接跑一遍 */
  preview: (text, c) => engine.transform(text, Object.assign({}, config, c || {})),

  setDebug: (v) => {
    debug = !!v;
    log('调试模式:', debug ? '开' : '关');
  },
  getLogs: () => logs.slice(),
  clearLogs: () => {
    logs.length = 0;
  },
});
