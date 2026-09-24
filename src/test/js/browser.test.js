/**
 * 浏览器端的闭环测试。
 *
 * 前面的 web.test.js 只测纯逻辑，测不到「点了按钮到底发生了什么」——
 * 而界面上的坑基本都长在那里：
 *   - 异步初始化回来晚了，把用户已经打进去的内容擦掉，保存时变成空名字；
 *   - 报错画在主面板里，被弹层整个盖住，用户看到的是「点了没反应」；
 *   - 保存成功也一声不吭。
 * 这些只有真开一个浏览器点一遍才发现得了。
 *
 * 用 CDP 驱动本机 Chrome，不需要任何 npm 依赖（Node 22+ 自带 WebSocket）。
 *
 * 运行方式：
 *   先起服务  java -jar target/specflow.jar web --port 3081 --no-open -p .
 *   再跑测试  node src/test/js/browser.test.js http://127.0.0.1:3081/
 *
 * 它会自己清理掉自己建的模板，跑完磁盘上不留东西。
 */
'use strict';

const { spawn } = require('child_process');
const fs = require('fs');
const os = require('os');
const path = require('path');

const BROWSER = process.env.SPECFLOW_BROWSER
    || 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe';
const DEBUG_PORT = 9333;
const BASE = (process.argv[2] || process.env.SPECFLOW_URL || 'http://127.0.0.1:3081/')
    .replace(/\/+$/, '') + '/';
/**
 * 项目根。有一条测试要<b>故意放一个坏模板文件</b>进去——那是手滑写坏的样子，
 * 而它恰恰没法通过接口造出来（保存那一刻就会被拦下）。跑完会删掉。
 */
const PROJECT_ROOT = process.argv[3] || process.env.SPECFLOW_PROJECT || '.';

/** 本次测试建出来的模板，跑完删掉。 */
const CREATED = [];
/** 本次测试建出来的任务草稿，跑完删掉。 */
const CREATED_TASKS = [];
/** 故意放进去的坏模板文件，跑完删掉。 */
const BROKEN_FILES = [];
/** 测试在项目里造出来的临时目录/文件（模拟在 IDE 里加东西），跑完删掉。 */
const TEMP_PATHS = [];
/**
 * 「切项目」那条链会临时造一个项目出来。跑完必须删掉，而且必须把服务换回原项目——
 * 否则下一次跑测试是在别的项目里跑的，失败会莫名其妙。
 */
let otherProject = null;
let failed = 0;

const sleep = ms => new Promise(r => setTimeout(r, ms));

function check(condition, message) {
  console.log(condition ? '  ok   ' + message : '  FAIL ' + message);
  if (!condition) failed++;
}

// ---------- 最小的 CDP 客户端 ----------
let ws;
let nextId = 1;
const pending = new Map();
const browserErrors = [];

function send(method, params) {
  const id = nextId++;
  ws.send(JSON.stringify({ id, method, params: params || {} }));
  return new Promise((resolve, reject) => pending.set(id, { resolve, reject }));
}

async function evaluate(expression) {
  const r = await send('Runtime.evaluate', {
    expression, awaitPromise: true, returnByValue: true,
  });
  if (r.exceptionDetails) {
    const d = r.exceptionDetails;
    throw new Error('页面里抛异常: ' + ((d.exception && d.exception.description) || d.text));
  }
  return r.result.value;
}

/**
 * 把 /api/run 拦住。
 *
 * <p>好几条链会去点「运行」，它们验的是"该不该被拦住"，不是真要跑一次——
 * 真跑会调模型、改磁盘上的文件，那是拿用户的项目当试验田。
 * 所以桩只此一份，各链只管数 `__runCalls`。
 *
 * <p><b>每次整页刷新之后都要重装</b>（换项目那条链会 location.reload()，
 * 刷新会把 window 上这些东西全冲掉）。之前就是装一次、还在链尾还原，
 * 结果下一条链的「放行」落到了真服务上——那次侥幸只崩在模板名上、没走到模型调用，
 * 但那是运气，不是设计。
 */
async function installRunStub() {
  await evaluate(`(() => {
    window.__realFetch = window.fetch;
    window.__runCalls = 0;
    window.fetch = (url, opts) => {
      if (String(url).endsWith('/api/run')) {
        window.__runCalls++;
        window.__lastRunBody = (opts && opts.body) || '';
        return Promise.resolve(new Response(JSON.stringify({ runId: 'probe' }),
            { status: 200, headers: { 'Content-Type': 'application/json' } }));
      }
      return window.__realFetch(url, opts);
    };
    return 'ok';
  })()`);
}

/** 反复求值直到条件成立；超时就把最后一次的值报出来。 */
async function waitFor(expression, message, timeoutMs = 5000) {
  const deadline = Date.now() + timeoutMs;
  let last;
  while (Date.now() < deadline) {
    last = await evaluate(expression);
    if (last) return last;
    await sleep(50);
  }
  throw new Error('等待超时：' + message + '（最后一次求值结果 ' + JSON.stringify(last) + '）');
}

/**
 * 等一次整页刷新落地。
 *
 * <p>换项目是刻意用整页刷新做的（状态一定干净），而导航期间页面上下文会短暂消失，
 * 这时候求值会抛错——那不是失败，是在等，所以这里吞掉它继续轮询。
 */
async function waitForReload(expression, message, timeoutMs = 15000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    try {
      if (await evaluate(expression)) return true;
    } catch (e) { /* 正在导航，下一轮再来 */ }
    await sleep(120);
  }
  throw new Error('等待超时：' + message);
}

// ---------- 页面动作 ----------

const optionValues = () => evaluate(
    `[...document.querySelectorAll('#tpl option')].map(o => o.value)`);

const noticeText = () => evaluate(`document.getElementById('notice').textContent.trim()`);

const managerOpen = () => evaluate(`!document.getElementById('tplmgr').hidden`);

const closeManager = () => evaluate(`document.getElementById('tplmgr').hidden = true; 'ok'`);

/**
 * 等「新建」彻底就绪。
 *
 * <p>光等弹层可见不够——弹层现在是**立刻**开的（慢网下也不能点了没反应），
 * 数据还在路上，编辑区在此期间是锁着的。真用户也只能等到解锁才能打字，
 * 所以这里必须一起等，否则测的是个人做不到的操作。
 */
const waitForFreshForm = () => waitFor(
    `!document.getElementById('tplmgr').hidden
     && !document.getElementById('tf-name').disabled
     && document.getElementById('tf-name').value === ''`, '新建表单就绪');

/** 等管理弹层把数据填好（而不是刚显示出来）。 */
const waitForManagerReady = () => waitFor(
    `!document.getElementById('tplmgr').hidden
     && !document.getElementById('tf-name').disabled`, '管理弹层就绪');

/** 在下拉框里选一项，派发真实的 change 事件。 */
async function chooseTemplateOption(value) {
  await evaluate(`
    (() => {
      const s = document.getElementById('tpl');
      s.value = ${JSON.stringify(value)};
      s.dispatchEvent(new Event('change'));
      return s.value;
    })()`);
}

async function setField(id, value) {
  await evaluate(`
    (() => {
      const el = document.getElementById(${JSON.stringify(id)});
      el.value = ${JSON.stringify(value)};
      el.dispatchEvent(new Event('input'));
      return el.value;
    })()`);
}

const clickButton = id => evaluate(`document.getElementById(${JSON.stringify(id)}).click(); 'ok'`);

/**
 * 元素在页面上真的占着一块地方吗。
 *
 * <p>不能查 `el.hidden`——那只是属性。标着 hidden 却照样显示是这里真出过的事故：
 * `main{display:flex}` 把浏览器自带的 `[hidden]{display:none}` 盖掉了，
 * 欢迎页下面拖着一整个空的工作区（模板、文件树全是空的），而当时所有断言的
 * `el.hidden` 都是 true。查属性等于什么都没查。
 */
const visible = id => evaluate(`
  (() => {
    const el = document.getElementById(${JSON.stringify(id)});
    if (!el) return false;
    const r = el.getBoundingClientRect();
    return r.width > 0 && r.height > 0;
  })()`);

/** 标着 hidden 却还在页面上占地方的元素，全都列出来。 */
const hiddenButVisible = () => evaluate(`
  [...document.querySelectorAll('[hidden]')]
      .filter(el => {
        const r = el.getBoundingClientRect();
        return r.width > 0 || r.height > 0;
      })
      .map(el => (el.id || el.className) + ' ' + Math.round(el.getBoundingClientRect().width)
                 + 'x' + Math.round(el.getBoundingClientRect().height))`);

/** 打开的弹层必须落在视口里——不然「弹窗」弹在屏幕外，用户看到的就是「没反应」。 */
const overlayInViewport = id => evaluate(`
  (() => {
    const el = document.getElementById(${JSON.stringify(id)});
    if (!el || el.hidden) return false;
    const r = el.getBoundingClientRect();
    const panel = el.querySelector('.overlay-panel').getBoundingClientRect();
    return r.width > 0 && panel.width > 0 && panel.width <= r.width + 1
        && panel.top >= -1 && panel.bottom <= innerHeight + 1
        && panel.left >= -1 && panel.right <= innerWidth + 1;
  })()`);

const templatesOnServer = async () => {
  const response = await fetch(BASE + 'api/config');
  return (await response.json()).templates.map(t => t.name);
};

async function deleteTemplateOnServer(name) {
  await fetch(BASE + 'api/templates?name=' + encodeURIComponent(name), { method: 'DELETE' });
}

// ---------- 测试 ----------

