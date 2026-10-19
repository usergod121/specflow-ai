/**
 * 浏览器冒烟：起真 jar、用真 Chrome 走七个页面，除了"有没有报错"，还要对账三件事：
 *
 *  1. 后端声明的页头动作，界面上必须真的画出来（上一版漏过一整个 actions 字段）；
 *  2. 能点的按钮点下去必须发出请求（上一版按钮被 disabled 却不报错）；
 *  3. 换项目之后五格状态必须跟着换（上一版换了项目还显示上一条流程）。
 *
 * 用法：node tests/smoke.mjs [jar路径] [端口]
 *
 * playwright 不是这个项目的依赖（只是我本机装了它），所以路径从环境变量取：
 *   SPECFLOW_PLAYWRIGHT=file:///D:/somewhere/node_modules/playwright/index.mjs node tests/smoke.mjs
 * 不设就按普通包名 'playwright' 解析（在 frontend 目录里 npm i -D playwright 也能用）。
 */
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

const { chromium } = await import(process.env.SPECFLOW_PLAYWRIGHT || 'playwright');

const JAR = process.argv[2] || 'E:/specflow-v2/target/specflow.jar';
const PORT = Number(process.argv[3] || 3821);
const BASE = 'http://127.0.0.1:' + PORT;
const ROUTES = ['/', '/workbench', '/env', '/probe', '/cases', '/dev', '/test'];
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

function makeProject(name) {
  const root = path.join(os.tmpdir(), name + '-' + Date.now());
  fs.mkdirSync(path.join(root, '.specflow'), { recursive: true });
  fs.writeFileSync(path.join(root, '.specflow/project.yaml'),
    'build:\n  compile: "echo ok"\n\nllm:\n  base-url: http://127.0.0.1:1\n  model: stub\n'
    + '  api-key-env: SPECFLOW_SMOKE_KEY\n');
  fs.writeFileSync(path.join(root, '.specflow/local.env'), 'SPECFLOW_SMOKE_KEY=sk-smoke\n');
  fs.writeFileSync(path.join(root, 'README.md'), '# smoke\n');
  return root;
}

const projectA = makeProject('sf-smoke-a');
const projectB = makeProject('sf-smoke-b');
const log = [];
const problems = [];

const server = spawn('java', ['-jar', JAR, 'web', '--port', String(PORT), '--no-open', '-p', projectA],
  { stdio: 'pipe' });
server.stdout.on('data', (chunk) => log.push(String(chunk)));
server.stderr.on('data', (chunk) => log.push(String(chunk)));
server.on('error', (error) => log.push('起不来 java：' + error.message));

