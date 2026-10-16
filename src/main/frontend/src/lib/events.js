/** 事件流 → 日志行 / chip（纯函数，不碰 DOM、不碰 Vue）。
 *
 *  API.md §8：工具调用推两条（running + ok|error），两条共用 callId。
 *  两条**必须合成一枚** chip，而且终态之后再来 running 不许退回去。 */

import { clip, clockText, millisText, oneLine } from './text.js';

const TERMINAL = { ok: 1, error: 1, running: 0 };

export function callIdOf(event) {
  const raw = event ? (event.callId ?? event.call_id) : '';
  return raw === null || raw === undefined || String(raw).trim() === '' ? '' : String(raw);
}

export function isChipEvent(event) {
  if (!event) {
    return false;
  }
  return Boolean(callIdOf(event))
    || String(event.type || '').toLowerCase() === 'tool'
    || Boolean(event.tool);
}

export function chipStatus(event) {
  const raw = String((event && (event.status ?? event.state)) || '').toLowerCase();
  if (raw === 'running' || raw === 'start' || raw === 'started') {
    return 'running';
  }
  if (raw === 'error' || raw === 'failed' || raw === 'fail') {
    return 'error';
  }
  if (raw === 'ok' || raw === 'done' || raw === 'success') {
    return 'ok';
  }
  return String((event && event.level) || '') === 'error' ? 'error' : 'ok';
}

export function argText(raw) {
  if (raw === null || raw === undefined || raw === '') {
    return '';
  }
  return typeof raw === 'string' ? raw : JSON.stringify(raw);
}

/** 参数摘要：像命令行那样读（`read_file pom.xml`）。原文一字不改地留在展开区。 */
export function argHeadline(raw) {
  const text = argText(raw);
  if (!text.trim()) {
    return '无参数';
  }
  let parsed;
  try {
    parsed = JSON.parse(text);
  } catch (e) {
    return clip(text, 44);
  }
  if (Array.isArray(parsed)) {
    return parsed.length ? parsed.length + ' 项' : '空';
  }
  if (parsed && typeof parsed === 'object') {
    const keys = Object.keys(parsed);
    if (!keys.length) {
      return '空';
    }
    const scalar = keys.find(key => typeof parsed[key] === 'string' || typeof parsed[key] === 'number');
    if (scalar !== undefined) {
      return clip(parsed[scalar], 44) || scalar;
    }
    return keys[0] + ' ' + clip(argText(parsed[keys[0]]), 32);
  }
  return clip(parsed, 44);
}

/** 一条事件 → 一枚 chip。 */
export function chipFromEvent(event, index) {
  const argsRaw = argText(event ? (event.args ?? event.arguments) : '');
  const result = String((event && event.result) ?? '');
  const full = String((event && event.resultFull) ?? '') || result;
  return {
    key: callIdOf(event) || 's' + index,
    callId: callIdOf(event),
    tool: String((event && event.tool) || ''),
    argsRaw,
    arg: argHeadline(argsRaw),
    status: chipStatus(event),
    result,
    resultFull: full,
    ms: event && event.millis !== undefined && event.millis !== null ? Number(event.millis) : null,
    msText: millisText(event ? event.millis : null),
    at: clockText(event && event.at),
    round: Number((event && event.round) || 0),
  };
}

/** 同一 callId 的两条并进一枚：谁有内容用谁的，终态**单向**推进。 */
export function mergeChip(prev, next) {
  if (!prev) {
    return next;
  }
  if (!next) {
    return prev;
  }
  const status = TERMINAL[next.status] >= TERMINAL[prev.status] ? next.status : prev.status;
  const argsRaw = next.argsRaw.length > prev.argsRaw.length ? next.argsRaw : prev.argsRaw;
  const ms = next.ms === null || next.ms === undefined ? prev.ms : next.ms;
  const full = next.resultFull || next.result || prev.resultFull || prev.result;
  return {
    ...prev,
    tool: prev.tool || next.tool,
    argsRaw,
    arg: argHeadline(argsRaw),
    status,
    // 失败/耗时以终态那条为准，但「一开始为什么调」的时间取先到的那条
    result: next.status === 'running' ? prev.result : (next.result || prev.result),
    resultFull: status === 'running' ? (prev.resultFull || full) : full,
    ms,
    msText: millisText(ms),
    at: prev.at || next.at,
    round: prev.round || next.round,
  };
}

/** 事件流 → 一列 chip + 「12 次调用 · 1 报错」要的那几个数。 */
export function mergeChips(events, options) {
  const bag = options || {};
  const limit = Math.max(1, Number(bag.limit) || 1);
  const order = [];
  const byKey = new Map();
  for (const event of events || []) {
    if (!isChipEvent(event)) {
      continue;
    }
    if (bag.node && event.node && event.node !== bag.node) {
      continue;
    }
    const fresh = chipFromEvent(event, order.length + 1);
    if (byKey.has(fresh.key)) {
      // 位置不动：chip 停在它第一次出现的地方，原地推进状态
      byKey.set(fresh.key, mergeChip(byKey.get(fresh.key), fresh));
      continue;
    }
    byKey.set(fresh.key, fresh);
    order.push(fresh.key);
  }
  const all = order.map(key => byKey.get(key));
  const shown = all.slice(Math.max(0, all.length - limit));
  return {
    chips: shown,
    total: all.length,
    hidden: all.length - shown.length,
    failed: all.filter(chip => chip.status === 'error').length,
    running: all.some(chip => chip.status === 'running'),
  };
}

/** 形状 + 文字，颜色永远不是唯一信号。 */
export function chipMark(status) {
  return status === 'running' ? '●' : status === 'error' ? '✗' : '✓';
}

export function chipWord(status) {
  return status === 'running' ? '进行中' : status === 'error' ? '失败' : '已完成';
}

/** 事件流 → 归档日志行（只追加；**最新在最上面**由渲染方反转）。 */
export function logLines(events, node) {
  const rows = [];
  for (const event of events || []) {
    const type = String((event && event.type) || 'log').toLowerCase();
    if (type === 'tool' || isChipEvent(event)) {
      continue;
    }
    if (node && event.node && event.node !== node) {
      continue;
    }
    const text = oneLine(event.text) || oneLine(event.result) || (type === 'gate' ? '闸没过' : '');
    rows.push({
      id: event.id === undefined || event.id === null ? rows.length + 1 : event.id,
      at: clockText(event.at),
      level: String(event.level || (type === 'gate' ? 'warn' : 'info')),
      text,
      raw: event,
    });
  }
  return rows;
}

/** chip 那一行：「12 次调用 · 1 报错」。 */
export function chipSummary(view) {
  if (!view || !view.total) {
    return '';
  }
  return view.failed ? view.total + ' 次调用 · ' + view.failed + ' 报错' : view.total + ' 次调用';
}

/** 事件流里最后一条 `type=result` 的 payload（API.md §9）——那一页的结论就靠它。 */
export function lastPayload(events, node) {
  for (let at = (events || []).length - 1; at >= 0; at -= 1) {
    const event = events[at];
    if (event && event.type === 'result' && event.payload && (!node || !event.node || event.node === node)) {
      return event.payload;
    }
  }
  return null;
}

/** 增量拉回来的事件按 id 去重合并（`from=` 重放也不会画两遍）。 */
export function mergeById(known, fresh) {
  const seen = new Map();
  for (const event of known || []) {
    seen.set(event.id, event);
  }
  for (const event of fresh || []) {
    seen.set(event.id, event);
  }
  return [...seen.values()].sort((left, right) => Number(left.id) - Number(right.id));
}
