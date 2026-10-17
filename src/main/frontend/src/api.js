/**
 * 所有 HTTP 都在这个文件里（**只有这一处发请求**）。
 *
 * 规矩：
 *  - 后端错误正文恒为 `{"error":"一句人话"}`（docs/API.md §0），这里只负责把它原样带出来；
 *    界面**照原话显示**，不许换成"操作失败"。
 *  - 409 = 现在不能这么做（闸没过 / 还有活没跑完），400 = 参数不合法，500 = 引擎自己炸了。
 */

export class ApiError extends Error {
  constructor(status, message) {
    super(message || '后端没说原因');
    this.status = status;
  }
}

async function call(method, path, body) {
  let response;
  try {
    response = await fetch(path, {
      method,
      headers: body === undefined ? undefined : { 'content-type': 'application/json' },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
  } catch (e) {
    throw new ApiError(0, '连不上引擎：它可能已经退出了（看 start.bat 那个窗口）');
  }
  const text = await response.text();
  let payload = null;
  try {
    payload = text ? JSON.parse(text) : null;
  } catch {
    payload = null;
  }
  if (!response.ok) {
    throw new ApiError(response.status, (payload && payload.error) || text || `HTTP ${response.status}`);
  }
  return payload;
}

const get = (path) => call('GET', path);
const post = (path, body) => call('POST', path, body === undefined ? {} : body);
const del = (path) => call('DELETE', path);

// ---------- 外壳 ----------
export const state = () => get('/api/state');
export const open = (path) => post('/api/open', { path });
export const browse = (path) => get('/api/browse?path=' + encodeURIComponent(path || ''));
export const recent = () => get('/api/recent');
export const forget = (path) => del('/api/recent?path=' + encodeURIComponent(path));

// ---------- 流程 ----------
export const flow = () => get('/api/flow');
export const flowNew = () => post('/api/flow/new');
export const flowGoto = (node) => post('/api/flow/goto', { node });
export const flowFreeze = (node) => post('/api/flow/freeze', { node });
export const flows = (limit = 20) => get('/api/flows?limit=' + limit);
export const nodeEvents = (key, from = 0) => get(`/api/node/${key}/events?from=${from}`);
export const cancel = () => post('/api/node/cancel');
export const streamUrl = (key, from = 0) => `/api/node/${key}/stream?from=${from}`;

// ---------- ① 环境 ----------
export const envView = () => get('/api/node/env/view');
export const envSave = (envYaml, compileCommand) => post('/api/node/env/save', { envYaml, compileCommand });
export const envUp = () => post('/api/node/env/up');
export const envCompile = () => post('/api/node/env/compile');
export const envSkip = () => post('/api/node/env/skip');
export const envAgent = (note = '') => post('/api/node/env/agent', { note });
export const envSettings = () => get('/api/node/env/settings');
export const envSetTimeout = (pullTimeoutMinutes) => post('/api/node/env/settings', { pullTimeoutMinutes });

// ---------- ② 探针 ----------
export const probeView = () => get('/api/node/probe/view');
export const probeBrief = (requirement, design) => post('/api/node/probe/brief', { requirement, design });
export const probeSave = (files, changes, context, prompt) =>
  post('/api/node/probe/save', { files, changes, context, prompt });
export const probeAgent = () => post('/api/node/probe/agent');

// ---------- ③ 用例 ----------
export const cases = () => get('/api/node/cases');
export const casesSave = (list) => post('/api/node/cases/save', { cases: list });
export const casesAgent = () => post('/api/node/cases/agent');

// ---------- ④ 开发 ----------
export const devView = () => get('/api/node/dev/view');
export const devRun = () => post('/api/node/dev/run');
export const devRollback = () => post('/api/node/dev/rollback');

// ---------- ⑤ 测试 ----------
export const testRun = () => post('/api/node/test/run');
export const testRetry = (id) => post('/api/node/test/case/retry', { id });
export const testCheckpoint = () => get('/api/node/test/checkpoint');
export const testResume = () => post('/api/node/test/resume');
export const testApprovals = () => get('/api/node/test/approvals');
export const testApprove = (id, allow) => post('/api/node/test/approve', { id, allow });
export const testSubmit = (summary) => post('/api/node/test/submit', { summary });
export const testAccept = () => post('/api/node/test/accept');
export const testOptimize = () => post('/api/node/test/optimize');