let browser = null;
try {
  const deadline = Date.now() + 120_000;
  for (;;) {
    try {
      const response = await fetch(BASE + '/api/state');
      if (response.ok) {
        break;
      }
    } catch {
      /* 还没起来 */
    }
    if (Date.now() > deadline) {
      throw new Error('服务 120 秒没起来。日志尾部：\n' + log.join('').slice(-800));
    }
    await sleep(400);
  }

  browser = await chromium.launch({ channel: 'chrome', headless: true });
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  const errors = [];
  const calls = [];
  page.on('console', (message) => {
    if (message.type() === 'error') {
      errors.push('console: ' + message.text());
    }
  });
  page.on('pageerror', (error) => errors.push('pageerror: ' + error.message));
  page.on('request', (request) => {
    const url = request.url();
    if (url.includes('/api/')) {
      calls.push(request.method() + ' ' + url.replace(BASE, ''));
    }
  });

  await page.goto(BASE + '/', { waitUntil: 'networkidle' });
  const declared = await page.evaluate(async (url) => {
    const flow = await (await fetch(url)).json();
    const out = {};
    for (const node of flow.nodes || []) {
      out[node.key] = (node.actions || [])
        .filter((one) => one.menu !== true && one.onlyWhileRunning !== true)
        .map((one) => String(one.label || ''));
    }
    return out;
  }, BASE + '/api/flow');

  // 五格都必须声明「叫停」——有一版只给了 ①③④，用户在 ⑤ 卡住时页面上没有任何能停的按钮，
  // 而错误话术却写着"点『叫停』"。所以这里对五格逐个核对，不是抽查一格。
  const cancels = await page.evaluate(async (url) => {
    const flow = await (await fetch(url)).json();
    const out = {};
    for (const node of flow.nodes || []) {
      const one = (node.actions || []).find((action) => action.id === 'cancel');
      out[node.key] = one ? { label: String(one.label || ''), only: one.onlyWhileRunning === true } : null;
    }
    return out;
  }, BASE + '/api/flow');
  for (const key of ['env', 'probe', 'cases', 'dev', 'test']) {
    const one = cancels[key];
    if (!one) {
      problems.push(`/api/flow 里 ${key} 没声明「叫停」：这一格跑飞了没有退路`);
    } else if (!one.only) {
      problems.push(`/api/flow 里 ${key} 的「叫停」没有 onlyWhileRunning（没活在跑时它会常驻死按钮）`);
    }
  }

  const rows = [];
  for (const route of ROUTES) {
    errors.length = 0;
    await page.goto(BASE + route, { waitUntil: 'networkidle' });
    await sleep(500);
    const seen = await page.evaluate(() => ({
      shell: Boolean(document.querySelector('.shell')),
      rail: document.querySelectorAll('.rail .step').length,
      title: (document.querySelector('h1') || {}).textContent || '',
      status: (document.querySelector('.head .pill') || {}).textContent || '',
      buttons: Array.from(document.querySelectorAll('.head button.btn, .head .more > summary'))
        .map((one) => ({ text: (one.textContent || '').trim(), disabled: one.disabled === true })),
      failures: Array.from(document.querySelectorAll('[data-failure]')).length,
      stream: document.querySelectorAll('.ev').length,
    }));
    rows.push({ route, seen, errors: [...errors] });
    if (!seen.shell) {
      problems.push(route + '：外壳没渲染');
    }
    if (errors.length) {
      problems.push(route + '：' + errors.join(' | '));
    }
    if (seen.failures) {
      problems.push(route + '：页面上有失败条');
    }
    const key = route.replace('/', '');
    const labels = seen.buttons.filter((one) => one.text && one.text !== '⋯').map((one) => one.text);
    for (const label of declared[key] || []) {
      if (!labels.includes(label)) {
        problems.push(`${route}：后端声明了页头动作「${label}」，界面没画出来`);
      }
    }
    if (labels.includes('叫停')) {
      problems.push(`${route}：没活在跑却画出了「叫停」（只在跑着时该出现）`);
    }
  }

  // ---------- 点「探测环境」：必须真的发请求 ----------
  await page.goto(BASE + '/env', { waitUntil: 'networkidle' });
  await sleep(400);
  const before = calls.length;
  const probe = page.locator('.head button.btn', { hasText: '探测环境' }).first();
  if (await probe.count()) {
    if (await probe.isDisabled()) {
      problems.push('/env：「探测环境」是灰的，点不动');
    } else {
      await probe.click({ force: true });
      await sleep(1500);
      const fresh = calls.slice(before).filter((one) => one.includes('/api/node/env/agent'));
      if (!fresh.length) {
        problems.push('/env：点了「探测环境」但没有任何请求发出');
      }
    }
  }

  // ---------- 换项目：五格必须跟着换 ----------
  const flowOf = async () => page.evaluate(async (url) => {
    const view = await (await fetch(url)).json();
    const states = {};
    for (const node of view.nodes || []) {
      states[node.key] = node.state;
    }
    return { flowId: view.flowId, states };
  }, BASE + '/api/flow');

  const flowA = await flowOf();
  await page.goto(BASE + '/', { waitUntil: 'networkidle' });
  await sleep(300);
  await page.fill('#path', projectB);
  await page.locator('button.btn.primary', { hasText: '打开' }).first().click();
  await page.waitForURL((url) => String(url).includes('/workbench'), { timeout: 10_000 }).catch(() => {});
  await sleep(900);
  const flowB = await flowOf();
  if (flowB.flowId === flowA.flowId) {
    problems.push('换项目后 flowId 没变（后端没切）');
  }
  await page.goto(BASE + '/env', { waitUntil: 'networkidle' });
  await sleep(600);
  const word = ((await page.evaluate(() => (document.querySelector('.head .pill') || {}).textContent || ''))).trim();
  const expect = { READY: '待完成', DONE: '已完成', LOCKED: '未开始' }[flowB.states.env];
  if (expect && word !== expect) {
    problems.push(`换项目后 /env 显示「${word}」，后端是 ${flowB.states.env}（应为「${expect}」）`);
  }

  console.log('=== 逐页 ===');
  for (const one of rows) {
    const buttons = one.seen.buttons.map((b) => b.text + (b.disabled ? '(灰)' : '')).join(' ');
    console.log(`${one.route.padEnd(11)} h1「${String(one.seen.title).trim()}」 `
      + `侧栏 ${one.seen.rail} 格 · 状态「${String(one.seen.status).replace(/\s+/g, ' ').trim()}」 · `
      + `按钮 ${buttons || '（无）'} · 事件 ${one.seen.stream} 条 · 失败条 ${one.seen.failures}`);
  }
  console.log(`换项目：${flowA.flowId} → ${flowB.flowId}`);
  console.log(problems.length ? '✗ 问题：\n- ' + problems.join('\n- ') : '✓ 七页全过（含按钮对账 / 点击发请求 / 换项目隔离）');
} catch (error) {
  problems.push(String((error && error.message) || error));
  console.log('✗ ' + problems[problems.length - 1]);
} finally {
  if (browser) {
    await browser.close();
  }
  server.kill();
}

process.exit(problems.length ? 1 : 0);
