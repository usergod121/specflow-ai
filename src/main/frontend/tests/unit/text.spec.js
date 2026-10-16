import { describe, expect, it } from 'vitest';
import {
  baseName, clip, clockText, countHan, findBanned, fitHan, hasBanned, millisText, oneLine, withinHan,
} from '../../src/lib/text.js';

describe('数汉字（UI.md §5 的预算按汉字数）', () => {
  it('只数 CJK 表意字', () => {
    expect(countHan('还没跑')).toBe(3);
    expect(countHan('12 次调用 · 1 报错')).toBe(5);
    expect(countHan('）。，、')).toBe(0);
    expect(countHan('')).toBe(0);
    expect(countHan(null)).toBe(0);
  });

  it('英文与数字一个字都不算', () => {
    expect(countHan('read_file pom.xml 12ms')).toBe(0);
  });
});

describe('预算判定', () => {
  it('卡在线上算过', () => {
    expect(withinHan('未开始', 6)).toBe(true);
    expect(withinHan('正在进行中哦', 6)).toBe(true);
    expect(withinHan('正在进行中哦哦', 6)).toBe(false);
  });

  it('超了就砍到预算并加省略号', () => {
    expect(fitHan('一二三四五六七八', 6)).toBe('一二三四五六…');
    expect(fitHan('未开始', 6)).toBe('未开始');
  });

  it('砍完不留尾随标点', () => {
    expect(fitHan('一、二、三、四、五、六、七', 4)).toBe('一、二、三、四…');
    expect(fitHan('一，二，三，四，五', 2)).toBe('一，二…');
  });
});

describe('禁用词（UI.md §5）', () => {
  it('四个词一个都不许出现', () => {
    expect(findBanned('点击这里开始')).toEqual(['点击这里']);
    expect(findBanned('您可以试试')).toEqual(['您可以']);
    expect(findBanned('请注意保存')).toEqual(['请注意']);
    expect(findBanned('温馨提示一下')).toEqual(['温馨提示']);
    expect(hasBanned('一切正常')).toBe(false);
  });
});

describe('一行化与裁剪', () => {
  it('空白折叠', () => {
    expect(oneLine('  a \n b\tc  ')).toBe('a b c');
  });

  it('超长留省略号', () => {
    expect(clip('abcdefghij', 5)).toBe('abcd…');
    expect(clip('abc', 5)).toBe('abc');
  });
});

describe('耗时与时钟', () => {
  it('毫秒级', () => {
    expect(millisText(12)).toBe('12ms');
    expect(millisText(999)).toBe('999ms');
  });

  it('秒级与分钟级', () => {
    expect(millisText(1000)).toBe('1.0s');
    expect(millisText(3200)).toBe('3.2s');
    expect(millisText(12_000)).toBe('12s');
    expect(millisText(75_000)).toBe('1m15s');
  });

  it('没给耗时就不画', () => {
    expect(millisText(null)).toBe('');
    expect(millisText(undefined)).toBe('');
  });

  it('时间戳只留时刻', () => {
    expect(clockText('2026-10-16T15:38:44')).toBe('15:38:44');
    expect(clockText('')).toBe('');
  });
});

describe('路径末段', () => {
  it('Windows 与 POSIX 都认', () => {
    expect(baseName('E:\\work\\specflow-v2')).toBe('specflow-v2');
    expect(baseName('/home/me/specflow')).toBe('specflow');
    expect(baseName('')).toBe('');
  });
});
