/**
 * 五个节点页的「接线」：**哪一格必须有哪个入口**。
 *
 * <p>为什么要用源码扫描、而不是挂载组件断言：这套前端单测跑在 node 环境里，
 * 没装 @vue/test-utils，挂不起来。而这里要钉的东西恰好是**静态的**——
 * 「这一页有没有那个按钮」。真实撞到过的问题就是它：
 * ① 和 ④ 少写了一个「冻结」，于界面上永远走不到 DONE，
 * 下游永远解锁不了，用户只看到一句「前置「环境搭建」还没做完（现在 READY）」。
 *
 * <p>浏览器那一侧另有一层（`tests/smoke.mjs` 七个路由零报错），两层不重复：
 * 这里钉「有入口」，那里钉「页面不炸」。
 */
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';

const path = name => fileURLToPath(new URL(`../../src/views/${name}`, import.meta.url));
const source = name => readFileSync(path(name), 'utf8');

/** 这一页有没有某个动作入口（只看动作表里那一行，不认注释里的字样）。 */
function hasAction(text, id) {
  return new RegExp(`id:\\s*'${id}'`).test(text);
}

describe('每个节点页的入口', () => {
  // ① ② ③ ④ 都得能「冻结」：闸判的是「前置是不是 DONE」，没这个入口就走不到 DONE
  const mustFreeze = [
    ['EnvNode.vue', '① 环境'],
    ['ProbeNode.vue', '② 探针'],
    ['CasesNode.vue', '③ 用例'],
    ['DevNode.vue', '④ 开发'],
  ];

  it.each(mustFreeze)('%s（%s）有「冻结」入口', (file) => {
    const text = source(file);
    expect(hasAction(text, 'freeze'), `${file} 少了冻结动作：下游会被永远挡住`).toBe(true);
    expect(text).toMatch(/freezeNode/);
  });

  it('① 环境：探测环境 / 起环境 / 冻结 三件都在', () => {
    const text = source('EnvNode.vue');
    for (const id of ['agent', 'up', 'freeze']) {
      expect(hasAction(text, id), `① 少了 ${id}`).toBe(true);
    }
  });

  it('② 探针：探测（agent）+ 保存 + 冻结', () => {
    const text = source('ProbeNode.vue');
    for (const id of ['agent', 'save', 'freeze']) {
      expect(hasAction(text, id), `② 少了 ${id}`).toBe(true);
    }
  });

  it('③ 用例：让 AI 写用例（agent）+ 保存 + 冻结', () => {
    const text = source('CasesNode.vue');
    for (const id of ['agent', 'save', 'freeze']) {
      expect(hasAction(text, id), `③ 少了 ${id}`).toBe(true);
    }
  });

  it('④ 开发：跑开发 + 冻结 + 回滚', () => {
    const text = source('DevNode.vue');
    for (const id of ['run', 'freeze', 'rollback']) {
      expect(hasAction(text, id), `④ 少了 ${id}`).toBe(true);
    }
  });

  it('⑤ 测试：跑测试 + 提交 + 接受（收尾那两下）', () => {
    const text = source('TestNode.vue');
    for (const id of ['run', 'submit', 'accept', 'optimize']) {
      expect(hasAction(text, id), `⑤ 少了 ${id}`).toBe(true);
    }
  });

  it('五个页面都用同一个骨架（NodeShell），谁也不自己拼页头', () => {
    for (const file of ['EnvNode.vue', 'ProbeNode.vue', 'CasesNode.vue', 'DevNode.vue', 'TestNode.vue']) {
      expect(source(file), `${file} 没走共用骨架`).toMatch(/NodeShell/);
    }
  });
});
