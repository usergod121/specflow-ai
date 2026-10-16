/** 文案裁剪（纯函数）。UI.md §5 的预算是按**汉字**数的，所以这里只数 CJK 表意字，
 *  标点、数字、字母都不计数——「12 次调用」是 2 个汉字，不是 6 个字。 */

const HAN = /[\u3400-\u4dbf\u4e00-\u9fff]/g;

/** 不带 g 的那一份：`test()` 不能带全局标志，否则 lastIndex 会在调用之间残留。 */
const ONE_HAN = /[\u3400-\u4dbf\u4e00-\u9fff]/;

export function countHan(text) {
  const found = String(text ?? '').match(HAN);
  return found ? found.length : 0;
}

export function withinHan(text, budget) {
  return countHan(text) <= Number(budget);
}

/** 超预算就砍到最后一个汉字并加省略号；砍不动（全是标点）就原样返回。 */
export function fitHan(text, budget) {
  const source = String(text ?? '');
  if (countHan(source) <= budget) {
    return source;
  }
  let seen = 0;
  let cut = source.length;
  for (let at = 0; at < source.length; at += 1) {
    if (ONE_HAN.test(source[at])) {
      seen += 1;
      if (seen > budget) {
        cut = at;
        break;
      }
    }
  }
  return source.slice(0, cut).replace(/[\s、，,。.；;：:]+$/, '') + '…';
}

/** 这些词一律不许出现在界面上（UI.md §5）。 */
export const BANNED = ['点击这里', '您可以', '请注意', '温馨提示'];

export function findBanned(text) {
  const source = String(text ?? '');
  return BANNED.filter(word => source.includes(word));
}

export function hasBanned(text) {
  return findBanned(text).length > 0;
}

/** 压成一行：空白折叠、去掉首尾（chip 摘要与日志行都用它）。 */
export function oneLine(text) {
  return String(text ?? '').replace(/\s+/g, ' ').trim();
}

/** 一行、最长 `max` 列，超了留一个省略号。 */
export function clip(text, max) {
  const flat = oneLine(text);
  return flat.length > max ? flat.slice(0, Math.max(1, max - 1)) + '…' : flat;
}

/** 毫秒 → 人读的短串（等宽字体里用它，所以短而固定）。 */
export function millisText(ms) {
  const value = Number(ms);
  if (ms === null || ms === undefined || Number.isNaN(value) || value < 0) {
    return '';
  }
  if (value < 1000) {
    return Math.round(value) + 'ms';
  }
  if (value < 60_000) {
    return (value / 1000).toFixed(value < 10_000 ? 1 : 0) + 's';
  }
  const minutes = Math.floor(value / 60_000);
  return minutes + 'm' + Math.round((value % 60_000) / 1000) + 's';
}

/** `2026-10-16T15:38:44` → `15:38:44`（只有日期没时间就原样返回）。 */
export function clockText(at) {
  const found = /T(\d{2}:\d{2}:\d{2})/.exec(String(at ?? ''));
  return found ? found[1] : oneLine(at).slice(-8);
}

/** 路径末段（`E:\a\b` 与 `/a/b` 都认）。 */
export function baseName(path) {
  const parts = String(path ?? '').split(/[\\/]+/).filter(Boolean);
  return parts.length ? parts[parts.length - 1] : '';
}
