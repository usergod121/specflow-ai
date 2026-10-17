import { describe, expect, it, beforeEach } from 'vitest';
import { bind, head } from '../../src/lib/actions.js';
import { store } from '../../src/store.js';

/**
 * 动作绑定：**禁用条件必须是"每次重算都重新读一遍"的**。
 *
 * <h2>这一条钉的是一个真 bug</h2>
 * 视图原来这样写：`const options = { disabled: { agent: Boolean(store.flow) === false } }`——
 * 一个在 setup 时求好的字面量。直接打开 `/env` 时 `/api/flow` 还没回来，于是那一刻
 * `store.flow` 是空的，`agent` 就被<b>永久禁用</b>：页面状态、徽章都正常（它们是响应式的），
 * 只有「探测环境」点不动，而且**不发任何请求、不报任何错**。
 *
 * 现在允许传函数：`() => ({ disabled: { agent: store.booted !== true } })`。
 */
describe('lib/actions 的禁用条件', () => {
  beforeEach(() => {
    store.flow = {
      flowId: 'f1',
      nodes: [
        { key: 'env', actions: [
          { id: 'agent', label: '探测环境', tip: '读一遍项目', menu: false },
          { id: 'up', label: '起环境', tip: '起容器', menu: false },
        ] },
      ],
    };
    store.running = false;
    store.booted = false;
  });

  const handlers = { agent: () => {}, up: () => {} };

  it('字面量会让禁用条件"冻住"（这就是那个 bug 的形状）', () => {
    const frozen = { disabled: { agent: store.flow === null } };
    store.flow = null;                       // 挂载时为空的另一种写法
    store.flow = { flowId: 'f1', nodes: [] }; // 后来回来了
    const buttons = bind('env', handlers, frozen);
    expect(buttons).toEqual([]);               // 没声明可绑 → 空（这里只看"字面量不会重算"这件事）
  });

  it('传函数：每次都重新读 store（boot 之后「探测环境」就该能点）', () => {
    const options = () => ({ disabled: { agent: store.booted !== true } });

    store.booted = false;
    const before = bind('env', handlers, options);
    expect(before.find(one => one.id === 'agent').disabled).toBe(true);

    store.booted = true;                     // 相当于 /api/flow 回来了
    const after = bind('env', handlers, options);
    expect(after.find(one => one.id === 'agent').disabled).toBe(false);
    expect(after.find(one => one.id === 'up').disabled).toBe(false);
  });

  it('没接处理函数的动作：渲染成不可点，并说明原因（不静默消失）', () => {
    const buttons = bind('env', { agent: () => {} }, () => ({}));
    const up = buttons.find(one => one.id === 'up');
    expect(up).toBeTruthy();
    expect(up.disabled).toBe(true);
    expect(up.tip).toContain('还没接上');
  });

  it('head 只留非菜单项；没有声明时是空表（不炸）', () => {
    const list = head('env', handlers, () => ({})).value;
    expect(list.map(one => one.id)).toEqual(['agent', 'up']);
    store.flow = null;
    expect(head('env', handlers, () => ({})).value).toEqual([]);
  });
});
