import { beforeEach, describe, expect, it, vi } from 'vitest';
import { statusWord, toneOf, pillTone, flag } from '../../src/lib/status.js';
import { clock, join, lines, oneLine, seconds } from '../../src/lib/format.js';

/**
 * 纯函数：状态词与格式化。
 *
 * 这里钉的两件事都是上一版真出过的问题：
 *  1. `READY` 不许再说成「已就绪」——在 ① 上会被读成"环境已经起好了"；
 *  2. 多行字符串 ↔ 行数组必须能来回转（否则界面上的 textarea 存一次就被清空）。
 */
describe('状态词', () => {
  it('三态的词一眼分得开', () => {
    expect(statusWord('LOCKED')).toBe('未开始');
    expect(statusWord('READY')).toBe('待完成');
    expect(statusWord('DONE')).toBe('已完成');
    expect(statusWord('')).toBe('未开始');
  });

  it('在跑时一律「进行中」，不管后端说它是什么', () => {
    expect(statusWord('READY', true)).toBe('进行中');
    expect(statusWord('LOCKED', true)).toBe('进行中');
  });

  it('圆点与 pill 的色调同一套语义', () => {
    expect(toneOf('DONE')).toBe('done');
    expect(toneOf('READY')).toBe('ready');
    expect(toneOf('LOCKED')).toBe('');
    expect(toneOf('READY', true)).toBe('run');
    expect(pillTone('READY', false, false)).toBe('ready');
    expect(pillTone('READY', false, true)).toBe('bad');
  });

  it('facts 里的真假是字符串', () => {
    expect(flag({ a: 'true' }, 'a')).toBe(true);
    expect(flag({ a: 'false' }, 'a')).toBe(false);
    expect(flag({}, 'a')).toBe(false);
  });
});

describe('格式化', () => {
  it('行数组与多行文本能来回转', () => {
    const text = 'src/A.java\nsrc/B.java\n';
    expect(lines(text)).toEqual(['src/A.java', 'src/B.java']);
    expect(lines(['a', ' b ', ''])).toEqual(['a', 'b']);
    expect(join(['a', 'b'])).toBe('a\nb');
    expect(join(lines(text))).toBe('src/A.java\nsrc/B.java');
  });

  it('秒与时钟', () => {
    // 10 秒以内保留一位小数（"1.2 秒"比"1 秒"诚实）；10 秒以上取整
    expect(seconds(9.4)).toBe('9.4 秒');
    expect(seconds(1.234)).toBe('1.2 秒');
    expect(seconds(12.6)).toBe('13 秒');
    expect(seconds(95)).toBe('1 分 35 秒');
    expect(seconds(-1)).toBe('');
    expect(clock('2026-10-17T22:26:24')).toBe('22:26:24');
    expect(clock('')).toBe('');
  });

  it('摘要只取第一行', () => {
    expect(oneLine('第一行\n第二行')).toBe('第一行');
    expect(oneLine('x'.repeat(300), 10)).toBe('xxxxxxxxxx…');
  });
});

// ---------- store：刷新纪律（这是这一版的地基） ----------

describe('store 的刷新纪律', () => {
  let store;
  let pushFailure;
  let act;
  let refresh;

  const flowBody = (running) => ({
    flowId: 'f1',
    running,
    stage: 'ok',
    nodes: [{ key: 'env', title: '环境', state: 'READY', blockedReason: '', facts: {}, actions: [] }],
  });

  beforeEach(async () => {
    vi.resetModules();
    globalThis.fetch = vi.fn(async (url) => {
      const path = String(url);
      if (path.includes('/api/flow')) {
        return new Response(JSON.stringify(flowBody(false)), { status: 200 });
      }
      return new Response(JSON.stringify({ ok: true }), { status: 200 });
    });
    const module = await import('../../src/store.js');
    store = module.store;
    pushFailure = module.pushFailure;
    act = module.act;
    refresh = module.refresh;
    store.failures = [];
  });

  it('refresh() 把 running 一起读回来（叫停按钮靠它出现）', async () => {
    globalThis.fetch = vi.fn(async () => new Response(JSON.stringify(flowBody(true)), { status: 200 }));
    await refresh();
    expect(store.running).toBe(true);
    expect(store.flow.flowId).toBe('f1');
  });

  it('act() 成功之后会重新问一次流程（界面不许自己拼状态）', async () => {
    const before = globalThis.fetch.mock.calls.length;
    const done = await act('env:up', async () => ({ accepted: true }));
    expect(done.ok).toBe(true);
    const paths = globalThis.fetch.mock.calls.map((one) => String(one[0]));
    expect(paths.length).toBeGreaterThan(before);
    expect(paths.some((one) => one.includes('/api/flow'))).toBe(true);
  });

  it('act() 失败：留一条人话，且同样的不重复堆', async () => {
    const boom = async () => {
      const error = new Error('还有一段在跑（环境搭建 · env-agent）');
      error.status = 409;
      throw error;
    };
    await act('env:up', boom);
    await act('env:up', boom);
    expect(store.failures.filter((one) => one.scope === 'env:up')).toHaveLength(1);
    expect(store.failures[0].text).toContain('还有一段在跑');
    expect(store.failures[0].status).toBe(409);
  });

  it('409 之后会重新拉一次流程（于是叫停按钮会出现）', async () => {
    globalThis.fetch = vi.fn(async (url) => {
      if (String(url).includes('/api/flow')) {
        return new Response(JSON.stringify(flowBody(true)), { status: 200 });
      }
      return new Response(JSON.stringify({ error: '还有一段在跑' }), { status: 409 });
    });
    const boom = async () => {
      const error = new Error('还有一段在跑');
      error.status = 409;
      throw error;
    };
    await act('env:up', boom);
    expect(store.running).toBe(true);
  });
});
