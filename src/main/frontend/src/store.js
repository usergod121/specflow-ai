import { reactive } from 'vue';
import * as api from './api.js';

/**
 * 全站唯一的状态与唯一的刷新口。
 *
 * 铁律（这次重写的地基）：**界面不猜任何后端已经知道的事**。
 * 每一次调用（成功或失败）之后都 await refresh()：五格状态、闸那句话、facts、动作表、
 * running 全部重新问一遍。所以：
 *  - 换项目之后不可能还显示上一个项目的五格；
 *  - 后端说「还有一段在跑」，界面就一定拿得到 running=true，于是「叫停」一定在；
 *  - 一个动作改了什么，界面看到的是流程里的新值，不是本地拼出来的。
 * 代价是每个动作多一个很小的 GET；换来的是状态永不发霉。
 */
export const store = reactive({
  boot: false,
  bootError: '',
  open: false,
  root: '',
  projectName: '',
  hasModel: false,
  recent: [],

  flow: null,
  node: '',
  running: false,
  stage: '',
  busy: {},
  failures: [],
  toast: '',

  events: {},
  cursors: {},
  streaming: {},
});

let toastTimer = 0;
let heartbeat = 0;
let failureSeq = 0;

// ---------- 小工具 ----------

export function say(text) {
  store.toast = String(text || '');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => {
    store.toast = '';
  }, 2400);
}

export function busy(key) {
  return store.busy[key] === true;
}

export function node(key) {
  const list = (store.flow && store.flow.nodes) || [];
  return list.find((one) => one && one.key === key)
    || { key, state: '', blockedReason: '', facts: {}, actions: [] };
}

export function actionsOf(key) {
  return node(key).actions || [];
}

// ---------- 失败：就地留一条、可关掉；同一条不重复堆 ----------

export function pushFailure(scope, error) {
  const text = String((error && error.message) || error || '没成，后端没说原因');
  const status = Number((error && error.status) || 0);
  if (store.failures.some((one) => one.scope === scope && one.text === text)) {
    return;
  }
  failureSeq += 1;
  store.failures = [...store.failures, { id: 'f' + failureSeq, scope, text, status }];
}

export function failuresOf(scope) {
  return store.failures.filter((one) => one.scope === scope);
}

export function dismiss(id) {
  store.failures = store.failures.filter((one) => one.id !== id);
}

// ---------- 刷新（唯一口） ----------

export async function refresh() {
  const flow = await api.flow();
  store.flow = flow;
  store.running = flow.running === true;
  store.stage = flow.stage || '';
  syncHeartbeat();
  return flow;
}

/** 有活在跑时补一个 1.5 秒轮询：SSE 断了也不会出现「按钮永远灰着」。 */
function syncHeartbeat() {
  if (store.running && !heartbeat) {
    heartbeat = setInterval(() => {
      refresh().catch(() => {});
    }, 1500);
  } else if (!store.running && heartbeat) {
    clearInterval(heartbeat);
    heartbeat = 0;
  }
}

/**
 * 一切会改变后端状态的调用都走这里。
 *
 * @param key  用于「这一个正在忙」（同一个 key 不并发）
 * @param work 真正干活的那一下（api.xxx）
 */
export async function act(key, work) {
  if (store.busy[key]) {
    return { ok: false, busy: true };
  }
  store.busy[key] = true;
  try {
    const value = await work();
    await refresh().catch(() => {});
    return { ok: true, value };
  } catch (error) {
    pushFailure(key, error);
    // 409 的两种意思（闸没过 / 还有活没跑完）都得重新问一遍后端，否则界面会精神分裂
    if (Number(error && error.status) === 409) {
      await refresh().catch(() => {});
    }
    return { ok: false, error };
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
}

export async function boot() {
  try {
    applyState(await api.state());
  } catch (error) {
    store.bootError = String((error && error.message) || error);
    store.boot = true;
    return;
  }
  await loadRecent();
  if (store.open) {
    await refresh().catch(() => {});
  }
  store.boot = true;
}

export async function loadRecent() {
  const view = await api.recent().catch(() => ({ recent: [] }));
  store.recent = (view && view.recent) || [];
}

/**
 * 打开 / 换项目：**五格全部重来**。
 *
 * 必须清掉旧项目的一切（flow、事件流、游标、失败条），否则会看到
 * 「在 A 项目冻了两格，换到 B 项目还是那两格」——上一版最刺眼的一个 bug。
 */
export async function openProject(path) {
  const done = await act('project:open', () => api.open(path));
  if (!done.ok) {
    return done;
  }
  applyState(done.value);
  store.flow = null;
  store.running = false;
  store.stage = '';
  store.failures = [];
  store.events = {};
  store.cursors = {};
  closeStreams();
  await loadRecent();
  await refresh().catch(() => {});
  return done;
}

// ---------- 事件流：SSE 主路，轮询备路 ----------

function push(key, view) {
  const older = store.events[key] || [];
  const rows = (view && view.events) || [];
  const known = new Set(older.map((one) => one.id));
  store.events[key] = older.concat(rows.filter((one) => !known.has(one.id)));
  if (view && typeof view.next === 'number') {
    store.cursors[key] = view.next;
  }
  if (view && typeof view.running === 'boolean') {
    store.running = view.running;
    syncHeartbeat();
  }
  if (view && typeof view.stage === 'string' && view.stage) {
    store.stage = view.stage;
  }
}

/** 打开某一格的事件流（同一时刻只连一条，切页面时旧的关掉）。 */
export function openStream(key) {
  closeStreams();
  const from = store.cursors[key] || 0;
  const source = new EventSource(api.streamUrl(key, from));
  store.streaming[key] = source;

  source.addEventListener('events', (event) => {
    try {
      push(key, JSON.parse(event.data));
    } catch {
      // 一帧坏数据不该让流停下
    }
  });
  source.addEventListener('ping', () => {});
  source.addEventListener('done', () => {
    try {
      push(key, JSON.parse(event.data || '{}'));
    } catch {
      // 最后一帧坏了不影响已经渲染的
    }
    source.close();
    delete store.streaming[key];
    refresh().catch(() => {});
  });
  source.onerror = async () => {
    // 断了就退回轮询（同形载荷、同游标），并把「正在跑」重新问一遍
    source.close();
    delete store.streaming[key];
    try {
      push(key, await api.nodeEvents(key, store.cursors[key] || 0));
    } catch {
      // 后端也没了：下一次动作会给出人话
    }
    await refresh().catch(() => {});
  };
}

/** 进页面先补一次历史（SSE 只推增量）。 */
export async function pullEvents(key) {
  try {
    push(key, await api.nodeEvents(key, 0));
  } catch {
    // 没历史不算错
  }
}

export function closeStreams() {
  for (const key of Object.keys(store.streaming)) {
    store.streaming[key].close();
    delete store.streaming[key];
  }
}
