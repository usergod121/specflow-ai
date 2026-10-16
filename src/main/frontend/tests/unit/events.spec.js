import { describe, expect, it } from 'vitest';
import {
  argHeadline, callIdOf, chipFromEvent, chipMark, chipStatus, chipSummary, chipWord,
  isChipEvent, lastPayload, logLines, mergeById, mergeChip, mergeChips,
} from '../../src/lib/events.js';

const running = (id) => ({ id: 1, type: 'tool', callId: id, tool: 'read_file', args: '{"path":"pom.xml"}', status: 'running', at: '2026-10-16T15:38:44' });
const done = (id) => ({ id: 2, type: 'tool', callId: id, tool: 'read_file', args: '{"path":"pom.xml"}', status: 'ok', millis: 12, result: '一行摘要', resultFull: '全文', at: '2026-10-16T15:38:45' });

describe('认事件', () => {
  it('callId 两种写法都认', () => {
    expect(callIdOf({ callId: 'c7' })).toBe('c7');
    expect(callIdOf({ call_id: 9 })).toBe('9');
    expect(callIdOf({})).toBe('');
  });

  it('工具事件三档都认', () => {
    expect(isChipEvent({ callId: 'c1' })).toBe(true);
    expect(isChipEvent({ type: 'tool' })).toBe(true);
    expect(isChipEvent({ type: 'log', tool: 'bash' })).toBe(true);
    expect(isChipEvent({ type: 'log', text: '普通日志' })).toBe(false);
    expect(isChipEvent(null)).toBe(false);
  });

  it('状态归一，认不出时看 level', () => {
    expect(chipStatus({ status: 'running' })).toBe('running');
    expect(chipStatus({ status: 'started' })).toBe('running');
    expect(chipStatus({ status: 'error' })).toBe('error');
    expect(chipStatus({ status: 'done' })).toBe('ok');
    expect(chipStatus({ level: 'error' })).toBe('error');
    expect(chipStatus({})).toBe('ok');
  });
});

describe('参数摘要（chip 面上那一截）', () => {
  it('取第一个标量值当主角', () => {
    expect(argHeadline('{"path":"pom.xml","line":3}')).toBe('pom.xml');
    expect(argHeadline('{"cmd":"mvn test"}')).toBe('mvn test');
  });

  it('不是 JSON 就照原样裁', () => {
    expect(argHeadline('mvn -q test')).toBe('mvn -q test');
    expect(argHeadline('')).toBe('无参数');
  });

  it('数组与空对象也说得出话', () => {
    expect(argHeadline('[1,2,3]')).toBe('3 项');
    expect(argHeadline('[]')).toBe('空');
    expect(argHeadline('{}')).toBe('空');
  });
});

describe('一枚 chip', () => {
  it('跑着的时候没有耗时', () => {
    const one = chipFromEvent(running('c7'), 1);
    expect(one.status).toBe('running');
    expect(one.msText).toBe('');
    expect(one.arg).toBe('pom.xml');
  });

  it('跑完带耗时与结果全文', () => {
    const one = chipFromEvent(done('c7'), 1);
    expect(one.msText).toBe('12ms');
    expect(one.resultFull).toBe('全文');
    expect(one.key).toBe('c7');
  });

  it('没有 callId 时用序号当 key', () => {
    expect(chipFromEvent({ type: 'tool', tool: 'bash' }, 4).key).toBe('s4');
  });
});

describe('同一个 callId 的两条合成一枚', () => {
  it('先 running 后 ok：状态推进、耗时补上', () => {
    const one = mergeChip(chipFromEvent(running('c7'), 1), chipFromEvent(done('c7'), 2));
    expect(one.status).toBe('ok');
    expect(one.msText).toBe('12ms');
    expect(one.resultFull).toBe('全文');
  });

  it('终态之后再来 running 不许退回去', () => {
    const one = mergeChip(chipFromEvent(done('c7'), 1), chipFromEvent(running('c7'), 2));
    expect(one.status).toBe('ok');
    expect(one.msText).toBe('12ms');
  });

  it('乱序也一样：先 ok 后 running 仍然停在 ok', () => {
    const one = mergeChip(chipFromEvent(running('c7'), 2), chipFromEvent(done('c7'), 1));
    expect(one.status).toBe('ok');
  });

  it('参数原文取更全的那份', () => {
    const one = mergeChip(
      chipFromEvent({ callId: 'c1', tool: 'bash', status: 'running', args: '' }, 1),
      chipFromEvent({ callId: 'c1', tool: 'bash', status: 'ok', args: '{"cmd":"ls"}' }, 2),
    );
    expect(one.argsRaw).toBe('{"cmd":"ls"}');
    expect(one.arg).toBe('ls');
  });
});