async function main() {
  const profile = fs.mkdtempSync(path.join(os.tmpdir(), 'specflow-browser-'));
  const chrome = spawn(BROWSER, [
    '--headless=new', '--disable-gpu', '--no-first-run', '--no-default-browser-check',
    '--disable-extensions', '--remote-debugging-port=' + DEBUG_PORT,
    '--user-data-dir=' + profile, 'about:blank',
  ], { stdio: 'ignore' });

  try {
    for (let i = 0; i < 80; i++) {
      try {
        if ((await fetch(`http://127.0.0.1:${DEBUG_PORT}/json/version`)).ok) break;
      } catch (e) { /* 还没起来 */ }
      await sleep(250);
    }

    const targets = await (await fetch(`http://127.0.0.1:${DEBUG_PORT}/json/list`)).json();
    const target = targets.find(t => t.type === 'page');
    ws = new WebSocket(target.webSocketDebuggerUrl);
    await new Promise((resolve, reject) => { ws.onopen = resolve; ws.onerror = reject; });

    ws.onmessage = event => {
      const msg = JSON.parse(event.data);
      if (msg.id && pending.has(msg.id)) {
        const { resolve, reject } = pending.get(msg.id);
        pending.delete(msg.id);
        msg.error ? reject(new Error(JSON.stringify(msg.error))) : resolve(msg.result);
        return;
      }
      if (msg.method === 'Runtime.exceptionThrown') {
        const d = msg.params.exceptionDetails;
        browserErrors.push('未捕获异常: ' + ((d.exception && d.exception.description) || d.text));
      }
      if (msg.method === 'Runtime.consoleAPICalled' && msg.params.type === 'error') {
        browserErrors.push('console.error: '
            + msg.params.args.map(a => a.value || a.description).join(' '));
      }
    };

    await send('Runtime.enable');
    await send('Page.enable');
    await send('Page.navigate', { url: BASE });
    await waitFor(`document.querySelectorAll('#tpl option').length > 0`, '模板下拉框被填上');

    console.log('页面加载：');
    check(await evaluate(`document.getElementById('notice') !== null`),
        '提示浮层存在（不是主面板里那个会被弹层盖住的容器）');
    check(await evaluate(`
      (() => {
        const el = document.getElementById('notice');
        return el && el.getBoundingClientRect().top >= 0;
      })()`), '提示浮层在视口里、切到 fixed 定位');
    check(browserErrors.length === 0, '加载期间没有未捕获异常');

    // 布局：有项目时只该有工作区，欢迎页一个字都不该露出来
    check(await visible('workspace'), '有项目时工作区是显示出来的');
    check(!(await visible('welcome')), '有项目时欢迎页不显示');
    check((await hiddenButVisible()).length === 0,
        '没有「标着 hidden 却还占着地方」的元素：' + JSON.stringify(await hiddenButVisible()));

    // 把 /api/run 的桩装上（每次整页刷新之后都得重装一遍，见 installRunStub 的说明）
    await installRunStub();

    // ---------- 链 1：下拉框入口新建 ----------
    console.log('\n链 1　下拉框「＋ 新建模板…」新建并保存：');
    await chooseTemplateOption('__new__');
    check(await waitForFreshForm(), '选中动作项后弹层自动打开，且是空表单');
    check(await evaluate(`document.getElementById('tpl').value`) !== '__new__',
        '动作项没有留在下拉框的选择里');
    check(await evaluate(`document.activeElement.id`) === 'tf-name',
        '光标落在名字输入框上');

    // 这一条是竞态回归：弹层刚打开、异步初始化还没回来时打的字不该被擦掉
    const raceName = '测试-竞态';
    await setField('tf-name', raceName);
    await sleep(1500);
    check(await evaluate(`document.getElementById('tf-name').value`) === raceName,
        '等异步初始化跑完之后，已经打进去的名字还在');

    await clickButton('tf-save');
    await sleep(800);
    CREATED.push(raceName);
    check((await templatesOnServer()).includes(raceName), '模板真的落到了服务端');
    check((await optionValues()).includes(raceName), '新模板出现在下拉框里');
    check((await noticeText()).includes(raceName), '保存有可见的回音');

    // ---------- 链 2：保存失败必须看得见 ----------
    console.log('\n链 2　保存失败时，报错要看得见（这是「点了没反应」的根因）：');
    await closeManager();
    await chooseTemplateOption('__new__');
    await waitForFreshForm();
    await clickButton('tf-save');            // 名字留空，后端一定会拒
    await sleep(800);
    const failedNotice = await noticeText();
    check(failedNotice.length > 0, '空名字保存失败时弹出了提示');
    check(failedNotice.includes('模板名不能为空'),
        '提示说的是人话：' + JSON.stringify(failedNotice));
    check(!/Cannot construct instance|StreamReadFeature/.test(failedNotice),
        '提示里没有 Jackson 的类名和内部开关');
    check(await evaluate(`
      (() => {
        const notice = document.getElementById('notice');
        const panel = document.querySelector('#tplmgr .overlay-panel');
        // 提示必须在弹层之上，否则用户还是看不见
        return Number(getComputedStyle(notice).zIndex)
             > Number(getComputedStyle(document.getElementById('tplmgr')).zIndex);
      })()`), '提示浮层的层级高于弹层，不会被盖住');

    // ---------- 链 3：管理弹层里的「新建」按钮、以及顶部那个「我在编辑哪个」 ----------
    console.log('\n链 3　管理弹层里的「新建」按钮，和顶部下拉框的语义：');
    await clickButton('tf-new');
    check(await evaluate(`document.getElementById('tf-name').value`) === '',
        '「新建」把编辑区清空了');
    // 新建的时候顶部那个下拉框曾经是「空的、点开却列着别的模板」，看着像在问「要先选一个吗」
    check(await evaluate(`document.getElementById('tf-pick').value`) === '__new__',
        '新建状态下，顶部下拉框显示的是「＋ 新建模板…」而不是空着');
    check(await evaluate(`
      document.getElementById('tf-pick').selectedOptions[0].textContent.includes('新建模板')`),
        '它显示的文字也确实是「＋ 新建模板…」');
    check(await evaluate(`document.getElementById('tf-delete').disabled`),
        '还没落盘的模板，「删除」是禁用的，而不是点了再告诉你不该点');

    const btnName = '测试-按钮入口';
    await setField('tf-name', btnName);
    await sleep(300);
    await clickButton('tf-save');
    await sleep(800);
    CREATED.push(btnName);
    check((await templatesOnServer()).includes(btnName), '这条入口存下来的模板也在服务端');

    // ---------- 链 4：改已有模板 ----------
    console.log('\n链 4　改一个已有模板：');
    await evaluate(`
      (() => {
        const picker = document.getElementById('tf-pick');
        picker.value = ${JSON.stringify(btnName)};
        picker.dispatchEvent(new Event('change'));
        return picker.value;
      })()`);
    check(await evaluate(`document.getElementById('tf-name').value`) === btnName,
        '选中已有模板后表单被填上');
    check(!(await evaluate(`document.getElementById('tf-delete').disabled`)),
        '切回已有模板之后，「删除」又变成可点的');
    await setField('tf-desc', '改过的说明');
    await clickButton('tf-save');
    await sleep(800);
    const reloaded = await (await fetch(BASE + 'api/config')).json();
    const edited = reloaded.templates.find(t => t.name === btnName);
    check(edited && edited.description === '改过的说明', '改动落到了服务端');

    // ---------- 链 5：源码视图 ----------
    console.log('\n链 5　源码视图读写：');
    await evaluate(`
      [...document.querySelectorAll('#tplmgr .tabs span')]
          .find(s => s.dataset.view === 'yaml').click(); 'ok'`);
    const source = await evaluate(`document.getElementById('tf-source').value`);
    check(source.includes('name:') && source.includes('system:'), '源码视图里有当前模板的 YAML');
    await evaluate(`
      (() => {
        const ta = document.getElementById('tf-source');
        ta.value = ta.value.replace('改过的说明', '源码改的说明');
        return ta.value;
      })()`);
    await clickButton('tf-save');
    await sleep(800);
    const afterSource = await (await fetch(BASE + 'api/config')).json();
    const fromSource = afterSource.templates.find(t => t.name === btnName);
    check(fromSource && fromSource.description === '源码改的说明', '源码视图保存生效');
    await evaluate(`
      [...document.querySelectorAll('#tplmgr .tabs span')]
          .find(s => s.dataset.view === 'form').click(); 'ok'`);

    // ---------- 链 6：删除 ----------
    console.log('\n链 6　删除：');
    await evaluate(`
      (() => {
        const picker = document.getElementById('tf-pick');
        picker.value = ${JSON.stringify(btnName)};
        picker.dispatchEvent(new Event('change'));
        return picker.value;
      })()`);
    await clickButton('tf-delete');
    await sleep(800);
    CREATED.splice(CREATED.indexOf(btnName), 1);
    check(!(await templatesOnServer()).includes(btnName), '模板从服务端消失');
    check(!(await optionValues()).includes(btnName), '模板从下拉框消失');
    check((await noticeText()).includes('删除'), '删除有可见的回音');

    // ---------- 链 7：任务草稿 ----------
    console.log('\n链 7　任务草稿：存下来 → 列出来 → 载入回来 → 删掉：');
    await closeManager();
    const draftName = '测试-草稿';
    const demandText = '给 OrderService 加一个按订单号查询的方法';
    await setField('demand', demandText);
    await setField('newpath', 'src/test/OrderQuery.java');
    await clickButton('addpath');
    await clickButton('taskmanage');
    await waitFor(`!document.getElementById('taskmgr').hidden`, '任务草稿弹层打开');
    await setField('task-name', draftName);
    await clickButton('task-save');
    await sleep(800);
    CREATED_TASKS.push(draftName);
    check((await noticeText()).includes('已保存'), '保存草稿有可见的回音');
    check(((await (await fetch(BASE + 'api/tasks')).json()).tasks || []).includes(draftName),
        '草稿落到了服务端');
    check(await evaluate(
        `document.getElementById('task-list').textContent.includes(${JSON.stringify(draftName)})`),
        '草稿出现在列表里');

    // 先把表单改掉，这样才能看出载入确实把内容填了回来
    await setField('demand', '换个内容，等着被覆盖');
    await evaluate(`
      [...document.querySelectorAll('#task-list .list-row')]
          .find(r => r.textContent.includes(${JSON.stringify(draftName)}))
          .querySelector('button').click(); 'ok'`);
    await sleep(800);
    check(await evaluate(`document.getElementById('demand').value`) === demandText,
        '载入草稿把需求填回了表单');
    check((await noticeText()).includes(draftName), '载入有可见的回音');

    // ---------- 链 8：主面板的组装 ----------
    console.log('\n链 8　主面板：目标文件、上下文依赖、验收标准：');
    await closeManager();
    await evaluate(`document.getElementById('taskmgr').hidden = true; 'ok'`);

    // 目标文件：勾一个已有的，再手输一个不存在的
    const beforePicked = await evaluate(`document.getElementById('picked').textContent`);
    const firstFile = await evaluate(`
      (() => {
        const box = document.querySelector('#files .node.file input[type=checkbox]');
        if (!box) return null;
        box.click();
        const path = box.parentElement.querySelector('.name').title;
        box.click();                 // 勾上再取消，计数要能回到原样
        box.click();                 // 真正勾上
        return path;
      })()`);
    if (firstFile) {
      check((await evaluate(`document.getElementById('picked').textContent`)) !== beforePicked,
          '勾选一个已有文件后，「将修改」的计数跟着变');
      check((await evaluate(`
        [...document.querySelectorAll('#files .node.file input[type=checkbox]')]
            .some(b => b.checked)`)), '那个文件确实是勾上的');
    } else {
      check(false, '文件树里一个文件都没有，这条没法测');
    }

    await setField('newpath', 'src/test/新文件.java');
    await clickButton('addpath');
    check((await evaluate(`document.getElementById('picked').textContent`)).includes('新建'),
        '手输的新路径计入「新建」');
    check(await evaluate(
        `[...document.querySelectorAll('#newchips .chip')].some(c => c.textContent.includes('新文件'))`),
        '新路径以 chip 形式列出来，能看见也能删');

    // ---------- 链 8b：在目录行上「＋」新建（路径自动填好，只打文件名） ----------
    // 用户报的原话：新建文件必须从 src 一路手打完整路径。目录行上给他一个落点。
    console.log('\n链 8b　在目录行上「＋」新建：');
    const dirRows = await evaluate(`document.querySelectorAll('#files .node.dir').length`);
    const pluses = await evaluate(`document.querySelectorAll('#files .node.dir .plus').length`);
    check(dirRows > 0 && pluses === dirRows,
        '每个目录行上都有「＋」：' + pluses + '/' + dirRows);

    const findDirRow = name => `
      [...document.querySelectorAll('#files .node.dir')]
          .find(r => r.querySelector('.name').title === ${JSON.stringify(name)})`;
    const newpathValue = () => evaluate(`document.getElementById('newpath').value`);
    const chipsText = `[...document.querySelectorAll('#newchips .chip')].map(c => c.textContent).join('|')`;
    const newCount = async () => {
      const m = (await evaluate(`document.getElementById('picked').textContent`)).match(/新建\s*(\d+)/);
      return m ? Number(m[1]) : -1;
    };

    // 「＋」是新建，展开/折叠是另一件事，两者不能互相牵连
    const caretBefore = await evaluate(`${findDirRow('src')}.querySelector('.caret').textContent`);
    await evaluate(`${findDirRow('src')}.querySelector('.plus').click(); 'ok'`);
    check(await evaluate(`${findDirRow('src')}.querySelector('.caret').textContent`) === caretBefore,
        '点「＋」不会顺手把目录展开或折叠（caret 还是 ' + caretBefore + '）');
    check(await newpathValue() === 'src/', '「＋」把目录路径填进了输入框：' + (await newpathValue()));
    check(await evaluate(`document.activeElement === document.getElementById('newpath')`),
        '光标已经落在输入框里，接着打文件名就行');

    // 用户抱怨的正是「从 src 一路手打到包名」，所以要在真实的深目录上验一次。
    // 每一条都自己把状态摆好，不依赖上一条的结果（否则一条挂了会连累后面几条）。
    const ensureOpen = async name => evaluate(`(() => {
      const row = ${findDirRow(name)};
      if (row && row.querySelector('.caret').textContent === '▸') row.querySelector('.name').click();
      return 'ok';
    })()`);
    await ensureOpen('src');
    await ensureOpen('src/main');
    // 不写死目录名：树的合并规则会随项目里有没有文件而变（少一个文件，两层就并成一行），
    // 写死了迟早误报。这里只要求「能找到一个够深的目录行」。
    const deepDir = await evaluate(`(() => {
      const titles = [...document.querySelectorAll('#files .node.dir .name')].map(n => n.title);
      return titles.filter(t => t.split('/').length > 2).sort((a, b) => b.length - a.length)[0] || null;
    })()`);
    check(deepDir !== null, '展开之后能找到深一层的目录：' + deepDir);
    await evaluate(`${findDirRow(deepDir)}.querySelector('.plus').click(); 'ok'`);
    check(await newpathValue() === deepDir + '/',
        '深目录上的「＋」填的是完整那一段（行上只显示后半截）：' + (await newpathValue()));

    const newBefore = await newCount();
    await evaluate(`(() => {
      const input = document.getElementById('newpath');
      input.value += 'zz-plus.java';
      input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
      return 'ok';
    })()`);
    check((await evaluate(chipsText)).includes(deepDir + '/zz-plus.java'),
        '补上文件名回车就加进去了：' + (await evaluate(chipsText)));
    check(await newCount() === newBefore + 1,
        '它计入「新建」：' + newBefore + ' → ' + (await newCount()));
    check(await newpathValue() === '', '加完之后输入框清空，等着下一次输入');

    // 手上还有半截路径时点了别的目录：填进去的必须是新目录，不能两段拼一起
    await evaluate(`document.getElementById('newpath').value = 'src/半截'; 'ok'`);
    await evaluate(`${findDirRow('src/test')} ? ${findDirRow('src/test')}.querySelector('.plus').click()
        : document.getElementById('newpath').value = '（树里没有 src/test 这一行）'; 'ok'`);
    check(await newpathValue() === 'src/test/',
        '换一个目录点「＋」，填的是新目录而不是接着上一段：' + (await newpathValue()));

    await evaluate(`(() => {
      [...document.querySelectorAll('#newchips .chip')]
          .filter(c => c.textContent.includes('zz-plus.java'))
          .forEach(c => c.querySelector('.x').click());
      document.getElementById('newpath').value = '';
      return 'ok';
    })()`);
    check(await newCount() === newBefore, '点 × 能把这条路径撤掉');

    // 上下文依赖
    check(await evaluate(`document.getElementById('ctxlist').textContent.includes('暂无')`),
        '没加上下文时，列表给的是空状态说明而不是一片空白');
    await evaluate(`document.querySelector('.ctx-add summary').click(); 'ok'`);
    await setField('ctxname', '订单表结构');
    await evaluate(`document.getElementById('ctxkind').value = 'text';
                    document.getElementById('ctxkind').dispatchEvent(new Event('change')); 'ok'`);
    await setField('ctxtext', 'CREATE TABLE orders (id BIGINT)');
    await clickButton('ctxadd');
    check(await evaluate(`document.querySelectorAll('#ctxlist .ctx-item').length`) === 1,
        '加一条上下文后列表里多了一行');
    await evaluate(`document.querySelector('#ctxlist .ctx-item .x').click(); 'ok'`);
    check(await evaluate(`document.querySelectorAll('#ctxlist .ctx-item').length`) === 0,
        '点 × 能把它删掉');

    // 验收标准：列表里永远至少留一行空的，所以按「比之前多几行」来断言
    const beforeRows = await evaluate(
        `document.querySelectorAll('#acceptance .acceptance-row').length`);
    await clickButton('add-acceptance');
    await clickButton('add-acceptance');
    check(await evaluate(`document.querySelectorAll('#acceptance .acceptance-row').length`)
        === beforeRows + 2, '「＋ 加一条」每点一次多一行');
    await evaluate(`document.querySelectorAll('#acceptance .acceptance-row .x')[0].click(); 'ok'`);
    check(await evaluate(`document.querySelectorAll('#acceptance .acceptance-row').length`)
        === beforeRows + 1, '点 × 能删掉其中一行');

    // 搜索时用平铺列表、工具条让位——这是「标着 hidden 却还显示」的另一个现场
    await setField('search', 'Demo');
    await sleep(300);
    check(!(await visible('treetools')), '搜索时文件树工具条让位（不是只标了个 hidden）');
    check((await hiddenButVisible()).length === 0,
        '搜索状态下也没有占着地方的 hidden 元素：' + JSON.stringify(await hiddenButVisible()));
    await setField('search', '');
    await sleep(300);
    check(await visible('treetools'), '清空搜索之后工具条回来');

    // ---------- 链 9：键盘与状态细节 ----------
    // 这几条来自一次专门的前端交互审查：弹层没有键盘出口、
    // 打开管理弹层时认错了模板、成功提示一直挂着不走。
    console.log('\n链 9　键盘与状态细节：');
    await evaluate(`document.getElementById('taskmgr').hidden = true;
                    document.getElementById('tplmgr').hidden = true; 'ok'`);

    // 重试轮数不能是自由文本：打进去的汉字会被静默当成 0，也就是一次都不重试
    check(await evaluate(`document.getElementById('maxretry').type`) === 'number',
        '「失败重试上限」是数字输入，打不进汉字');

    await clickButton('tplmanage');
    await waitFor(`!document.getElementById('tplmgr').hidden`, '模板管理弹层打开');
    await waitForManagerReady();
    check(await overlayInViewport('tplmgr'), '模板弹窗落在视口里');
    check((await noticeText()) === '', '打开弹层会先清掉上一条提示，不让它跟过来');

    // 只能点右上角那个「关闭」的话，键盘用户就被关在弹层里了
    await evaluate(
        `document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true })); 'ok'`);
    check(!(await managerOpen()), 'Esc 能关掉弹层');

    // 「管理模板」该打开主面板正在用的那个，而不是永远第一个
    await chooseTemplateOption(raceName);
    await clickButton('tplmanage');
    await waitForManagerReady();
    check(await evaluate(`document.getElementById('tf-name').value`) === raceName,
        '「管理模板」打开的是主面板正在用的那个模板');

    // 慢网下不能「点了没反应」：弹层要立刻出现，期间编辑区是锁上的
    await evaluate(`document.getElementById('tplmgr').hidden = true; 'ok'`);
    await evaluate(`window.__slowBackup = window.fetch; window.fetch = (url, opts) =>
        String(url).includes('/api/config')
          ? new Promise(r => setTimeout(() => r(window.__slowBackup(url, opts)), 1200))
          : window.__slowBackup(url, opts); 'ok'`);
    await clickButton('tplmanage');
    await sleep(300);
    check(await managerOpen(), '慢网下弹层也是立刻出现，而不是等数据回来才开');
    check(await evaluate(`document.getElementById('tf-name').disabled`),
        '数据没回来之前编辑区是锁上的（也正是这一步挡住了「打字被异步重填擦掉」）');
    await sleep(1600);
    check(!(await evaluate(`document.getElementById('tf-name').disabled`)),
        '数据回来之后编辑区解锁');
    await evaluate(`window.fetch = window.__slowBackup;
                    document.getElementById('tplmgr').hidden = true; 'ok'`);

    // ---------- 链 10：坏模板与两个视图的一致性 ----------
    console.log('\n链 10　坏模板看得见、删得掉；源码视图的改动不会在切 tab 时丢：');

    // 手滑写坏的模板文件。这种文件没法通过接口造出来（保存那一刻就会被拦下），
    // 所以直接放到目录里——这正是它真实的来源。
    const brokenPath = path.join(PROJECT_ROOT, '.specflow', 'templates', '测试-坏模板.yaml');
    fs.writeFileSync(brokenPath, 'name: 测试-坏模板\nsystem: [这不是: 合法 yaml\n');
    BROKEN_FILES.push(brokenPath);

    await evaluate(`document.getElementById('tplmgr').hidden = true; 'ok'`);
    await clickButton('tplmanage');
    await waitForManagerReady();
    check(await evaluate(`document.querySelectorAll('#tplbroken .broken-row').length`) === 1,
        '读不出来的模板在界面上列出来了，而不是悄悄少一个');
    check(await evaluate(`document.getElementById('tplbroken').textContent.includes('测试-坏模板')`),
        '列出来的是哪一份，写得清清楚楚');
    check(await evaluate(`
      ![...document.querySelectorAll('#tpl option')].some(o => o.value === '测试-坏模板')`),
        '它不参与选择——坏的东西不该能被选中');

    await evaluate(`document.querySelector('#tplbroken .broken-row button').click(); 'ok'`);
    await sleep(800);
    check(await evaluate(`document.querySelectorAll('#tplbroken .broken-row').length`) === 0,
        '点「删除」能从界面里把它删掉——否则界面起不来就没地方救它了');
    check(!fs.existsSync(brokenPath), '文件真的没了');
    BROKEN_FILES.length = 0;

    // 源码视图改完切回表单：以前这一步会静默丢掉源码里的改动
    const tabName = '测试-切视图';
    await chooseTemplateOption('__new__');
    await waitForFreshForm();
    await setField('tf-name', tabName);
    await setField('tf-desc', '表单里写的说明');
    await clickButton('tf-save');
    await sleep(800);
    CREATED.push(tabName);

    await closeManager();
    await chooseTemplateOption(tabName);
    await clickButton('tplmanage');
    await waitForManagerReady();
    await evaluate(`
      [...document.querySelectorAll('#tplmgr .tabs span')]
          .find(s => s.dataset.view === 'yaml').click(); 'ok'`);
    await evaluate(`
      (() => {
        const ta = document.getElementById('tf-source');
        ta.value = ta.value.replace('表单里写的说明', '源码里改的说明');
        return ta.value;
      })()`);
    await evaluate(`
      [...document.querySelectorAll('#tplmgr .tabs span')]
          .find(s => s.dataset.view === 'form').click(); 'ok'`);
    // 切回表单要先跟服务端往返一次（把 YAML 解析成对象），所以等的是「表单真的切回来了」
    await waitFor(`!document.getElementById('tf-form').hidden`, '表单视图就绪');
    check(await evaluate(`document.getElementById('tf-desc').value`) === '源码里改的说明',
        '切回表单时，源码里改的内容被解析回来了，而不是拿旧内容重画');

    await clickButton('tf-save');
    await sleep(800);
    const afterTabs = await (await fetch(BASE + 'api/config')).json();
    const saved = afterTabs.templates.find(t => t.name === tabName);
    check(saved && saved.description === '源码里改的说明', '保存下去的是源码里那份内容');

    // ---------- 链 11：欢迎页、目录浏览、切换项目 ----------
    console.log('\n链 11　切换项目：欢迎页 → 挑目录 → 打开 → 切回来：');

    // 造一个真的第二个项目：有 pom.xml，能被认出来
    const other = otherProject = fs.mkdtempSync(path.join(os.tmpdir(), 'specflow-other-'));
    fs.writeFileSync(path.join(other, 'pom.xml'), '<project/>\n');
    fs.mkdirSync(path.join(other, 'src'));
    // 再放一份**空壳**的 project.yaml：这是那个死角——界面以为「已经配过了」，
    // 于是不给「初始化」入口，而模板和编译命令一个都没有，用户没有任何出路
    fs.mkdirSync(path.join(other, '.specflow'));
    fs.writeFileSync(path.join(other, '.specflow', 'project.yaml'), '');

    check(await evaluate(
        `document.getElementById('projectname').textContent.includes(${JSON.stringify(path.basename(PROJECT_ROOT))})`),
        '顶栏写着当前项目名');

    await clickButton('switchproject');
    await waitForReload(
        `document.getElementById('welcome') && !document.getElementById('welcome').hidden`,
        '回到欢迎页');
    check(await evaluate(`document.getElementById('workspace').hidden`), '主界面让位给欢迎页');
    // 上面那句查的是属性，这里查它到底画没画出来——两者的区别正是这个项目栽过的坑
    check(!(await visible('workspace')), '欢迎页上工作区真的没画出来（不是只标了个 hidden）');
    check(await visible('welcome'), '欢迎页本身是显示出来的');
    check(await evaluate(`document.body.scrollHeight <= innerHeight + 40`),
        '欢迎页不需要滚动——下面不该再拖着半个空工作区：scrollHeight='
        + (await evaluate(`document.body.scrollHeight`)) + ' 视口='
        + (await evaluate(`innerHeight`)));
    check((await hiddenButVisible()).length === 0,
        '欢迎页上也没有「标着 hidden 却还占着地方」的元素：' + JSON.stringify(await hiddenButVisible()));
    check(await evaluate(
        `document.getElementById('recent-list').textContent.includes(${JSON.stringify(path.basename(PROJECT_ROOT))})`),
        '「最近打开」里有刚才那个项目');
    check(await evaluate(`document.getElementById('historylink').hidden`),
        '欢迎页上不该还挂着「运行历史」这种要有项目才有意义的按钮');

    // 主按钮走的是服务端弹出来的系统对话框（页面拿不到系统对话框里选的绝对路径），
    // 所以这里绝不能真点它——点了桌面上就会弹出一个窗口。改成拦住这个请求看交互。
    check(await evaluate(`document.getElementById('open-folder').textContent.includes('打开文件夹')`),
        '主按钮是「打开文件夹…」（走系统对话框）');
    check(await evaluate(
        `document.getElementById('open-folder-manual').classList.contains('ghost')`),
        '「手动输入路径…」是次要样式，不和主按钮抢眼');

    await evaluate(`
      window.__realPickFetch = window.fetch;
      window.__json = (body, status) => new Response(JSON.stringify(body),
          { status: status || 200, headers: { 'Content-Type': 'application/json' } });
      window.fetch = (url, opts) =>
          String(url).includes('/api/pick-folder') ? window.__pick(url, opts)
                                                   : window.__realPickFetch(url, opts);
      'ok'`);

    // ① 窗口开着的时候：按钮禁用 + 写明在等你；再点也不会变成「可以再点」
    await evaluate(`window.__pick = () => window.__json({ status: 'picking' }); 'ok'`);
    await clickButton('open-folder');
    await sleep(300);
    check(await evaluate(`document.getElementById('open-folder').disabled`),
        '等系统窗口期间按钮是禁用的，不会被连点出第二个窗口');
    check(await evaluate(`document.getElementById('open-folder').textContent.includes('正在等你选')`),
        '期间按钮写明「正在等你选…」，不是「点了没反应」');
    // 硬点一下（绕开禁用）：状态仍然由服务端那一份说了算，界面不该解锁
    await evaluate(`document.getElementById('open-folder').click(); 'ok'`);
    await sleep(600);
    check(await evaluate(`document.getElementById('open-folder').disabled`),
        '窗口开着时再点，按钮仍然是禁用的——不会再弹一个窗口');
    check(!(await evaluate(`document.getElementById('welcome').hidden`)),
        '这期间一直留在欢迎页');

    // ② 用户点了取消：状态变成 cancelled，按钮还回来，什么都不做
    await evaluate(`window.__pick = () => window.__json({ status: 'cancelled' }); 'ok'`);
    await waitFor(`!document.getElementById('open-folder').disabled`, '取消之后按钮恢复');
    check(await evaluate(`document.getElementById('open-folder').textContent.includes('打开文件夹')`),
        '按钮文字也恢复原样');
    check((await noticeText()) === '', '取消是正常操作，不该留下提示');
    check(!(await evaluate(`document.getElementById('welcome').hidden`)),
        '点了取消就留在欢迎页，什么都不该发生');

    // ③ 弹不出来（比如服务跑在没有桌面的环境里）：说清原因，并指出还有手动这条路
    await evaluate(`window.__pick = (url, opts) => ((opts && opts.method) === 'POST')
        ? window.__json({ status: 'picking' })
        : window.__json({ status: 'failed',
            error: '弹不出选择文件夹的窗口：既没有 pwsh 也没有 powershell' }); 'ok'`);
    await clickButton('open-folder');
    await waitFor(`document.getElementById('notice').textContent.includes('弹不出')`,
        '弹不出来时给出原因');
    const pickNotice = await noticeText();
    check(pickNotice.includes('手动输入路径'), '并且告诉用户还有「手动输入路径」这条路：'
        + JSON.stringify(pickNotice));
    await waitFor(`!document.getElementById('open-folder').disabled`, '失败之后按钮回到可点状态');
    await evaluate(`document.getElementById('notice').textContent = ''; 'ok'`);

    // ④ 用户选了目录：界面自己把它打开（走 /api/open），然后整页刷新进工作区
    await evaluate(`window.__pick = (url, opts) => ((opts && opts.method) === 'POST')
        ? window.__json({ status: 'picking' })
        : window.__json({ status: 'picked', path: ${JSON.stringify(other)} }); 'ok'`);
    await clickButton('open-folder');
    await waitForReload(
        `document.getElementById('projectname').textContent.includes(${JSON.stringify(path.basename(other))})`,
        '选完目录之后界面自己把项目打开了');
    check(await evaluate(`document.getElementById('projectname').textContent
        .includes(${JSON.stringify(path.basename(other))})`),
        '选完目录不用再点一次，界面自己打开了它');
    check(await evaluate(`document.getElementById('welcome').hidden`), '打开之后欢迎页让位');

    // 回到欢迎页——后面的链要从这里继续走「手动输入路径」那条路
    await clickButton('switchproject');
    await waitForReload(`!document.getElementById('welcome').hidden`, '回到欢迎页');

    await clickButton('open-folder-manual');
    await waitFor(`!document.getElementById('browser').hidden`, '目录浏览器打开');
    // 「弹窗」要真的看得见：落在视口里，而且列出来的是有名字的盘符
    check(await overlayInViewport('browser'), '目录弹窗落在视口里，不是弹在屏幕外');
    // 弹层是立刻开的，目录是随后拉回来的——等列出来，别抢在前头看
    await waitFor(`document.querySelectorAll('#browser-dirs .open-row').length > 0`, '盘符列出来');
    check(await evaluate(`document.getElementById('browser-path').textContent.includes('盘符')`),
        '还没选目录时，标题说明白当前列的是盘符');
    const driveNames = await evaluate(
        `[...document.querySelectorAll('#browser-dirs .open-row .head')].map(e => e.textContent)`);
    check(driveNames.length >= 2 && driveNames.every(n => /^[A-Za-z]:/.test(n)),
        '列出来的是 C:\\ D:\\ E:\\ 这样的盘符，而不是没有名字的空行：'
        + JSON.stringify(driveNames));
    check((await hiddenButVisible()).length === 0,
        '弹层打开时也没有「标着 hidden 却还占着地方」的元素：' + JSON.stringify(await hiddenButVisible()));

    // 这两个弹层的键盘出口一度只挂在「已经打开项目」那条路上，而目录弹层恰恰主要在欢迎页用
    await evaluate(
        `document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true })); 'ok'`);
    check(await evaluate(`document.getElementById('browser').hidden`),
        '欢迎页上 Esc 也能关掉目录弹层');

    await clickButton('open-folder-manual');
    await waitFor(`!document.getElementById('browser').hidden`, '目录浏览器重新打开');
    // 弹层里的旧列表还在，而新一轮是随后才回来的——等按钮解锁才说明这一轮读完了
    await waitFor(`!document.getElementById('browser-go').disabled`, '目录读完了');

    // 路径框里能打任何东西，Windows 会因此直接抛 InvalidPathException。
    // 那属于「你打错了」，界面上该看到的是一句人话。
    await setField('browser-input', 'C:\\aa"bb');
    await clickButton('browser-go');
    await waitFor(`document.getElementById('notice').textContent.trim().length > 0`, '非法路径给出提示');
    const badPathNotice = await noticeText();
    check(!/InvalidPathException|java\.nio/.test(badPathNotice),
        '非法路径的提示里没有 Java 异常类名：' + JSON.stringify(badPathNotice));
    check(badPathNotice.includes('路径'), '提示说的是这个路径用不了：' + JSON.stringify(badPathNotice));

    await evaluate(`
      (() => {
        document.getElementById('browser-input').value = ${JSON.stringify(other)};
        document.getElementById('browser-go').click();
        return 'ok';
      })()`);
    await waitFor(`document.getElementById('browser-scan').textContent.includes('pom.xml')`,
        '认出这个目录是个 Maven 项目');
    check(await evaluate(`document.getElementById('browser-scan').textContent.includes('mvn')`),
        '并且给出了建议的编译校验命令');
    check(await evaluate(
        `[...document.querySelectorAll('#browser-dirs .open-row .hit')].some(r => r.textContent.includes('src'))`),
        '列出了这个目录下的子目录');

    // 读一个目录可能要等到超时（不通的网络位置尤其久），期间必须有反馈，
    // 否则界面看着就是「点了没反应」，用户会一直点
    await evaluate(`window.__slowBrowse = window.fetch; window.fetch = (url, opts) =>
        String(url).includes('/api/browse')
          ? new Promise(r => setTimeout(() => r(window.__slowBrowse(url, opts)), 800))
          : window.__slowBrowse(url, opts); 'ok'`);
    await clickButton('browser-go');
    await sleep(200);
    check(await evaluate(
        `document.getElementById('browser-note').textContent.includes('读取中')`),
        '翻目录期间显示「读取中…」');
    check(await evaluate(`document.getElementById('browser-go').disabled`),
        '期间「前往」是禁用的，不会被连点成一堆请求');
    await sleep(1000);
    check(!(await evaluate(`document.getElementById('browser-go').disabled`)),
        '读完之后按钮恢复可用');
    check((await evaluate(`document.getElementById('browser-note').textContent`)) === '',
        '读完之后「读取中…」消失，不会一直挂着');
    await evaluate(`window.fetch = window.__slowBrowse; 'ok'`);

    // 连着翻两个目录：先发的那个慢、后发的那个快，界面必须停在最后点的那个。
    // 少了这条护栏，先发后回的旧结果会把界面拽回去，而用户以为自己已经在别的目录里了。
    const parentDir = path.dirname(other);
    await evaluate(`window.__raceBrowse = window.fetch; window.fetch = (url, opts) => {
      const u = String(url);
      if (!u.includes('/api/browse')) return window.__raceBrowse(url, opts);
      const asked = new URL(u, location.href).searchParams.get('path') || '';
      const slow = asked === ${JSON.stringify(parentDir)};
      return new Promise(r => setTimeout(() => r(window.__raceBrowse(url, opts)), slow ? 1200 : 0));
    }; 'ok'`);

    await setField('browser-input', parentDir);
    await clickButton('browser-go');                  // 慢的那次：上一级
    // 快的那次：点它列出来的 src 子目录（行是可点的，不受「前往」禁用影响）
    await evaluate(`(() => {
      const row = [...document.querySelectorAll('#browser-dirs .open-row')]
          .find(r => r.querySelector('.head') && r.querySelector('.head').textContent.trim() === 'src');
      row.querySelector('.hit').click();
      return 'ok';
    })()`);
    await sleep(1800);
    const landed = await evaluate(`document.getElementById('browser-path').textContent`);
    check(landed === path.join(other, 'src'),
        '连着翻两个目录时，界面停在最后点的那个，而不是先发后回的旧结果：' + landed);
    await evaluate(`window.fetch = window.__raceBrowse; 'ok'`);

    // 把当前位置收回刚认出来的那个项目，后面要打开它
    await setField('browser-input', other);
    await clickButton('browser-go');
    await waitFor(`!document.getElementById('browser-go').disabled`, '回到待打开的项目');

    await clickButton('browser-open');
    await waitForReload(
        `document.getElementById('projectname').textContent.includes(${JSON.stringify(path.basename(other))})`,
        '切到新项目');
    // 顶栏是 /api/state 一回来就画的，模板和编译命令要等 /api/config——
    // 不等齐就断言，测的其实是「谁先回来」
    await waitFor(`!document.getElementById('workspace').hidden
        && document.getElementById('meta').textContent.includes('未配置编译命令')`,
        '新项目的工作区就绪');
    // 新项目只有 pom.xml、没配过 specflow，所以编译命令应该是「未配置」——
    // 这里要是还显示上一个项目的命令，就是典型的串项目
    check(await evaluate(`document.getElementById('meta').textContent.includes('未配置编译命令')`),
        '编译命令没有从上个项目带过来');
    check(await evaluate(`document.getElementById('tpl').options.length`) === 2,
        '新项目的模板下拉框只有「新建模板」和「自由输入」两个哨兵项，没带过来旧模板');
    check(await evaluate(`!document.getElementById('setup').hidden`),
        '认得出这是个还没配过 specflow 的项目，并给出了提示');
    check(await evaluate(
        `[...document.querySelectorAll('#setup button')].some(b => b.textContent.includes('初始化'))`),
        '空壳配置也给了「初始化」入口，而不是把用户关在门外');

    // 真的点一下：这个空壳必须被修好（以前这条路是死的——点完还是「没配过」）
    await evaluate(
        `[...document.querySelectorAll('#setup button')].find(b => b.textContent.includes('初始化')).click(); 'ok'`);
    await waitForReload(`document.getElementById('setup').hidden
        && document.getElementById('meta').textContent.includes('mvn')`,
        '初始化把这个空壳修好了');
    check(await evaluate(`document.getElementById('meta').textContent.includes('mvn')`),
        '初始化之后编译命令就位');
    check(await evaluate(`document.getElementById('tpl').options.length > 2`),
        '初始化之后内置模板也进来了');
    check(fs.readFileSync(path.join(other, '.specflow', 'project.yaml'), 'utf8').includes('compile'),
        '磁盘上那份空壳真的被写上了内容');

    // 切回去：先关掉，再从最近列表点回去
    await clickButton('switchproject');
    await waitForReload(`!document.getElementById('welcome').hidden`, '又回到欢迎页');
    await evaluate(`
      [...document.querySelectorAll('#recent-list .open-row .hit')]
          .find(r => r.textContent.includes(${JSON.stringify(path.basename(PROJECT_ROOT))})).click(); 'ok'`);
    await waitForReload(
        `document.getElementById('projectname').textContent.includes(${JSON.stringify(path.basename(PROJECT_ROOT))})`,
        '切回原项目');
    await waitFor(`document.getElementById('tpl').options.length > 2`, '原项目的模板回来了');
    check(await evaluate(`document.getElementById('tpl').options.length > 2`),
        '原项目的模板回来了');
    check(!(await visible('welcome')), '切回项目之后欢迎页又让位了');
    check(await visible('workspace'), '工作区回来了');
    check((await hiddenButVisible()).length === 0,
        '切回项目后也没有「标着 hidden 却还占着地方」的元素：' + JSON.stringify(await hiddenButVisible()));

    // 换项目是整页刷新，刚才装在 window 上的东西全没了：桩得重装一遍，
    // 否则后面点「运行」就落到真服务上去了（真跑会调模型、动文件）。
    await installRunStub();

    // ---------- 链 13：文件树刷新（在 IDE 里加了包/文件，切回来要看得见） ----------
    // 这是用户报的那个 bug 的正面复现：他在 IDEA 里新建了一个包，页面上看不见、也刷新不出来。
    // 两个原因：树是从「文件路径」推出来的（空包推不出来），而且界面上根本没有刷新入口。
    console.log('\n链 13　文件树刷新：新加的包和文件都要能刷出来：');
    const probeDir = path.join(PROJECT_ROOT, 'zz-refresh-probe');
    const emptyDir = path.join(PROJECT_ROOT, 'zz-empty-pkg');
    TEMP_PATHS.push(probeDir, emptyDir);

    const countFiles = async () => {
      const text = await evaluate(`document.getElementById('meta').textContent`);
      const m = text.match(/·\s*(\d+)\s*个文件/);
      return m ? Number(m[1]) : -1;
    };
    const before = await countFiles();
    check(before >= 0, '顶栏能读出文件数：' + before);

    // 1) 页面开着的时候往磁盘上加东西（模拟 IDE 那一侧的动作）
    fs.mkdirSync(path.join(probeDir, 'inner'), { recursive: true });
    fs.writeFileSync(path.join(probeDir, 'NewInIde.java'), 'class NewInIde {}\n');
    fs.mkdirSync(emptyDir, { recursive: true });

    // 2) 点「刷新」——不该需要整页 F5
    await clickButton('refreshfiles');
    await waitFor(`document.getElementById('files').textContent.includes('zz-refresh-probe')`,
        '点刷新之后新加的包出现在树里');
    check(await evaluate(`document.getElementById('files').textContent.includes('zz-empty-pkg')`),
        '空包（一个文件都没有）也出现在树里');
    check(await countFiles() === before + 1,
        '顶栏文件数跟着变了：' + before + ' → ' + (await countFiles()));

    // 点开那个包，应该能看到里面新加的文件
    await evaluate(`(() => {
      const row = [...document.querySelectorAll('#files .node.dir')]
          .find(r => r.textContent.includes('zz-refresh-probe'));
      row.querySelector('.name').click();
      return 'ok';
    })()`);
    check(await evaluate(`document.getElementById('files').textContent.includes('NewInIde.java')`),
        '展开这个包能看到里面新加的文件');

    // 空包照样能新建：树里一个文件都没有，但它的目录行在，落点就在那儿
    await evaluate(`(() => {
      const row = [...document.querySelectorAll('#files .node.dir')]
          .find(r => r.querySelector('.name').title.includes('zz-empty-pkg'));
      row.querySelector('.plus').click();
      return 'ok';
    })()`);
    check(await evaluate(`document.getElementById('newpath').value`) === 'zz-empty-pkg/',
        '空包上的「＋」填的是它自己的路径：' + (await evaluate(`document.getElementById('newpath').value`)));
    await evaluate(`(() => {
      const input = document.getElementById('newpath');
      input.value += 'FromEmpty.java';
      input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
      return 'ok';
    })()`);
    check(await evaluate(`[...document.querySelectorAll('#newchips .chip')]
        .some(c => c.textContent.includes('zz-empty-pkg/FromEmpty.java'))`),
        '空包里建的路径是 zz-empty-pkg/FromEmpty.java');
    await evaluate(`(() => {
      [...document.querySelectorAll('#newchips .chip')]
          .filter(c => c.textContent.includes('zz-empty-pkg/FromEmpty.java'))
          .forEach(c => c.querySelector('.x').click());
      return 'ok';
    })()`);

    // 3) 从别的窗口切回来（focus）应当自动刷新，不用手动点
    const focusedFile = path.join(PROJECT_ROOT, 'zz-focused.java');
    TEMP_PATHS.push(focusedFile);
    fs.writeFileSync(focusedFile, 'class Zz {}\n');
    await evaluate(`window.dispatchEvent(new Event('focus')); 'ok'`);
    await waitFor(`document.getElementById('files').textContent.includes('zz-focused.java')`,
        '切回窗口时自动刷出了新文件');
    check((await hiddenButVisible()).length === 0,
        '刷新之后也没有「标着 hidden 却还占着地方」的元素');

    // ---------- 链 14：检查阶段的方案面板（流程图） ----------
    // 用户的现场：模型画了判断 + 两股，图上看不全，还多出莫名其妙的框。
    // 这里喂一份「模型爱写的那种」流程图进去，看界面到底画成什么样、又说了什么。
    console.log('\n链 14　方案面板：流程图要画全，画不了的要说明白：');
    const planFlow = [
      'flowchart TD',
      '    A[收到下单请求] --> B{库存够不够}',
      '    B -->|够| C[创建订单]',
      '    B -->|不够| D[返回缺货]',
      '    subgraph 订单模块',
      '      C --> E[扣减库存]',
      '    end',
      '    E -.-> F[发通知]',
      '    style B fill:#f9f,stroke:#333',
      '    classDef big font-size:20px',
      '    linkStyle 0 stroke:#f00',
      '    click B "http://example.com"',
    ].join('\n');
    await evaluate(`(() => {
      state.plan = {
        summary: '按订单号创建订单，库存不足时直接返回缺货。',
        flowchart: ${JSON.stringify(planFlow)},
        missing: [],
      };
      renderPlan();
      return 'ok';
    })()`);

    check(await evaluate(`!!document.querySelector('#plan .flow svg')`),
        '方案面板里真的画出了 SVG，而不是回退成一段文字');
    const nodeTexts = await evaluate(
        `[...document.querySelectorAll('#plan .flow svg text')].map(t => t.textContent)`);
    check(nodeTexts.includes('创建订单') && nodeTexts.includes('扣减库存'),
        '节点文字都在：' + JSON.stringify(nodeTexts));
    check(!['style', 'classDef', 'linkStyle', 'click', 'subgraph', 'end']
        .some(ghost => nodeTexts.includes(ghost)),
        '样式/分组指令没有变成幻影节点：' + JSON.stringify(nodeTexts));
    check(nodeTexts.includes('够') && nodeTexts.includes('不够'),
        '判断的两个分支标签都在：' + JSON.stringify(nodeTexts));
    check(await evaluate(`document.querySelectorAll('#plan .flow svg .flow-decision').length`) === 1,
        '判断节点画成菱形（有 flow-decision 这个类）');
    check(await evaluate(`document.querySelectorAll('#plan .flow svg .flow-group').length`) === 1,
        '分组画成了一个框');
    check(nodeTexts.includes('订单模块'), '分组框上带着组名');

    // 点线：E -.-> F 必须画出来，而不是让两个节点各站一排
    check(await evaluate(
        `[...document.querySelectorAll('#plan .flow svg path')]
            .some(p => (p.getAttribute('stroke-dasharray') || '') !== '')`),
        '点线用的是虚线段，不是实线');
    check(await evaluate(
        `document.querySelectorAll('#plan .flow svg .flow-node').length`) === 6,
        '六个节点一个不少：' + (await evaluate(
            `document.querySelectorAll('#plan .flow svg .flow-node').length`)));

    // 画不了的东西必须明说
    const noteText = await evaluate(`(() => {
      const note = document.querySelector('#plan .flow-note');
      return note ? note.textContent : '';
    })()`);
    check(noteText.includes('4 行没能画进图里'), '没画进图里的行数写在界面上：' + noteText);
    check(noteText.includes('样式'), '而且说清了是什么原因：' + noteText);

    // 原始 Mermaid 开关：默认收起，打开后与模型给的一字不差
    check(await evaluate(`document.querySelector('#plan .flow-raw').open === false`),
        '「看原始 Mermaid」默认是收起的');
    const rawText = await evaluate(`document.querySelector('#plan .flow-raw pre').textContent`);
    check(rawText === planFlow, '打开前它就在 DOM 里，内容与模型给的逐字一致');
    await evaluate(`document.querySelector('#plan .flow-raw summary').click(); 'ok'`);
    check(await evaluate(`document.querySelector('#plan .flow-raw').open === true`),
        '点一下能展开看原文');

    // 几何不变量：节点框互不重叠、都在画布内、分组框圈住了它的组员
    const geometry = await evaluate(`(() => {
      const rectOf = el => { const r = el.getBoundingClientRect();
        return { l: r.left, r: r.right, t: r.top, b: r.bottom }; };
      const svg = document.querySelector('#plan .flow svg');
      const canvas = rectOf(svg);
      const nodes = [...svg.querySelectorAll('.flow-node')].map(rectOf);
      const group = rectOf(svg.querySelector('.flow-group'));
      const overlaps = [];
      for (let i = 0; i < nodes.length; i++) {
        for (let j = i + 1; j < nodes.length; j++) {
          const a = nodes[i], b = nodes[j];
          if (a.l < b.r && b.l < a.r && a.t < b.b && b.t < a.b) overlaps.push([i, j]);
        }
      }
      const outside = nodes.filter(n =>
          n.l < canvas.l - 1 || n.r > canvas.r + 1 || n.t < canvas.t - 1 || n.b > canvas.b + 1).length;
      // 组框里的节点分两种：整个在里面（组员）和被压住一半（画坏了/看着像组员）
      const insideGroup = n => n.l >= group.l - 1 && n.r <= group.r + 1
          && n.t >= group.t - 1 && n.b <= group.b + 1;
      const hitsGroup = n => n.l < group.r && group.l < n.r && n.t < group.b && group.t < n.b;
      return { count: nodes.length, overlaps: overlaps.length, outside,
               groupCovers: nodes.filter(insideGroup).length,
               groupIntruders: nodes.filter(n => hitsGroup(n) && !insideGroup(n)).length };
    })()`);
    check(geometry.overlaps === 0, '六个节点框互不重叠：' + JSON.stringify(geometry));
    check(geometry.outside === 0, '所有节点都在画布范围内：' + JSON.stringify(geometry));
    check(geometry.groupCovers === 2,
        '分组框把组员圈在里面（2 个）：' + JSON.stringify(geometry));
    check(geometry.groupIntruders === 0,
        '没有被组框压住一半的节点（压住一半看着就像组员）：' + JSON.stringify(geometry));

    // 解析不出图时：老老实实显示原文，并且照样给出说明
    await evaluate(`(() => {
      state.plan = { summary: '这行不是图', flowchart: '这里不是 mermaid', missing: [] };
      renderPlan();
      return 'ok';
    })()`);
    check(await evaluate(`document.querySelector('#plan .flow-fallback') !== null`),
        '画不出来时把原文显示出来，而不是留一个空框');
    check(await evaluate(
        `document.querySelector('#plan .flow-raw pre').textContent === '这里不是 mermaid'`),
        '回退之后原文开关里的内容还是原样');
    await evaluate(`state.plan = null; renderPlan(); 'ok'`);

    // ---------- 链 15：缺失信息的严重度（只显示，不拦人） ----------
    // 用户的现场：模型列一堆缺失项追着问，分不出哪条真要紧。
    // 现在每条都带严重度；但**模型自评的严重度不再拦人**——它标歪过，
    // 真正拦人的是机器算出来的「方案执行不了」（见链 16）。
    console.log('\n链 15　缺失信息：分严重度、模型自评不拦人：');
    // 顺序由引擎定（PlanParser 按严重度排好），界面只负责照着画。
    // 这里按「服务端会发过来的样子」给：阻断在前，然后是影响质量、可选、未标。
    const planWithMissing = {
      summary: '加一个查询接口。',
      flowchart: 'flowchart TD\n    A[入口] --> B[出口]',
      missing: [
        { what: 'orders 表结构', severity: 'BLOCKING', impact: '没有字段名就写不出 SQL',
          business: '查询可能查不到数据', fallback: '' },
        { what: '命名习惯', severity: 'QUALITY', impact: '能编译但可能不一致',
          business: '字段名和你要的叫法不同', fallback: '按现有代码的驼峰写' },
        { what: '日志格式', severity: 'OPTIONAL', impact: '无所谓',
          business: '看不出差别', fallback: '按现有格式' },
        { what: '说不清的一项', severity: 'UNKNOWN', impact: '', business: '', fallback: '' },
      ],
    };
    await evaluate(`(() => {
      state.plan = ${JSON.stringify(planWithMissing)};
      state.forced = false;
      renderPlan();
      return 'ok';
    })()`);

    const chips = await evaluate(`[...document.querySelectorAll('#plan .missing .sev')]
        .map(c => c.className.replace('sev ', '') + '|' + c.textContent)`);
    check(chips.length === 4, '四条缺失项都画出来了：' + JSON.stringify(chips));
    check(chips[0] === 'sev-blocking|阻断', '第一条就是阻断（顺序由引擎排，界面照画）：' + chips[0]);
    check(chips.join(',') === 'sev-blocking|阻断,sev-quality|影响质量,sev-optional|可选,sev-unknown|未标',
        '四档标签齐全且顺序没被打乱：' + JSON.stringify(chips));

    const planText = await evaluate(`document.getElementById('plan').textContent`);
    check(planText.includes('技术影响：没有字段名就写不出 SQL'), '技术影响写出来了');
    check(planText.includes('业务影响：查询可能查不到数据'),
        '业务影响（给不熟这块的人看）也写出来了');
    check(planText.includes('它打算这么写：按现有代码的驼峰写'),
        '非阻断项写出它打算用的默认值');
    check(planText.includes('它没给具体默认值'), '没给默认值的那条也不是一片空白');
    check(planText.includes('模型自己觉得这 1 条比较要紧'), '标题说清了这是「模型自己觉得」');
    check(planText.includes('它不拦你'), '并且明说它不拦人');
    check(await evaluate(`!document.querySelector('#plan .missing .gate')`),
        '缺失清单里没有闸门——模型自评不该挡住运行');

    // 这一链绝不能真跑起来（真跑会调模型、动文件）：/api/run 的桩在最前面就装好了，
    // 这里只把计数清零，看这一链点了没有。
    await evaluate(`window.__runCalls = 0; 'ok'`);
    await setField('demand', '缺失信息的严重度联动');
    await evaluate(`state.selected.add('src/test/zz-severity-probe.java'); updatePicked(); 'ok'`);

    await evaluate(`state.forced = false; 'ok'`);
    await clickButton('run');
    await sleep(700);
    check(await evaluate(`window.__runCalls`) === 1,
        '模型标了阻断也不拦人：运行照常发出去');
    check(await evaluate(`window.__lastRunBody.includes('orders 表结构')`),
        '那份方案（含缺失项）跟着请求一起发出去');
    check(await evaluate(`window.__lastRunBody.includes('BLOCKING')`),
        '严重度也在请求里，不是只发个名字');
    check(await evaluate(`state.forced === false`), '没点过「仍然继续」时这个放行标记不生效');

    // 只有非阻断项时不该拦人，否则「严重度」等于白标
    await evaluate(`(() => {
      window.__runCalls = 0;
      state.plan = { summary: '', flowchart: 'flowchart TD\\n    A-->B',
        missing: [{ what: '日志格式', severity: 'OPTIONAL', impact: '无所谓',
                    business: '看不出差别', fallback: '按现有格式' }] };
      state.forced = false;
      renderPlan();
      return 'ok';
    })()`);
    await clickButton('run');
    await sleep(600);
    check(await evaluate(`window.__runCalls`) === 1, '只有「可选」项时不拦，直接开跑');

    // 统计闭环：它在历史里那一行（「标了阻断但最后还是成功」＝报重了）
    await evaluate(`(() => {
      window.__realFetch2 = window.fetch;
      window.fetch = (url, opts) => String(url).endsWith('/api/runs')
          ? Promise.resolve(new Response(JSON.stringify({
              runs: [{ id: '20260923-120000-000', startedAt: '', status: 'FAILED',
                       template: '', targets: ['a.java'], detail: '结束' }],
              missingStats: { runs: 4, items: 9, blockingItems: 3,
                              runsWithBlocking: 2, runsWithBlockingSucceeded: 1 },
            }), { status: 200, headers: { 'Content-Type': 'application/json' } }))
          : window.__realFetch2(url, opts);
      return 'ok';
    })()`);
    await clickButton('historylink');
    await waitFor(`!document.getElementById('history').hidden`, '历史弹层打开');
    await sleep(400);
    const statsText = await evaluate(`document.getElementById('historystats').textContent`);
    check(statsText.includes('9 条缺失项') && statsText.includes('3 条标了「阻断」'),
        '统计行说清了报过多少条、其中几条阻断：' + statsText);
    check(statsText.includes('有 1 次最后还是成功了'),
        '并且点出「标了阻断最后还是成功」的次数——那几次多半是报重了：' + statsText);
    check((await hiddenButVisible()).length === 0, '历史弹层里也没有「标着 hidden 却占地方」的元素');

    // 收拾干净：关弹层、撤掉这一链造的痕迹。
    // 注意**不要**在这里恢复 fetch：/api/run 的桩要一直挂到测试结束，
    // 后面的链还要点「运行」看它有没有被拦住。
    await evaluate(`(() => {
      document.getElementById('history').hidden = true;
      state.plan = null;
      state.forced = false;
      state.selected.delete('src/test/zz-severity-probe.java');
      renderPlan();
      updatePicked();
      return 'ok';
    })()`);

    // ---------- 链 16：机器查出来的「方案执行不了」 ----------
    // 用户的现场：目标清单只给了 service 和 mapper，检查阶段却给出「新建
    // com.library.controller.HealthController」——那种方案永远写不进去。
    // 现在由机器（PlanAudit）算出来，而且只有它拦人。
    console.log('\n链 16　方案执行不了：机器算出来的那一份才拦人：');
    const auditPlan = {
      summary: '加一个按订单号查询的接口。',
      flowchart: 'flowchart TD\n    A[加接口] --> B[写 Controller]',
      missing: [{ what: '接口路径前缀', severity: 'QUALITY', impact: '可能不一致',
                  business: '前端要改路径', fallback: '照现有的写' }],
    };
    const auditFindings = [
      { path: 'src/main/java/com/library/controller/HealthController.java',
        reason: '它在项目里，但不在本次的目标文件清单里：清单外的文件改不了',
        suggest: 'src/main/java/com/library/controller/HealthController.java' },
      { path: 'com/library/dto/SummaryDTO', reason: '清单里没有它（看起来是要新建）：清单外的文件建不了',
        suggest: null },
    ];
    await evaluate(`(() => {
      state.plan = ${JSON.stringify(auditPlan)};
      state.audit = ${JSON.stringify(auditFindings)};
      state.forced = false;
      renderPlan();
      return 'ok';
    })()`);

    const auditText = await evaluate(`document.querySelector('#plan .audit').textContent`);
    check(auditText.includes('2 处执行不了'), '机器查出来的问题排在最前，并说清有几处：' + auditText);
    check(auditText.includes('HealthController.java') && auditText.includes('清单外的文件改不了'),
        '把具体文件和不改的后果都写出来了');
    check(auditText.includes('它还不存在'), '给不出准确路径的那条改说「要自己填」，而不是塞个错路径');
    check(await evaluate(`document.querySelectorAll('#plan .audit .action').length`) === 1,
        '能落到具体文件上的那条，给一个「加进目标文件」按钮');
    check(await evaluate(`!!document.querySelector('#plan .audit .gate .force-run')`),
        '这一处才配拦人：旁边有「我知道，仍然继续」');

    // 点「加进目标文件」：路径真的进了清单（chip 出现），而且有回音
    await setField('demand', '机器检查的执行不了');
    await evaluate(`state.selected.delete('src/test/zz-severity-probe.java'); updatePicked(); 'ok'`);
    const beforeAdd = await evaluate(`state.selected.size`);
    await evaluate(`document.querySelector('#plan .audit .action').click(); 'ok'`);
    await sleep(300);
    check(await evaluate(`state.selected.size`) === beforeAdd + 1,
        '「加进目标文件」把它加进清单了：' + (await evaluate(`[...state.selected].join(',')`)));
    check((await noticeText()).includes('已加进目标文件'), '并且给了回音：' + (await noticeText()));

    // 闸门：有 audit 时先挡一下，点了「仍然继续」才放行
    await evaluate(`(() => { window.__runCalls = 0; state.forced = false; return 'ok'; })()`);
    await clickButton('run');
    await sleep(600);
    check(await evaluate(`window.__runCalls`) === 0, '有执行不了的地方时点「运行」被拦下');
    check((await noticeText()).includes('执行不了'),
        '拦下来的话里说的是「执行不了」，不是模型的自评：' + (await noticeText()));

    await evaluate(`document.querySelector('#plan .audit .force-run').click(); 'ok'`);
    await sleep(800);
    check(await evaluate(`window.__runCalls`) === 1, '点「我知道，仍然继续」之后放行');
    check(await evaluate(`state.forced === false`), '而且放行是一次性的');

    // 结果区：措辞说准 + 落盘的文件路径列出来
    await evaluate(`(() => {
      state.plan = null; state.audit = []; renderPlan();
      renderResult({ status: 'SUCCESS', attempts: 1, detail: '改动已落盘，校验通过',
        changes: [{ path: 'src/main/java/com/demo/Foo.java', created: false, bytes: 10, diff: '' }] });
      return 'ok';
    })()`);
    const resultText = await evaluate(`document.getElementById('result').textContent`);
    check(resultText.includes('编译校验通过'),
        '结果说的是「编译校验通过」，不让人读成「任务完成」：' + resultText);
    check(resultText.includes('src/main/java/com/demo/Foo.java'),
        '落盘的文件路径列出来了：' + resultText);

    // 目标路径的后缀提示：同目录都是 .js，只因少写后缀就提示一句（不拦）
    await setField('newpath', 'src/test/js/zz-probe');
    await sleep(500);
    const hintText = await evaluate(`document.getElementById('newpath-hint').textContent`);
    check(hintText.includes('.js'), '少了后缀时提示了同目录的惯例：' + hintText);
    await evaluate(`document.querySelector('#newpath-hint button').click(); 'ok'`);
    check(await evaluate(`document.getElementById('newpath').value`) === 'src/test/js/zz-probe.js',
        '点一下就补全成完整路径（目录没丢）：' + (await evaluate(`document.getElementById('newpath').value`)));
    await setField('newpath', 'src/test/js/zz-probe');
    await sleep(500);
    await evaluate(`document.querySelector('#newpath-hint .dismiss').click(); 'ok'`);
    check(await evaluate(`document.getElementById('newpath-hint').textContent`) === '',
        '点「不用了」就不再唠叨');
    await evaluate(`document.getElementById('newpath-hint').innerHTML = '';
                    document.getElementById('newpath').value = ''; 'ok'`);

    // 收拾干净（fetch 的桩不还原：挂到测试结束为止，见前面装桩处的说明）
    await evaluate(`(() => {
      state.plan = null;
      state.audit = [];
      state.forced = false;
      state.selected.delete('src/main/java/com/library/controller/HealthController.java');
      renderPlan();
      updatePicked();
      return 'ok';
    })()`);

    // ---------- 收尾 ----------
    console.log('\n整轮：');
    check(browserErrors.length === 0,
        '全程没有未捕获异常' + (browserErrors.length ? '：' + browserErrors.join(' | ') : ''));
  } finally {
    for (const name of CREATED) {
      try { await deleteTemplateOnServer(name); } catch (e) { /* 清理尽力而为 */ }
    }
    for (const name of CREATED_TASKS) {
      try {
        await fetch(BASE + 'api/tasks?name=' + encodeURIComponent(name), { method: 'DELETE' });
      } catch (e) { /* 清理尽力而为 */ }
    }
    for (const file of BROKEN_FILES) {
      try { fs.unlinkSync(file); } catch (e) { /* 清理尽力而为 */ }
    }
    // 链 13 在项目里造过临时目录/文件（模拟 IDE 侧的动作），跑完必须清干净
    for (const target of TEMP_PATHS) {
      try { fs.rmSync(target, { recursive: true, force: true }); } catch (e) { /* 清理尽力而为 */ }
    }
    // 把服务换回原项目。这条不管测试成没成功都要做——中途失败时它能把现场还原，
    // 不然下一次跑就是在别的项目上跑，看到一堆莫名其妙的失败。
    try {
      await fetch(BASE + 'api/open', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ path: PROJECT_ROOT }),
      });
    } catch (e) { /* 清理尽力而为 */ }
    if (otherProject) {
      // 临时项目删了，也把它从「最近打开」里去掉——不然它会以「已不在磁盘上」的样子
      // 一直留在真实的用户目录里
      try {
        await fetch(BASE + 'api/recent?path=' + encodeURIComponent(otherProject), { method: 'DELETE' });
      } catch (e) { /* 清理尽力而为 */ }
      try { fs.rmSync(otherProject, { recursive: true, force: true }); } catch (e) { /* 同上 */ }
    }
    try { ws && ws.close(); } catch (e) { /* ignore */ }
    chrome.kill();
    // Chrome 的 profile 目录是每次新建的，不删就会在 %TEMP% 里越堆越多
    await sleep(500);
    try {
      fs.rmSync(profile, { recursive: true, force: true, maxRetries: 3, retryDelay: 200 });
    } catch (e) { /* 清理尽力而为 */ }
  }
}

main()
  .then(() => {
    console.log(failed ? '\n失败 ' + failed + ' 项' : '\n全部通过');
    process.exitCode = failed ? 1 : 0;
  })
  .catch(e => {
    console.error('\n跑不下去：' + e.message);
    process.exitCode = 1;
  });
