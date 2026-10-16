/** 五个节点（UI.md §3）——顺序**本身就是信息**（闸按顺序放行），所以侧栏带序号；
 *  页面正文里不再出现序号（DESIGN.md §6 禁的是装饰性的 01/02/03）。 */

export const NODES = [
  { key: 'env', seq: '①', name: '环境', route: '/env' },
  { key: 'probe', seq: '②', name: '探针', route: '/probe' },
  { key: 'cases', seq: '③', name: '用例', route: '/cases' },
  { key: 'dev', seq: '④', name: '开发', route: '/dev' },
  { key: 'test', seq: '⑤', name: '测试', route: '/test' },
];

export const HOME = { name: '工作台', route: '/' };

export function nodeByKey(key) {
  return NODES.find(one => one.key === key) || null;
}

export function nodesOf(flow) {
  const list = (flow && flow.nodes) || [];
  return NODES.map(def => {
    const found = list.find(one => one && one.key === def.key) || {};
    return { ...def, ...found, key: def.key, seq: def.seq, name: def.name, route: def.route };
  });
}

export function nodeOf(flow, key) {
  const list = (flow && flow.nodes) || [];
  const found = list.find(one => one && one.key === key);
  return found || { key, state: '', blockedReason: '', facts: {} };
}
