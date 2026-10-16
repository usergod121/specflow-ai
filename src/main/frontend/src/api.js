/** 后端接口的唯一出口（API.md 的整张表）。**别处不许直接 fetch**。
 *  非 2xx 一律把后端的 `{"error": "…"}` 原样抛成 `ApiError`——界面要显示的是那句话本身。 */

export class ApiError extends Error {
  constructor(message, status, body) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.body = body;
  }
}

async function request(method, path, body) {
  const init = { method, headers: {} };
  if (body !== undefined) {
    init.headers['Content-Type'] = 'application/json';
    init.body = JSON.stringify(body);
  }
  let response;
  try {
    response = await fetch(path, init);
  } catch (e) {
    throw new ApiError('连不上本地服务：确认它还在跑，然后重试。', 0, null);
  }
  const text = await response.text();
  let parsed = null;
  if (text) {
    try {
      parsed = JSON.parse(text);
    } catch (e) {
      parsed = null;
    }
  }
  if (!response.ok) {
    const said = parsed && (parsed.error || parsed.message);
    throw new ApiError(said ? String(said) : text || ('HTTP ' + response.status), response.status, parsed);
  }
  return parsed;
}

const get = path => request('GET', path);
const post = (path, body) => request('POST', path, body === undefined ? {} : body);
const del = path => request('DELETE', path);

// 外壳
export const state = () => get('/api/state');
export const open = path => post('/api/open', { path });
export const browse = path => get('/api/browse?path=' + encodeURIComponent(path || ''));
export const recent = () => get('/api/recent');
export const forgetRecent = path => del('/api/recent?path=' + encodeURIComponent(path || ''));

// 流程
export const flow = () => get('/api/flow');
export const newFlow = () => post('/api/flow/new');
export const goto = node => post('/api/flow/goto', { node });
export const freeze = node => post('/api/flow/freeze', { node });
export const events = (key, from) =>
  get('/api/node/' + encodeURIComponent(key) + '/events?from=' + Number(from || 0));
export const cancel = () => post('/api/node/cancel');

// ① 环境
export const envAgent = () => post('/api/node/env/agent');
export const envSave = (envYaml, compileCommand) =>
  post('/api/node/env/save', { envYaml, compileCommand });
export const envView = () => get('/api/node/env/view');
export const envUp = () => post('/api/node/env/up');
export const envCompile = () => post('/api/node/env/compile');
export const envSkip = () => post('/api/node/env/skip');
export const envSettings = pullTimeoutMinutes =>
  post('/api/node/env/settings', { pullTimeoutMinutes });
export const envSettingsView = () => get('/api/node/env/settings');

// ② 探针
export const probeBrief = (requirement, design) =>
  post('/api/node/probe/brief', { requirement, design });
export const probeAgent = () => post('/api/node/probe/agent');
export const probeView = () => get('/api/node/probe/view');
export const probeSave = (files, changes, context, prompt) =>
  post('/api/node/probe/save', { files, changes, context, prompt });

// ③ 用例
export const cases = () => get('/api/node/cases');
export const casesSave = list => post('/api/node/cases/save', { cases: list });
export const casesAgent = () => post('/api/node/cases/agent');

// ④ 开发
export const devRun = () => post('/api/node/dev/run');
export const devView = () => get('/api/node/dev/view');
export const devRollback = () => post('/api/node/dev/rollback');

// ⑤ 测试
export const testRun = () => post('/api/node/test/run');
export const testApprovals = () => get('/api/node/test/approvals');
export const testApprove = (id, allow) => post('/api/node/test/approve', { id, allow });
export const testSubmit = summary => post('/api/node/test/submit', { summary });
export const testAccept = () => post('/api/node/test/accept');
export const testOptimize = () => post('/api/node/test/optimize');
