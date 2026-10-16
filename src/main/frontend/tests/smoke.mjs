/**
 * 页面冒烟：**起真 jar、用真浏览器打开七个页面、把 JS 报错与页面上的失败面板抓出来**。
 *
 * 单测只能证明纯函数对；它证明不了「这行代码跑起来不炸」（取值的时机、模板里的表达式、
 * 调了一个后端没有的出口）。所以这一条做的是真事：开页面、听控制台、看那一条红字。
 *
 * 用法：node tests/smoke.mjs <jar路径> [端口]
 * 退出码：0 = 全过；1 = 有页面有问题（或服务没起来）。
 */
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
// 本机的 playwright 装在参考实现那边，直接按绝对路径 import（这边不重复装）。
import { chromium } from 'file:///E:/specflow-ai/src/main/frontend/node_modules/playwright/index.mjs';

const JAR = process.argv[2] || 'E:/specflow-v2/target/specflow.jar';
const PORT = Number(process.argv[3] || 3802);
const BASE = 'http://127.0.0.1:' + PORT;
const ROUTES = ['/', '/workbench', '/env', '/probe', '/cases', '/dev', '/test'];
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));

/** 一个最小的目标项目：五格要用的文件都在。 */
function makeProject() {
  const root = path.join(os.tmpdir(), 'sf-v2-smoke-' + Date.now());
  fs.mkdirSync(path.join(root, '.specflow'), { recursive: true });
  fs.writeFileSync(path.join(root, '.specflow/project.yaml'),
    'build:\n  compile: "echo ok"\n\nllm:\n  base-url: http://127.0.0.1:1\n  model: stub\n'
    + '  api-key-env: SPECFLOW_SMOKE_KEY\n');
  fs.writeFileSync(path.join(root, '.specflow/local.env'), 'SPECFLOW_SMOKE_KEY=sk-smoke\n');
  fs.writeFileSync(path.join(root, '.specflow/env.yaml'), 'image: eclipse-temurin:17\nworkdir: /work\n');
  fs.writeFileSync(path.join(root, 'README.md'), '# smoke\n');
  return root;
}

const project = makeProject();
const log = [];
const server = spawn('java', ['-jar', JAR, 'web', '--port', String(PORT), '--no-open', '-p', project],
  { stdio: 'pipe' });
server.stdout.on('data', chunk => log.push(String(chunk)));
server.stderr.on('data', chunk => log.push(String(chunk)));
server.on('error', error => log.push('起不了 java：' + error.message));

const problems = [];
let browser = null;

async function waitForServer() {
  const deadline = Date.now() + 120_000;
  for (;;) {
    try {
      const response = await fetch(BASE + '/api/state');
      if (response.ok) {
        return;
      }
    } catch (e) { /* 还没起来 */ }
    if (Date.now() > deadline) {
      throw new Error('服务 120 秒没起来。日志尾部：\n' + log.join('').slice(-800));
    }
    await sleep(500);
  }
}

try {
  await waitForServer();
  browser = await chromium.launch({ channel: 'chrome', headless: true });
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  const errors = [];
  page.on('console', message => {
    if (message.type() === 'error') {
      errors.push('console: ' + message.text());
    }
  });
  page.on('pageerror', error => errors.push('pageerror: ' + error.message));

  const report = [];
  for (const route of ROUTES) {
    errors.length = 0;
    await page.goto(BASE + route, { waitUntil: 'networkidle' });
    await sleep(700);
    // 页面自己那一条失败面板（失败就地显示）也算问题：红字留在屏幕上就是要人看见
    const shown = await page.evaluate(() => Array.from(document.querySelectorAll('[data-failure]'))
      .map(one => (one.textContent || '').replace(/\s+/g, ' ').trim().slice(0, 120))
      .filter(Boolean));
    const seen = await page.evaluate(() => ({
      shell: Boolean(document.querySelector('.shell')),
      rail: document.querySelectorAll('.rail-item').length,
      title: (document.querySelector('h1') || {}).textContent || '',
      status: (document.querySelector('.status') || document.querySelector('.statusline') || {}).textContent || '',
      panels: document.querySelectorAll('.sec, .fold').length,
      folds: document.querySelectorAll('details.fold').length,
      chips: document.querySelectorAll('.chips').length,
      log: document.querySelectorAll('.log > li').length,
    }));
    report.push({ route, errors: [...errors], shown, seen });
    if (errors.length || shown.length) {
      problems.push(route + '：' + [...errors, ...shown].join(' | '));
    }
    if (!seen.shell) {
      problems.push(route + '：外壳没渲染出来（#app 是空的？）');
    }
  }

  // 日志倒序：最新的一条在最上面。抽一行有 at 的时刻比大小。
  const ordered = await page.evaluate(async () => {
    const days = ['env', 'probe', 'cases', 'dev', 'test'];
    for (const day of days) {
      const response = await fetch('/api/node/' + day + '/events?from=0');
      if (!response.ok) {
        continue;
      }
      const view = await response.json();
      const list = (view.events || []).map(one => one.at).filter(Boolean);
      if (list.length > 1) {
        return { node: day, first: list[0], last: list[list.length - 1], count: list.length };
      }
    }
    return null;
  });

  console.log('=== 逐页 ===');
  for (const one of report) {
    const marks = [
      one.errors.length ? '✗ JS: ' + one.errors.join(' / ') : '✓ 无 JS 报错',
      one.shown.length ? '✗ 页面有失败信息: ' + one.shown.join(' / ') : '✓ 无失败面板',
    ];
    console.log(`${one.route.padEnd(11)} h1「${String(one.seen.title).trim()}」 `
      + `侧栏 ${one.seen.rail} 格 · 分区 ${one.seen.panels} 块 · 折叠 ${one.seen.folds} 个 · chip 区 ${one.seen.chips} 个 `
      + `状态「${String(one.seen.status).replace(/\s+/g, ' ').trim().slice(0, 20)}」 ${marks.join(' ')}`);
  }
  console.log(ordered
    ? `事件流倒序抽样：${ordered.node} 共 ${ordered.count} 条，第一条 ${ordered.first}`
    : '事件流：还没有可抽样的行（新项目正常）');
  if (log.length) {
    console.log('服务日志尾部：' + log.join('').trim().split('\n').slice(-3).join(' | '));
  }
} catch (e) {
  problems.push(String((e && e.message) || e));
} finally {
  if (browser) {
    await browser.close();
  }
  spawn('taskkill', ['/PID', String(server.pid), '/T', '/F'], { stdio: 'ignore' });
  await sleep(600);
  fs.rmSync(project, { recursive: true, force: true });
}

console.log(problems.length
  ? `\n✗ ${problems.length} 处有问题：\n- ` + problems.join('\n- ')
  : `\n✓ ${ROUTES.length} 页全过：没有 JS 报错、没有失败面板、外壳都渲染了`);
process.exit(problems.length ? 1 : 0);
