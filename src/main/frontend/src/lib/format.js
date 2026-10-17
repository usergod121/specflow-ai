/** 纯格式化函数：没有状态、没有副作用，好被单测钉住。 */

/** 秒 → 人话（1 位小数，够用了；超过 1 分钟给分秒）。 */
export function seconds(value) {
  const n = Number(value);
  if (!Number.isFinite(n) || n < 0) {
    return '';
  }
  if (n < 10) {
    return n.toFixed(1) + ' 秒';
  }
  if (n < 60) {
    return Math.round(n) + ' 秒';
  }
  const m = Math.floor(n / 60);
  const s = Math.round(n % 60);
  return `${m} 分 ${s} 秒`;
}

/** 事件时间戳（ISO 或 "2026-10-17T22:26:24"）→ HH:MM:SS。 */
export function clock(at) {
  const text = String(at || '');
  const hit = text.match(/(\d{2}):(\d{2}):(\d{2})/);
  return hit ? hit[1] + ':' + hit[2] + ':' + hit[3] : '';
}

/** 多行字符串 / 数组 → 行数组（'a\nb' 与 ['a','b'] 同义，后端的两种形状都吃）。 */
export function lines(value) {
  if (Array.isArray(value)) {
    return value.map((one) => String(one || '').trim()).filter(Boolean);
  }
  return String(value || '')
    .split(/\r?\n/)
    .map((one) => one.trim())
    .filter(Boolean);
}

/** 行数组 → 多行字符串（回写时用）。 */
export function join(list) {
  return (Array.isArray(list) ? list : []).join('\n');
}

/** 截断（保留换行的场景用 slice，不要压成一行）。 */
export function head(text, max = 240) {
  const value = String(text || '');
  return value.length > max ? value.slice(0, max) + '…' : value;
}

/** 只取第一行并截断（列表里那一行摘要）。 */
export function oneLine(text, max = 120) {
  const first = String(text || '').split(/\r?\n/).find((one) => one.trim()) || '';
  return head(first.trim(), max);
}