describe('chip 流', () => {
  const events = [running('c1'), done('c1'), running('c2'), done('c2'), { id: 3, type: 'tool', callId: 'c3', tool: 'bash', status: 'error', millis: 3200 }];

  it('默认只画当前那一枚', () => {
    const view = mergeChips(events, { limit: 1 });
    expect(view.total).toBe(3);
    expect(view.chips.length).toBe(1);
    expect(view.chips[0].key).toBe('c3');
  });

  it('「更早 N 次」的数就是没画出来的那几枚', () => {
    const view = mergeChips(events, { limit: 1 });
    expect(view.hidden).toBe(2);
    expect(mergeChips(events, { limit: 3 }).hidden).toBe(0);
  });

  it('报错数只数终态是 error 的', () => {
    const view = mergeChips(events, { limit: 8 });
    expect(view.failed).toBe(1);
    expect(view.running).toBe(false);
  });

  it('上面那一行写「3 次调用 · 1 报错」', () => {
    expect(chipSummary(mergeChips(events, { limit: 8 }))).toBe('3 次调用 · 1 报错');
    expect(chipSummary(mergeChips([running('c1')], { limit: 8 }))).toBe('1 次调用');
    expect(chipSummary(mergeChips([], {}))).toBe('');
  });

  it('chip 停在它第一次出现的位置', () => {
    const view = mergeChips([running('c1'), done('c2'), done('c1')], { limit: 8 });
    expect(view.chips.map(one => one.key)).toEqual(['c1', 'c2']);
    expect(view.chips[0].status).toBe('ok');
  });

  it('只取本节点的事件', () => {
    const view = mergeChips([{ id: 1, node: 'env', type: 'tool', callId: 'a', status: 'ok' }, { id: 2, node: 'dev', type: 'tool', callId: 'b', status: 'ok' }], { node: 'env', limit: 8 });
    expect(view.total).toBe(1);
  });
});

describe('形状与文字（颜色不是唯一信号）', () => {
  it('跑着是点、跑完是对勾、失败是叉', () => {
    expect(chipMark('running')).toBe('●');
    expect(chipMark('ok')).toBe('✓');
    expect(chipMark('error')).toBe('✗');
  });

  it('每一档都有话说', () => {
    expect(chipWord('running')).toBe('进行中');
    expect(chipWord('ok')).toBe('已完成');
    expect(chipWord('error')).toBe('失败');
  });
});

describe('归档日志行', () => {
  it('工具事件不进日志（它们是 chip）', () => {
    const rows = logLines([running('c1'), { id: 5, type: 'log', text: '开工', level: 'info' }]);
    expect(rows.length).toBe(1);
    expect(rows[0].text).toBe('开工');
  });

  it('闸事件算 warn', () => {
    expect(logLines([{ id: 6, type: 'gate', text: '闸没过' }])[0].level).toBe('warn');
  });

  it('只取本节点', () => {
    expect(logLines([{ id: 1, node: 'env', text: 'a' }, { id: 2, node: 'dev', text: 'b' }], 'env').length).toBe(1);
  });
});

describe('增量合并', () => {
  it('同一个 id 不会被画两遍', () => {
    const rows = mergeById([{ id: 2, text: 'b' }], [{ id: 1, text: 'a' }, { id: 2, text: 'b2' }]);
    expect(rows.map(one => one.id)).toEqual([1, 2]);
    expect(rows[1].text).toBe('b2');
  });

  it('重放同一段结果不变', () => {
    const once = mergeById([], [{ id: 1, text: 'a' }, { id: 2, text: 'b' }]);
    expect(mergeById(once, [{ id: 1, text: 'a' }, { id: 2, text: 'b' }])).toEqual(once);
  });
});

describe('收场载荷（API.md §9）', () => {
  it('取最后一条 result 的 payload', () => {
    const events = [
      { id: 1, type: 'result', payload: { status: 'DONE', statusLabel: '已完成' } },
      { id: 2, type: 'log', text: 'x' },
      { id: 3, type: 'result', payload: { status: 'DONE', statusLabel: '又完成' } },
    ];
    expect(lastPayload(events).statusLabel).toBe('又完成');
    expect(lastPayload([])).toBe(null);
  });

  it('只看本节点的 result', () => {
    const events = [{ id: 1, node: 'env', type: 'result', payload: { a: 1 } }];
    expect(lastPayload(events, 'dev')).toBe(null);
  });
});
