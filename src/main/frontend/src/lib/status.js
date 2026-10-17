/**
 * 状态词与时序圆点：**全站只有这一处翻译**。
 *
 * 三态的词必须一眼分得开：
 *   未开始 / 待完成 / 已完成 —— 再加「进行中」「失败」两个动作态。
 * 上一版把 READY 翻成「已就绪」，在 ① 上被读成"环境已经起好了"，而用户还得去点「起环境」。
 */
export function statusWord(state, running) {
  if (running) {
    return '进行中';
  }
  switch (String(state || '').toUpperCase()) {
    case 'DONE':
      return '已完成';
    case 'READY':
      return '待完成';
    case 'LOCKED':
      return '未开始';
    default:
      return '未开始';
  }
}

export function toneOf(state, running) {
  if (running) {
    return 'run';
  }
  switch (String(state || '').toUpperCase()) {
    case 'DONE':
      return 'done';
    case 'READY':
      return 'ready';
    default:
      return '';
  }
}

/** 状态条的 pill 色（与 toneOf 同一套语义，但 pill 还需要「失败」这一档）。 */
export function pillTone(state, running, failed) {
  if (running) {
    return 'run';
  }
  if (failed === true) {
    return 'bad';
  }
  switch (String(state || '').toUpperCase()) {
    case 'DONE':
      return 'done';
    case 'READY':
      return 'ready';
    default:
      return '';
  }
}

/** facts 里那个 "true"/"false" 字符串。 */
export function flag(facts, key) {
  return String((facts && facts[key]) || '').toLowerCase() === 'true';
}
