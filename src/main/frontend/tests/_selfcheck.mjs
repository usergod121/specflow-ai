/**
 * 界面自查（**不是交付物**）：jar 还没有的时候，用一份假后端把界面跑起来，
 * 亲眼确认四件被点过名的事：归档倒序、chip 只画当前那一枚、徽章读 facts、失败就地显示。
 *
 * 用法：node tests/_selfcheck.mjs   （先 `npx vite build`）
 */
import { spawn } from 'node:child_process';
import { createServer } from 'node:http';
import { readFileSync, existsSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from 'file:///E:/specflow-ai/src/main/frontend/node_modules/playwright/index.mjs';

const root = fileURLToPath(new URL('..', import.meta.url));
const PORT = 3899;

const node = (key, state, facts, blockedReason) => ({ key, title: key, state, blockedReason: blockedReason || '', facts });

const events = [
  { id: 1, type: 'log', at: '2026-10-16T15:38:40', node: 'env', level: 'info', text: '最早的这一行' },
  { id: 2, type: 'tool', at: '2026-10-16T15:38:41', node: 'env', callId: 'c1', tool: 'read_file', args: '{"path":"pom.xml"}', status: 'running' },
  { id: 3, type: 'tool', at: '2026-10-16T15:38:42', node: 'env', callId: 'c1', tool: 'read_file', args: '{"path":"pom.xml"}', status: 'ok', millis: 12, result: '一行摘要', resultFull: '<project> 全文 </project>' },
  { id: 4, type: 'tool', at: '2026-10-16T15:38:43', node: 'env', callId: 'c2', tool: 'bash', args: '{"cmd":"mvn -q test"}', status: 'error', millis: 3200, result: '编译没过', resultFull: 'ERROR 找不到符号' },
  { id: 5, type: 'log', at: '2026-10-16T15:38:44', node: 'env', level: 'error', text: '最新的一条：编译没过' },
];

const routes = {
  '/api/state': () => ({ open: true, root: 'E:\\demo', project: { name: 'demo', path: 'E:\\demo' }, hasModel: true }),
  '/api/recent': () => ({ recent: [{ path: 'E:\\demo', name: 'demo', exists: true }] }),
  '/api/flow': () => ({
    flowId: 'f1',
    cursor: 'env',
    nodes: [
      node('env', 'DONE', { envReady: 'true', compilePassed: 'false' }),
      node('probe', 'READY', { probeDone: 'false' }),
      node('cases', 'LOCKED', { caseCount: '0' }, '先冻结 ② 才轮到这一格'),
      node('dev', 'LOCKED', {}),
      node('test', 'LOCKED', {}),
    ],
  }),
  '/api/node/env/events': () => ({ running: false, stage: '', events, next: 5 }),
  '/api/node/env/view': () => ({ envYaml: 'image: eclipse-temurin:17\n', compileCommand: 'mvn -q package', needed: true }),
  '/api/node/env/settings': () => ({ pullTimeoutMinutes: 30 }),
  '/api/node/probe/view': () => ({ files: [], changes: [], context: '', prompt: '', requirement: '', design: '' }),
  '/api/node/cases': () => ({ cases: [], pending: 0, missingTags: [] }),
  '/api/node/dev/view': () => ({ whitelist: [], readable: [], prompt: '', feedback: '', compileCommand: '', compilePassed: false }),
  '/api/node/test/approvals': () => ({ approvals: [] }),
  '/api/node/start': () => { throw new Error('boom'); },
};

const server = createServer((request, response) => {
  const url = new URL(request.url, 'http://127.0.0.1');
  const key = url.pathname;
  if (key.startsWith('/api/')) {
    // 「起环境」故意回 409 + 后端原话：看失败是不是就地贴在按钮下面
    if (key === '/api/node/env/up') {
      response.writeHead(409, { 'content-type': 'application/json' });
      response.end(JSON.stringify({ error: '容器还没起：先把 Docker 打开' }));
      return;
    }
    const found = routes[key] || (key.startsWith('/api/node/') ? () => ({ running: false, events: [], next: 0 }) : null);
    response.writeHead(found ? 200 : 404, { 'content-type': 'application/json' });
    response.end(JSON.stringify(found ? found() : { error: '没有这条' }));
    return;
  }
  const file = path.join(root, 'dist', key === '/' ? 'index.html' : key.slice(1));
  const extension = path.extname(file);
  if (extension && existsSync(file)) {
    const kind = { '.js': 'text/javascript', '.css': 'text/css', '.html': 'text/html' }[extension];
    response.writeHead(200, { 'content-type': kind || 'application/octet-stream' });
    response.end(readFileSync(file));
    return;
  }
  response.writeHead(200, { 'content-type': 'text/html' });
  response.end(readFileSync(path.join(root, 'dist/index.html')));
});

await new Promise(resolve => server.listen(PORT, '127.0.0.1', resolve));

const problems = [];
const browser = await chromium.launch({ channel: 'chrome', headless: true });
try {
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  const errors = [];
  const warnings = [];
  page.on('console', message => {
    const text = message.text();
    // 后端故意回的 409 会让浏览器自己打一条 "Failed to load resource"——那不是 JS 报错
    if (message.type() === 'warning') {
      warnings.push(text);
      return;
    }
    if (message.type() === 'error' && !text.includes('Failed to load resource')) {
      errors.push('console: ' + text);
    }
  });
  page.on('pageerror', error => errors.push('pageerror: ' + error.message));

  await page.goto(`http://127.0.0.1:${PORT}/env`, { waitUntil: 'networkidle' });
  await page.waitForTimeout(600);

  const seen = await page.evaluate(() => ({
    chips: Array.from(document.querySelectorAll('.chip')).map(one => one.textContent.replace(/\s+/g, ' ').trim()),
    head: (document.querySelector('.chips-head') || {}).textContent,
    more: (document.querySelector('.chips-head button') || {}).textContent,
    log: Array.from(document.querySelectorAll('.log > li .log-text')).map(one => one.textContent.trim()),
    badges: Array.from(document.querySelectorAll('.badge')).map(one => one.textContent.replace(/\s+/g, ' ').trim()),
    status: (document.querySelector('.status-text') || {}).textContent,
    gate: (document.querySelector('.gate') || {}).textContent,
    headButtons: Array.from(document.querySelectorAll('#actions-env button')).map(one => one.textContent.trim()),
    fonts: getComputedStyle(document.querySelector('.log-text') || document.body).fontSize,
    bodyBg: getComputedStyle(document.body).backgroundColor,
  }));

  // 点「起环境」——它回 409，失败必须就地贴在按钮下面
  const target = page.getByRole('button', { name: '起环境' });
  if (await target.count()) {
    const [hit] = await Promise.all([
      page.waitForResponse(response => response.url().includes('/api/'), { timeout: 8000 }).catch(() => null),
      target.first().click(),
    ]);
    console.log('点「起环境」打到：', hit ? hit.status() + ' ' + hit.url() : '(一个请求都没发)');
    await page.waitForTimeout(500);
  } else {
    problems.push('页头没有「起环境」这枚按钮');
  }
  const failed = await page.evaluate(() => ({
    // 只看界面自己那一条失败面板（`.log` 里 data-level=error 的行是归档，不算）
    shown: Array.from(document.querySelectorAll('.fail')).map(one => one.textContent.replace(/\s+/g, ' ').trim()),
    retry: Array.from(document.querySelectorAll('button')).filter(one => one.textContent.trim() === '重试').length,
  }));
  // 点「重试」，看它是不是又打了同一个动作
  const retry = page.getByRole('button', { name: '重试' });
  let again = null;
  if (await retry.count()) {
    const [hit] = await Promise.all([
      page.waitForResponse(response => response.url().includes('/env/up'), { timeout: 8000 }).catch(() => null),
      retry.first().click(),
    ]);
    again = hit ? hit.url() : null;
    await page.waitForTimeout(300);
  }

  console.log('chip 流：', JSON.stringify(seen));
  console.log('失败就地：', JSON.stringify(failed), '重试打到：', again || '(没发出去)');
  console.log('JS 报错：', errors.length ? errors.join(' | ') : '无');
  console.log('控制台提醒：', warnings.length ? warnings.slice(0, 5).join(' | ') : '无');

  if (seen.chips.length !== 1) { problems.push('chip 不是只画当前一枚：' + seen.chips.length + ' 枚'); }
  if (!String(seen.head).includes('2 次调用')) { problems.push('chip 上面那行不是「2 次调用 · 1 报错」：' + seen.head); }
  if (String(seen.more || '').trim() !== '更早 1 次') { problems.push('「更早」的数不对：' + seen.more); }
  if (seen.log[0] !== '最新的一条：编译没过') { problems.push('归档不是倒序：第一行是 ' + seen.log[0]); }
  if (seen.status !== '已完成') { problems.push('状态条不是「已完成」：' + seen.status); }
  if (!seen.badges.some(one => one.includes('编译') && one.includes('false'))) { problems.push('编译徽章没画出来：' + JSON.stringify(seen.badges)); }
  if (seen.headButtons.length > 3) { problems.push('页头按钮超过两个 + 「⋯」：' + JSON.stringify(seen.headButtons)); }
  if (!failed.shown.length) { problems.push('409 之后没有就地失败面板'); }
  else if (!failed.shown.join(' ').includes('容器还没起')) { problems.push('失败面板没显示后端原文'); }
  if (!failed.retry) { problems.push('失败面板上没有「重试」'); }
  else if (!again) { problems.push('「重试」没有重打同一个动作'); }

  // 「更早 1 次」点了要能翻出第二枚
  // chip 流在「调用」那一折里（默认收起），先展开再点「更早」
  const moreButton = page.locator('.chips-head button');
  if (await moreButton.count()) {
    await page.locator('details.fold', { hasText: '调用' }).locator('summary').click();
    await moreButton.first().click();
    await page.waitForTimeout(200);
  }
  const older = await page.evaluate(() => document.querySelectorAll('.chip').length);
  if (older !== 2) { problems.push('点「更早」之后 chip 数是 ' + older + '，应该是 2'); }

  // 七个页面都过一遍：外壳在、h1 在、没有 JS 报错
  for (const route of ['/', '/workbench', '/env', '/probe', '/cases', '/dev', '/test']) {
    errors.length = 0;
    await page.goto(`http://127.0.0.1:${PORT}${route}`, { waitUntil: 'networkidle' });
    await page.waitForTimeout(400);
    const shape = await page.evaluate(() => ({
      shell: Boolean(document.querySelector('.shell')),
      rail: document.querySelectorAll('.rail-item').length,
      title: (document.querySelector('h1') || {}).textContent || '',
      failures: Array.from(document.querySelectorAll('.fail')).map(one => one.textContent.trim()),
    }));
    if (!shape.shell || !shape.title) { problems.push(route + ' 没渲染出外壳或标题'); }
    if (errors.length) { problems.push(route + ' JS 报错：' + errors.join(' | ')); }
    console.log(`${route.padEnd(11)} h1「${shape.title}」侧栏 ${shape.rail} 格 `
      + (shape.failures.length ? '失败面板: ' + shape.failures.join(' / ') : '无失败面板'));
  }

  // 截一张图，用眼睛过一遍版面
  await page.goto(`http://127.0.0.1:${PORT}/env`, { waitUntil: 'networkidle' });
  await page.waitForTimeout(400);
  await page.screenshot({ path: path.join(root, 'tests', '_selfcheck-env.png'), fullPage: true });
  if (errors.length) { problems.push('JS 报错：' + errors.join(' | ')); }
} finally {
  await browser.close();
  server.close();
}

console.log(problems.length ? '\n✗ ' + problems.join('\n- ') : '\n✓ 自查全过');
process.exit(problems.length ? 1 : 0);
