/** 界面这一段共享的状态与动作。**闸不在这里判**——能不能点、为什么不能，
 *  一律读后端的 `state` / `blockedReason` / 409 原话（UI.md §4）。 */

import { reactive } from 'vue';
import * as api from './api.js';
import { mergeById } from './lib/events.js';

const POLL_MS = 1200;
const FLOW_EVERY = 5;

export const store = reactive({
  booted: false,
  shellError: '',
  open: false,
  root: '',
  projectName: '',
  hasModel: false,
  recent: [],
  browsing: null,
  /** 每个节点一次画几枚 chip（默认 1：只画当前那一枚）。 */
  paging: {},

  flow: null,
  node: '',

  events: [],
  cursor: 0,
  running: false,
  stage: '',
  /** 每收场一次 +1：页面 watch 它重读自己的产物。 */
  settle: 0,

  cases: null,
  approvals: [],
  probe: null,
  dev: null,
  envSaved: { envYaml: '', compileCommand: '' },

  busy: {},
  failures: [],
  paging: {},
  toast: '',
});

let ticks = 0;
let failureSeq = 0;
let toastTimer = 0;

const owner = (scope, action) => scope + ':' + action;

export function busyOf(scope, action) {
  return store.busy[owner(scope, action)] === true;
}

export function runningNow() {
  return store.running === true;
}

export function fail(scope, action, error) {
  failureSeq += 1;
  const said = String((error && error.message) || error || '').trim();
  const text = said || '没成，后端没说原因。';
  const where = owner(scope, action);
  // 同一条话、同一个位置：只留一份（用户实报过「两片红字叠在一起」）
  if (!store.failures.some(one => one.where === where && one.text === text)) {
    store.failures = [...store.failures, {
      id: 'f' + failureSeq,
      where,
      text,
      status: Number((error && error.status) || 0),
    }];
  }
  return said;
}

export function dismiss(id) {
  store.failures = store.failures.filter(one => one.id !== id);
}

export function failuresOf(scope, action) {
  return store.failures.filter(one => one.where === owner(scope, action));
}

export function say(text) {
  store.toast = String(text || '');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { store.toast = ''; }, 2600);
}

/** 一次性命调用：转圈 → 失败就地留下（**不抛**，抛了只会在控制台多一条没人看的红字）。 */
export async function act(scope, action, work) {
  const key = owner(scope, action);
  if (store.busy[key]) {
    return { ok: false, busy: true };
  }
  store.busy[key] = true;
  try {
    return { ok: true, value: await work() };
  } catch (e) {
    fail(scope, action, e);
    // 409 基本只有两种意思：闸没过（要看最新的 blockedReason）／还有一段在跑（要露出「叫停」）。
    // 两种都得**重新问一遍后端**——否则界面会精神分裂：它说在跑，我却看不到叫停按钮。
    if (Number((e && e.status) || 0) === 409) {
      await loadFlow().catch(() => {});
    }
    return { ok: false, error: e };
  } finally {
    delete store.busy[key];
  }
}

// ---------- 外壳 ----------

function applyState(view) {
  store.open = view.open === true;
  store.root = String(view.root || (view.project && view.project.path) || '');
  store.projectName = String((view.project && view.project.name) || '');
  store.hasModel = view.hasModel === true;
  store.booted = true;
}

export async function boot() {
  try {
    applyState(await api.state());
  } catch (e) {
    store.shellError = String((e && e.message) || e);
    store.booted = true;
    return;
  }
  await loadRecent();
  if (store.open) {
    await loadFlow().catch(() => {});
  }
}

export async function loadRecent() {
  const view = await api.recent().catch(() => ({ recent: [] }));
  store.recent = (view && view.recent) || [];
}

export async function openAt(path) {
  const done = await act('welcome', 'open', () => api.open(path));
  if (!done.ok) {
    return done;
  }
  applyState(done.value);
  // **换项目 = 换一条流程**：这里原来只更新了外壳，没重新拉 flow —— 于是界面继续画上一个项目的
  // 五格状态（实报：在 A 项目冻了两格，切到 B 项目还是那两格）。顺带把旧项目的流也停掉，
  // 否则它还往这条页面推 A 项目的事件。
  resetStream();
  store.flow = null;
  store.failures = [];
  store.cursor = 0;
  await loadRecent();
  await loadFlow().catch(() => {});
  return done;
}

export async function loadFlow() {
  const view = await api.flow();
  if (store.flow && view && view.flowId && store.flow.flowId && view.flowId !== store.flow.flowId) {
    resetStream();
  }
  store.flow = view;
  // **`running` 也必须跟着刷新**：它决定「叫停」露不露面。只靠事件流更新的话，
  // 一旦流没连上（换项目、刚刷新、流断了），后端说"还有一段在跑"而界面没有叫停按钮——
  // 用户就被锁死了（他就是这么撞的：提示叫他点「叫停」，可那一枚按钮根本没渲染）。
  store.running = view.running === true;
  if (typeof view.stage === 'string' && view.stage) {
    store.stage = view.stage;
  }
  return view;
}

