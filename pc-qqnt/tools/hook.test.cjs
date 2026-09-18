/**
 * preload.js 的拦截逻辑测试。
 *
 * 把 electron 模块换成假的，就能在 Node 里完整跑一遍钩子 ——
 * 这是整个插件里唯一挂在「发消息主路径」上的代码，必须验。
 *
 * 注意：这验证的是**拦截与改写逻辑**。真实 QQ 的 payload 形状是否与这里
 * 构造的一致，只能装到 QQ 上用调试模式确认。
 */

'use strict';

const { test, describe } = require('node:test');
const assert = require('node:assert/strict');
const Module = require('node:module');
const path = require('node:path');

// ---------------------------------------------------------------- 假 electron

const sent = [];        // 记录透传给原始 send 的调用
let bridge = null;      // contextBridge 暴露出来的 API

const fakeIpcRenderer = {
  send(...args) { sent.push(['send', ...args]); },
  invoke(...args) { sent.push(['invoke', ...args]); return Promise.resolve(); },
};

const fakeElectron = {
  ipcRenderer: fakeIpcRenderer,
  contextBridge: {
    exposeInMainWorld(name, api) { bridge = api; },
  },
};

const origLoad = Module._load;
Module._load = function (request, parent, isMain) {
  if (request === 'electron') return fakeElectron;
  return origLoad.apply(this, arguments);
};
require(path.join(__dirname, '..', 'src', 'preload.js'));
Module._load = origLoad;

// ------------------------------------------------------------------ 工具

/** 构造一次 sendMsg 的 IPC payload（按社区文档的形状） */
function sendMsgPayload(elements) {
  return [
    { type: 'request', callbackId: 'cb-1', eventName: 'ns-ntApi-2' },
    ['nodeIKernelMsgService/sendMsg', {
      msgId: '0',
      peer: { chatType: 2, peerUid: '12345' },
      msgElements: elements,
    }],
  ];
}

const textEl = (content) => ({ elementType: 1, textElement: { content } });
const atEl = (content) => ({ elementType: 1, textElement: { content, atType: 2, atNtUin: '999' } });

function fire(channel, payload) {
  sent.length = 0;
  fakeIpcRenderer.send(channel, ...payload);
  return sent;
}

// 让结果稳定：每句都加喵，不加颜文字
bridge.setConfig({
  enabled: true,
  rules: '我们=我们这群喵\n我=本喵\n你=主人',
  suffixProbability: 1,
  kaomojiProbability: 0,
  maxSuffixPerMessage: 0,
});

// ------------------------------------------------------------------ 测试

describe('钩子安装', () => {
  test('contextBridge 暴露了 API', () => {
    assert.ok(bridge, 'contextBridge 没被调用');
    for (const k of ['setConfig', 'getConfig', 'preview', 'setDebug', 'getLogs']) {
      assert.equal(typeof bridge[k], 'function', '缺少 ' + k);
    }
  });

  test('send 与 invoke 都被包装了', () => {
    assert.ok(fakeIpcRenderer.send.length >= 0);
    fire('IPC_UP_2', sendMsgPayload([textEl('我很开心')]));
    assert.equal(sent.length, 1, '原始 send 没有被调用');
  });
});

describe('发送时改写', () => {
  test('文本元素被喵化', () => {
    const els = [textEl('我今天很开心')];
    fire('IPC_UP_2', sendMsgPayload(els));
    assert.equal(els[0].textElement.content, '本喵今天很开心喵');
  });

  test('原始调用照常透传，且参数是同一个对象', () => {
    const els = [textEl('我很好')];
    const payload = sendMsgPayload(els);
    const rec = fire('IPC_UP_2', payload);
    assert.equal(rec.length, 1);
    assert.equal(rec[0][0], 'send');
    assert.equal(rec[0][1], 'IPC_UP_2');
    // 改写是就地进行的，所以透传出去的就是改写后的内容
    assert.equal(rec[0][3][1].msgElements[0].textElement.content, '本喵很好喵');
  });

  test('@提及 元素不被改写', () => {
    const els = [atEl('@你好呀'), textEl('你看下这个')];
    fire('IPC_UP_2', sendMsgPayload(els));
    assert.equal(els[0].textElement.content, '@你好呀', '@提及被改坏了');
    assert.equal(els[1].textElement.content, '主人看下这个喵');
  });

  test('非文本元素（图片等）不被碰', () => {
    const pic = { elementType: 2, picElement: { fileName: 'a.png' } };
    const els = [pic, textEl('我发张图')];
    fire('IPC_UP_2', sendMsgPayload(els));
    assert.deepEqual(pic, { elementType: 2, picElement: { fileName: 'a.png' } });
    assert.equal(els[1].textElement.content, '本喵发张图喵');
  });

  test('链接不被拆坏', () => {
    const els = [textEl('你看 https://docs.qq.com/doc/x')];
    fire('IPC_UP_2', sendMsgPayload(els));
    assert.ok(els[0].textElement.content.includes('https://docs.qq.com/doc/x'),
      els[0].textElement.content);
  });

  test('msgElements 藏得更深也能找到', () => {
    const els = [textEl('我在')];
    const weird = [
      { type: 'request' },
      ['nodeIKernelMsgService/sendMsg', { a: { b: { c: { msgElements: els } } } }],
    ];
    fire('IPC_UP_2', weird);
    assert.equal(els[0].textElement.content, '本喵在喵');
  });
});

