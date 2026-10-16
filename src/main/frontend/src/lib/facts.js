/** facts → 徽章（纯函数）。UI.md §4：徽章只读后端落档的 `facts`，
 *  **不读「历史里出现过 error」**——所以一个节点成功之后不许还说它失败。 */

/** 值只有 `"true"` / `"false"` / 数字 / 短文本（API.md §2）。 */
export function factValue(value) {
  if (value === null || value === undefined || value === '') {
    return null;
  }
  if (typeof value === 'number') {
    return Number.isFinite(value) ? String(value) : null;
  }
  return String(value);
}

/** 每一枚徽章都得有形状：颜色不是唯一信号。 */
export function factMark(tone) {
  return tone === 'ok' ? '✓' : tone === 'danger' ? '✗' : tone === 'run' ? '●' : '—';
}

export function factTone(value) {
  const text = factValue(value);
  if (text === null) {
    return 'idle';
  }
  const low = text.toLowerCase();
  if (low === 'false' || low === 'no' || low === '0' || low === 'error' || low === 'failed') {
    return 'danger';
  }
  if (low === 'running' || low === 'pending') {
    return 'run';
  }
  return 'ok';
}

export function factBool(value) {
  const text = factValue(value);
  if (text === null) {
    return null;
  }
  return !['false', 'no', '0'].includes(text.toLowerCase());
}

export function factCount(value) {
  const number = Number(factValue(value));
  return Number.isFinite(number) ? number : 0;
}

/** 一枚徽章：`{key, text, tone, mark, title}`。 */
export function badgeOf(key, label, value, title) {
  const tone = factTone(value);
  return {
    key,
    text: label ? label + ' ' + (factValue(value) ?? '—') : (factValue(value) ?? '—'),
    tone,
    mark: factMark(tone),
    title: title || '',
  };
}

/** 节点那一行徽章：只有后端给了、而且认得出的键才画（**不补算**）。 */
export const KNOWN_FACTS = {
  envReady: '环境',
  compilePassed: '编译',
  caseCount: '用例',
  frozen: '冻结',
  probeDone: '探针',
  devDone: '开发',
  testPassed: '测试',
  rounds: '轮次',
};

export function badgesOf(facts, only) {
  const bag = facts || {};
  const keys = Array.isArray(only) && only.length ? only : Object.keys(bag);
  const rows = [];
  for (const key of keys) {
    if (!(key in bag)) {
      continue;
    }
    const label = KNOWN_FACTS[key] || key;
    const value = factValue(bag[key]);
    if (key === 'caseCount') {
      rows.push({
        key,
        text: label + ' ' + factCount(bag[key]),
        tone: factCount(bag[key]) > 0 ? 'ok' : 'idle',
        mark: factCount(bag[key]) > 0 ? '✓' : '—',
        title: '这一页的用例条数，后端数的',
      });
      continue;
    }
    rows.push(badgeOf(key, label, value));
  }
  return rows;
}

/** 冻没冻：只认后端落档的 `frozen` fact。 */
export function isFrozen(node) {
  return factBool(node && node.facts && node.facts.frozen) === true;
}

/** 状态条那一句（**≤6 个汉字**，UI.md §2 的六个词）。 */
export function statusTextOf(state, running, facts) {
  if (running) {
    return '进行中';
  }
  if (facts && factBool(facts.failed) === true) {
    return '失败';
  }
  const key = String(state || '').toUpperCase();
  if (key === 'DONE') {
    return '已完成';
  }
  if (key === 'READY') {
    return '已就绪';
  }
  return '未开始';
}

/** 「为什么还不能点」/「它现在在等什么」——只有后端说过话才显示（UI.md §4）。 */
export function noteOf(node) {
  const reason = String((node && node.blockedReason) || '').trim();
  return reason || '';
}
