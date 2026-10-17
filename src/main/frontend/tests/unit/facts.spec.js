import { describe, expect, it } from 'vitest';
import {
  badgesOf, factBool, factCount, factMark, factTone, factValue, isFrozen, noteOf, statusTextOf,
} from '../../src/lib/facts.js';

describe('facts 的值（API.md §2：扁平字符串表）', () => {
  it('认 true / false / 数字 / 短文本', () => {
    expect(factValue('true')).toBe('true');
    expect(factValue(3)).toBe('3');
    expect(factValue('mvn package')).toBe('mvn package');
    expect(factValue('')).toBe(null);
    expect(factValue(undefined)).toBe(null);
  });

  it('真假值只有两种读法', () => {
    expect(factBool('true')).toBe(true);
    expect(factBool('false')).toBe(false);
    expect(factBool(null)).toBe(null);
  });

  it('数字读得出，读不出就是 0', () => {
    expect(factCount('4')).toBe(4);
    expect(factCount('没有')).toBe(0);
  });
});

describe('徽章色调', () => {
  it('false / 0 / failed 都是红', () => {
    expect(factTone('false')).toBe('danger');
    expect(factTone('0')).toBe('danger');
    expect(factTone('failed')).toBe('danger');
  });

  it('true / 数字 / 文本都是绿', () => {
    expect(factTone('true')).toBe('ok');
    expect(factTone('3')).toBe('ok');
  });

  it('正在跑是蓝，没跑过是灰', () => {
    expect(factTone('running')).toBe('run');
    expect(factTone(undefined)).toBe('idle');
    expect(factTone('')).toBe('idle');
  });

  it('每一档都有形状', () => {
    expect(factMark('ok')).toBe('✓');
    expect(factMark('danger')).toBe('✗');
    expect(factMark('run')).toBe('●');
    expect(factMark('idle')).toBe('—');
  });
});

describe('下一步的徽章读 facts，不读历史', () => {
  it('后端说 envReady=true 就是绿，哪怕历史里有报错', () => {
    const rows = badgesOf({ envReady: 'true', compilePassed: 'false' });
    expect(rows.map(one => [one.key, one.tone])).toEqual([
      ['envReady', 'ok'],
      ['compilePassed', 'danger'],
    ]);
  });

  it('没给的 fact 不画（界面不补算）', () => {
    expect(badgesOf({ envReady: 'true' }, ['envReady', 'compilePassed']).length).toBe(1);
  });

  it('caseCount 0 是灰不是红', () => {
    expect(badgesOf({ caseCount: '0' })[0].tone).toBe('idle');
    expect(badgesOf({ caseCount: '0' })[0].text).toBe('用例 0');
  });

  it('给得出人话的标题', () => {
    expect(badgesOf({ envReady: 'true' })[0].text).toBe('环境 true');
  });
});

describe('状态一行（≤6 个汉字，六个词）', () => {
  it('后端状态映射成人话', () => {
    // READY 是"可以做、还没做"——**不能**说成「已就绪」（在 ① 上会被读成"环境已经好了"，
    // 用户看到的就是"亮着已就绪却还得去点起环境"）。
    expect(statusTextOf('READY')).toBe('待完成');
    expect(statusTextOf('DONE')).toBe('已完成');
    expect(statusTextOf('LOCKED')).toBe('未开始');
    expect(statusTextOf('')).toBe('未开始');
    expect(statusTextOf(null)).toBe('未开始');
  });

  it('在跑就是「进行中」，不管后端说它是不是 READY', () => {
    expect(statusTextOf('READY', true)).toBe('进行中');
    expect(statusTextOf('LOCKED', true)).toBe('进行中');
  });

  it('只有后端落了 failed=true 才说失败', () => {
    expect(statusTextOf('DONE', false, { failed: 'false' })).toBe('已完成');
    expect(statusTextOf('READY', false, { failed: 'true' })).toBe('失败');
  });

  it('六个词都在 6 个汉字以内', () => {
    for (const word of ['未开始', '进行中', '已完成', '失败', '未保存', '待完成']) {
      expect(word.length).toBeLessThanOrEqual(6);
    }
  });
});

describe('冻结与闸', () => {
  it('冻结只认后端的 facts.frozen', () => {
    expect(isFrozen({ facts: { frozen: 'true' } })).toBe(true);
    expect(isFrozen({ facts: { frozen: 'false' } })).toBe(false);
    expect(isFrozen(null)).toBe(false);
  });

  it('闸那句话原样照搬，空串就是不显示', () => {
    expect(noteOf({ blockedReason: '先冻结 ③' })).toBe('先冻结 ③');
    expect(noteOf({ blockedReason: '' })).toBe('');
    expect(noteOf({})).toBe('');
  });
});