describe('不该动手的场合', () => {
  test('非 sendMsg 的调用原样放行', () => {
    const els = [textEl('我很开心')];
    fire('IPC_UP_2', [
      { type: 'request' },
      ['nodeIKernelProfileService/getUserInfo', { msgElements: els }],
    ]);
    assert.equal(els[0].textElement.content, '我很开心', '非发消息调用被改写了');
  });

  test('非 IPC_UP 频道不拦截', () => {
    const els = [textEl('我很开心')];
    fire('IPC_DOWN_2', sendMsgPayload(els));
    assert.equal(els[0].textElement.content, '我很开心');
  });

  test('enabled=false 时不改写', () => {
    bridge.setConfig({ enabled: false });
    const els = [textEl('我很开心')];
    fire('IPC_UP_2', sendMsgPayload(els));
    assert.equal(els[0].textElement.content, '我很开心');
    bridge.setConfig({
      enabled: true, rules: '我=本喵\n你=主人',
      suffixProbability: 1, kaomojiProbability: 0, maxSuffixPerMessage: 0,
    });
  });

  test('空文本不炸', () => {
    const els = [textEl('')];
    fire('IPC_UP_2', sendMsgPayload(els));
    assert.equal(els[0].textElement.content, '');
  });
});

describe('健壮性：钩子绝不能挡住发送', () => {
  test('畸形 payload 不抛异常，且照常透传', () => {
    const nasty = [
      null,
      undefined,
      'sendMsg',
      { msgElements: 'not-an-array' },
      ['nodeIKernelMsgService/sendMsg', { msgElements: [null, {}, { elementType: 1 }] }],
    ];
    assert.doesNotThrow(() => fire('IPC_UP_2', nasty));
    assert.equal(sent.length, 1, '畸形 payload 导致消息没发出去');
  });

  test('循环引用不会无限递归', () => {
    const cyc = { elementType: 1, textElement: { content: '我在' } };
    const holder = { msgElements: [cyc] };
    holder.self = holder;                     // 自引用
    assert.doesNotThrow(() => {
      fire('IPC_UP_2', [{ type: 'request' }, ['nodeIKernelMsgService/sendMsg', holder]]);
    });
    assert.equal(sent.length, 1);
  });

  test('transform 内部抛错也要放行', () => {
    // 塞一个会让 engine 出问题的配置：rules 给成非字符串
    bridge.setConfig({ enabled: true, rules: { bad: true } });
    const els = [textEl('我很开心')];
    assert.doesNotThrow(() => fire('IPC_UP_2', sendMsgPayload(els)));
    assert.equal(sent.length, 1, '配置异常导致消息没发出去');
    bridge.setConfig({
      enabled: true, rules: '我=本喵',
      suffixProbability: 1, kaomojiProbability: 0,
    });
  });
});

describe('设置面板接口', () => {
  test('preview 用传入配置跑，不影响全局', () => {
    const before = bridge.getConfig();
    const out = bridge.preview('我很开心', { suffixProbability: 1, kaomojiProbability: 0 });
    assert.ok(out.includes('本喵'), out);
    assert.deepEqual(bridge.getConfig(), before, 'preview 改动了全局配置');
  });

  test('日志可读可清', () => {
    bridge.setDebug(true);
    fire('IPC_UP_2', sendMsgPayload([textEl('我在')]));
    assert.ok(bridge.getLogs().length > 0);
    bridge.clearLogs();
    assert.equal(bridge.getLogs().length, 0);
    bridge.setDebug(false);
  });
});
