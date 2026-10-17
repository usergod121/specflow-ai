/**
 * 五个节点页的「接线」：**动作声明在后端，界面只绑处理函数**。
 *
 * <h2>这一组测试的形状变了</h2>
 * 原来它扫视图文件里有没有 `id: 'freeze'` 那一行 —— 那是在"六个落点"里挑一个来查。
 * 现在动作只有一份声明（`flow/Actions.java`），所以检查变成<b>跨两端对账</b>：
 * <ol>
 *   <li>后端那一份声明<b>完整</b>：五格都有，该有的动作一个不少；</li>
 *   <li>每一格声明的动作，对应视图的 <code>handlers</code> <b>都接了</b>（漏接 → 那个按钮灰着）；</li>
 *   <li>视图里<b>不许再出现按钮文案</b>（label / tip 只能来自后端）。</li>
 * </ol>
 *
 * <p>为什么用源码扫描而不是挂载组件：这套前端单测跑在 node 环境，没装 @vue/test-utils；
 * 而这里要钉的恰好是<b>静态事实</b>（声明与处理函数的对应关系）。
 * 浏览器那一侧另有一层（`tests/smoke.mjs`：七个路由零控制台错误）——两层不重复。
 */
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';

const read = path => readFileSync(fileURLToPath(new URL('../../' + path, import.meta.url)), 'utf8');
/** 从仓库根读（本文件在 src/main/frontend/tests/unit/，往上五层才是仓库根）。 */
const readRepo = path => readFileSync(fileURLToPath(new URL('../../../../../' + path, import.meta.url)), 'utf8');

/** 后端那一份声明（唯一真相）。 */
const actionsJava = readRepo('src/main/java/com/specflow/flow/Actions.java');

/** 视图 → 它对应的节点 key。 */
const VIEWS = {
  env: 'src/views/EnvNode.vue',
  probe: 'src/views/ProbeNode.vue',
  cases: 'src/views/CasesNode.vue',
  dev: 'src/views/DevNode.vue',
  test: 'src/views/TestNode.vue',
};

/**
 * 从 Actions.java 里抠出"每格声明了哪些动作"。
 *
 * <p>做法：先找出所有 `Node.XXX, List.of(` 的位置，每一块的范围是"到下一个这样的位置
 * （或文件末尾的 `);`）"——不要用"到第一个 `),\n`"，那只会截到第一个动作。
 */
function declaredAll() {
  const marks = [...actionsJava.matchAll(/Node\.(ENV|PROBE|CASES|DEV|TEST), List\.of\(/g)]
    .map(hit => ({ key: hit[1].toLowerCase(), at: hit.index + hit[0].length }));
  const out = {};
  marks.forEach((mark, index) => {
    const until = index + 1 < marks.length
      ? marks[index + 1].at - marks[index + 1].key.length // 下一块的起点（略往前，够用）
      : actionsJava.length;
    const block = actionsJava.slice(mark.at, until);
    const found = new Set();
    for (const hit of block.matchAll(/ActionSpec\.\w+\("([a-z]+)"/g)) {
      found.add(hit[1]);
    }
    if (/ActionSpec\.freeze\(/.test(block)) {
      found.add('freeze');
    }
    out[mark.key] = [...found];
  });
  return out;
}

const DECLARED = declaredAll();

function declaredFor(nodeKey) {
  return DECLARED[nodeKey] || [];
}

/** 视图里 `const handlers = {…}` 那一段。 */
function handlersOf(file) {
  const hit = read(file).match(/const handlers = \{([^}]*)\}/);
  return hit ? hit[1] : '';
}

describe('节点动作：声明在后端，界面绑处理函数', () => {
  it('五格都有声明（漏一格就是「这一格没有按钮」）', () => {
    for (const key of Object.keys(VIEWS)) {
      expect(declaredFor(key).length, `后端 Actions.java 里 ${key} 没有动作`).toBeGreaterThanOrEqual(2);
    }
  });

  it('① ② ③ ④ 都有「冻结」（没有它，下游永远解锁不了）', () => {
    for (const key of ['env', 'probe', 'cases', 'dev']) {
      expect(declaredFor(key), `${key} 少了冻结`).toContain('freeze');
    }
  });

  it('⑤ 的收尾三件（跑测试 / 提交 / 接受 / 再优化）都在声明里', () => {
    expect(declaredFor('test')).toEqual(expect.arrayContaining(['run', 'submit', 'accept', 'optimize']));
  });

  it('声明里有的，每个视图的 handlers 都接了（漏接会灰着，不会消失）', () => {
    for (const [key, file] of Object.entries(VIEWS)) {
      const handlers = handlersOf(file);
      expect(handlers, `${file} 里没有 handlers 块`).not.toBe('');
      for (const id of declaredFor(key)) {
        expect(handlers, `${file} 的 handlers 里漏了 ${id}`).toContain(id);
      }
    }
  });

  it('视图里不许再写按钮文案（label / tip 只能来自后端那一份）', () => {
    for (const file of Object.values(VIEWS)) {
      const text = read(file);
      expect(text, `${file} 里还写着 label: '`).not.toMatch(/label:\s*'/);
      expect(text, `${file} 里还写着 tip: '`).not.toMatch(/tip:\s*'/);
    }
  });

  it('五个页面都用同一个骨架（NodeShell），谁也不自己拼页头', () => {
    for (const file of Object.values(VIEWS)) {
      expect(read(file), `${file} 没走共用骨架`).toMatch(/NodeShell/);
    }
  });

  it('视图都是「读声明再绑函数」这一种写法（不是各写一份数组）', () => {
    for (const file of Object.values(VIEWS)) {
      const text = read(file);
      expect(text, `${file} 没从 lib/actions.js 取动作`).toContain("'../lib/actions.js'");
      expect(text, `${file} 没用 head(...)`).toMatch(/const actions = head\(/);
      expect(text, `${file} 没用 menuOf(...)`).toMatch(/const menu = menuOf\(/);
    }
  });
});
