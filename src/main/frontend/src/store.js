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

let pollTimer = 0;
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
  store.failures = [...store.failures, {
    id: 'f' + failureSeq,
    where: owner(scope, action),
    text: said || '没成，后端没说原因。',
    status: Number((error && error.status) || 0),
  }];
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

/** 一次性调用：转圈 → 失败就地留下（**不抛**，抛了只会在控制台多一条没人看的红字）。 */
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
  await loadRecent();
  return done;
}

export async function loadFlow() {
  const view = await api.flow();
  if (store.flow && view && view.flowId && store.flow.flowId && view.flowId !== store.flow.flowId) {
    resetStream();
  }
  store.flow = view;
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

export function startStream(node) {
  if (store.node === node && pollTimer) {
    return;
  }
  stopStream();
  store.node = node;
  store.events = [];
  store.cursor = 0;
  store.running = false;
  store.stage = '';
  ticks = 0;
  const tick = async () => {
    const wasRunning = store.running;
    try {
      await pullEvents();
    } catch (e) {
      // 轮询本身出问题不进失败面板（那是「用户点了东西没成」才配占的位置）
      store.stage = String((e && e.message) || e);
    }
    ticks += 1;
    if (wasRunning && !store.running) {
      store.settle += 1;
      await loadFlow().catch(() => {});
    } else if (ticks % FLOW_EVERY === 0) {
      await loadFlow().catch(() => {});
    }
  };
  pollTimer = setInterval(tick, POLL_MS);
  tick();
}

export function stopStream() {
  if (pollTimer) {
    clearInterval(pollTimer);
    pollTimer = 0;
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

/** 拉一次增量；`next` 记下来当下次的 `from`；`running=false` 就停轮询。 */
export async function pullEvents() {
  if (!store.node) {
    return;
  }
  const view = (await api.events(store.node, store.cursor)) || {};
  store.events = mergeById(store.events, view.events || []);
  store.cursor = Math.max(store.cursor, Number(view.next || store.cursor));
  store.running = view.running === true;
  store.stage = String(view.stage || '');
  if (!store.running) {
    stopStream();
  }
}

/** 长任务：起活 → 轮询到 `running=false` 为止（停止轮询由 pullEvents 负责）。 */
export async function runAction(scope, action, start, work) {
  return act(scope, action, async () => {
    const accepted = await start();
    if (accepted && accepted.cursor !== undefined && accepted.cursor !== null) {
      store.cursor = Math.max(store.cursor, Number(accepted.cursor) || 0);
    }
    store.running = true;
    if (!pollTimer) {
      startStream(store.node);
    }
    return work ? work(accepted) : accepted;
  });
}