export function nodeView(key) {
  const list = (store.flow && store.flow.nodes) || [];
  return list.find(one => one && one.key === key) || { key, state: '', blockedReason: '', facts: {} };
}

export async function freezeNode(key) {
  const done = await act(key, 'freeze', () => api.freeze(key));
  if (done.ok) {
    await loadFlow().catch(() => {});
    say('已冻结');
  }
  return done;
}

export async function goNode(key) {
  const done = await act('flow', 'goto', () => api.goto(key));
  if (done.ok) {
    await loadFlow().catch(() => {});
  }
  return done;
}

// ---------- 事件流 ----------
//
// 两条路，主路 SSE、备路轮询：
//   - SSE（`GET /api/node/<k>/stream?from=N`）：服务端有新行就推，界面顺着出现；
//   - 轮询（`GET /api/node/<k>/events?from=N`）：浏览器没有 EventSource、或 SSE 连不上时用。
// 两条路的**载荷与游标语义完全一样**（events/next/running/stage，id ≡ 行号），
// 所以换路不需要任何补偿逻辑，断线重连也只是带同一个游标再订一次。

let eventSource = null;
let pollTimer = 0;
let flowTimer = 0;
let retries = 0;

/** 把一份增量视图吃进 store。两条路共用这一个口子（两套合并逻辑 = 两份真相）。 */
function ingest(view) {
  if (!view) {
    return store.running;
  }
  const wasRunning = store.running;
  store.events = mergeById(store.events, view.events || []);
  store.cursor = Math.max(store.cursor, Number(view.next || store.cursor));
  store.running = view.running === true;
  store.stage = String(view.stage || '');
  if (wasRunning && !store.running) {
    store.settle += 1;
    loadFlow().catch(() => {});
  }
  return store.running;
}

function closeSource() {
  if (eventSource) {
    try {
      eventSource.close();
    } catch (e) {
      // 关不掉也不算事
    }
    eventSource = null;
  }
}

function startPolling() {
  if (pollTimer) {
    return;
  }
  pollTimer = setInterval(async () => {
    try {
      const view = await api.events(store.node, store.cursor);
      if (!ingest(view)) {
        stopStream();
      }
    } catch (e) {
      store.stage = String((e && e.message) || e);
    }
  }, POLL_MS);
}

function openSource() {
  if (typeof EventSource === 'undefined') {
    startPolling();
    return;
  }
  const url = `/api/node/${encodeURIComponent(store.node)}/stream?from=${store.cursor}`;
  const source = new EventSource(url);
  eventSource = source;
  source.addEventListener('events', (e) => {
    try {
      ingest(JSON.parse(e.data));
      retries = 0;
    } catch (err) {
      store.stage = String((err && err.message) || err);
    }
  });
  source.addEventListener('done', (e) => {
    try {
      ingest(JSON.parse(e.data));
    } catch (err) {
      // 最后一帧解析不出来不影响收场
    }
    closeSource();
    stopStream();
  });
  source.onerror = () => {
    // EventSource 自己会重连；连不上几次就退回轮询——别让界面假装还连着
    retries += 1;
    if (retries >= 3) {
      closeSource();
      startPolling();
    }
  };
}

export function startStream(node) {
  if (store.node === node && (eventSource || pollTimer)) {
    return;
  }
  stopStream();
  store.node = node;
  store.events = [];
  store.cursor = 0;
  store.running = false;
  store.stage = '';
  ticks = 0;
  retries = 0;
  openSource();
  // 顺手刷一下流程视图（节点状态在别人冻结/回写时会变，与事件流是两件事）
  if (!flowTimer) {
    flowTimer = setInterval(() => {
      if (store.running) {
        loadFlow().catch(() => {});
      }
    }, FLOW_EVERY * POLL_MS);
  }
}

export function stopStream() {
  closeSource();
  if (pollTimer) {
    clearInterval(pollTimer);
    pollTimer = 0;
  }
  if (flowTimer) {
    clearInterval(flowTimer);
    flowTimer = 0;
  }
}

export function resetStream() {
  stopStream();
  store.node = '';
  store.events = [];
  store.cursor = 0;
  store.running = false;
  store.stage = '';
}

/** 拉一次增量（SSE 之外的备用/首屏用）；`next` 记下来当下次的 `from`。 */
export async function pullEvents() {
  if (!store.node) {
    return;
  }
  const view = (await api.events(store.node, store.cursor)) || {};
  if (!ingest(view)) {
    stopStream();
  }
}

/**
 * 长任务：起活 → 订阅到 `running=false` 为止。
 *
 * <p>起完活先把游标推到"现在"（`accepted.cursor`），再确保订阅在——这样
 * 从"点下去"到"订阅建好"之间那几条事件也不会漏（服务端按 from 补）。
 */
export async function runAction(scope, action, start, work) {
  return act(scope, action, async () => {
    const accepted = await start();
    if (accepted && accepted.cursor !== undefined && accepted.cursor !== null) {
      store.cursor = Math.max(store.cursor, Number(accepted.cursor) || 0);
    }
    store.running = true;
    if (!eventSource && !pollTimer) {
      startStream(store.node);
    }
    return work ? work(accepted) : accepted;
  });
}
