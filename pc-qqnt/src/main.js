/**
 * 主进程脚本。
 *
 * 这个插件的全部工作都在 preload（IPC 钩子）和 renderer（设置面板）里完成，
 * 主进程无事可做。保留此文件是因为 LiteLoader 的 injects 里声明了它。
 */

'use strict';

// eslint-disable-next-line no-console
console.log('[喵喵助手] 主进程已加载');
