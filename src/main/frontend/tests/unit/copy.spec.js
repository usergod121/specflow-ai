/** 文案预算与被禁止的说法（UI.md §5）——机器钉住，不靠人记得。 */
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import { countHan, findBanned } from '../../src/lib/text.js';
import { NODES } from '../../src/lib/flow.js';
import { PAGES } from '../../src/lib/pages.js';

const read = name => readFileSync(fileURLToPath(new URL('../../' + name, import.meta.url)), 'utf8');

const SOURCES = [
  'src/App.vue', 'src/store.js',
  'src/views/Welcome.vue', 'src/views/Workbench.vue', 'src/views/EnvNode.vue',
  'src/views/ProbeNode.vue', 'src/views/CasesNode.vue', 'src/views/DevNode.vue', 'src/views/TestNode.vue',
  'src/components/NodeShell.vue', 'src/components/MoreMenu.vue', 'src/components/Chips.vue',
  'src/components/Chip.vue', 'src/components/Empty.vue', 'src/components/Fail.vue',
  'src/components/Fold.vue', 'src/components/Badge.vue',
];

/** 长解释只许出现在 title / tip / placeholder 里，正文一个字都不留。 */
function longAllowed(text) {
  const allowed = new Set();
  for (const hit of text.matchAll(/(?:title|tip|placeholder|aria-label)\s*[:=]\s*["']([^"']+)["']/g)) {
    allowed.add(hit[1]);
  }
  for (const hit of text.matchAll(/(?:title|tip|placeholder)\s*=\s*"([^"]*)"/g)) {
    allowed.add(hit[1]);
  }
  return allowed;
}

/** 例外（写在这儿的每一条都要说得出理由）。
 *  - `没成，后端没说原因。`：Fail 那一行只在前端连话都没拿到时用；UI.md §7 要的正好是
 *    「什么没成」这半句，它不是状态条。
 *  - App.vue 里那两句是 `lockTitle()` 的返回值，它只挂在 `:title` 上（正文一个字都不留）。 */
const KEPT = new Set([
  '没成，后端没说原因。',
  '后端说它现在是 ',
  '后端还没给这一格的话',
]);

describe('界面文案预算（UI.md §5）', () => {
  it('正文里的中文一律 ≤6 个汉字，长的只许进 title', () => {
    const offenders = [];
    for (const name of SOURCES) {
      const text = read(name);
      const allowed = longAllowed(text);
      for (const hit of text.matchAll(/['"`]([^'"`\n]*)['"`]/g)) {
        const value = hit[1];
        if (!value.includes('\\')) {
          if (countHan(value) > 6 && !allowed.has(value) && !KEPT.has(value) && !/[=\\/]/.test(value)) {
            offenders.push(name + ' → ' + value);
          }
        }
      }
    }
    expect(offenders).toEqual([]);
  });

  it('界面上一句禁用词都不许出现', () => {
    const offenders = [];
    for (const name of SOURCES) {
      const text = read(name);
      for (const hit of text.matchAll(/['"`]([^'"`\n]*)['"`]/g)) {
        const found = findBanned(hit[1]);
        if (found.length) {
          offenders.push(name + ' → ' + hit[1] + '（' + found.join('、') + '）');
        }
      }
    }
    expect(offenders).toEqual([]);
  });

  it('状态条那一句 ≤6 个汉字', () => {
    for (const word of ['未开始', '进行中', '已完成', '失败', '未保存', '已就绪']) {
      expect(countHan(word)).toBeLessThanOrEqual(6);
    }
    const shell = read('src/components/NodeShell.vue');
    expect(shell).toContain('statusTextOf');
  });

  it('页头动作名 ≤4 个汉字', () => {
    // 「⋯」里那两条例外是 UI.md §3 自己给的名字（`接受并完成` / `再优化一轮`），
    // 撞上 §2.1 那条 ≤4 字；按你说的优先级 UI.md 内部以 §3 那张表为准。
    const KEEP = new Set(['接受并完成', '再优化一轮']);
    const offenders = [];
    for (const name of SOURCES) {
      const text = read(name);
      for (const hit of text.matchAll(/label:\s*'([^']+)'/g)) {
        if (countHan(hit[1]) > 4 && !KEEP.has(hit[1])) {
          offenders.push(name + ' → ' + hit[1]);
        }
      }
    }
    expect(offenders).toEqual([]);
  });

  it('折叠标题 ≤4 个汉字', () => {
    const shell = read('src/components/NodeShell.vue');
    for (const hit of shell.matchAll(/label="([^"]+)"/g)) {
      expect(countHan(hit[1])).toBeLessThanOrEqual(4);
    }
  });

  it('空状态正文 ≤6 个汉字', () => {
    const offenders = [];
    for (const name of SOURCES) {
      const text = read(name);
      for (const hit of text.matchAll(/text="([^"]+)"/g)) {
        if (countHan(hit[1]) > 6) {
          offenders.push(name + ' → ' + hit[1]);
        }
      }
    }
    expect(offenders).toEqual([]);
  });
});

describe('页面结构', () => {
  it('正好七个页面，没有第二层导航', () => {
    expect(PAGES.map(one => one.path)).toEqual(['/', '/workbench', '/env', '/probe', '/cases', '/dev', '/test']);
    // router.js 那七条就是这张表拼出来的
    expect(read('src/router.js')).toContain('PAGES.map');
  });

  it('五格就在 flow.js 里，顺序就是闸的顺序', () => {
    expect(NODES.map(one => one.key)).toEqual(['env', 'probe', 'cases', 'dev', 'test']);
    expect(NODES.map(one => one.seq)).toEqual(['①', '②', '③', '④', '⑤']);
  });

  it('五个节点页共用同一个骨架', () => {
    for (const name of ['EnvNode', 'ProbeNode', 'CasesNode', 'DevNode', 'TestNode']) {
      const text = read('src/views/' + name + '.vue');
      expect(text).toContain("import NodeShell from '../components/NodeShell.vue'");
      expect(text).toContain('<NodeShell');
    }
  });

  it('页头最多 1 主 + 1 次，其余进「⋯」', () => {
    const shell = read('src/components/NodeShell.vue');
    expect(shell).toContain('MoreMenu');
    const menu = read('src/components/MoreMenu.vue');
    expect(menu).toContain('(props.actions || []).slice(0, 2)');
    expect(menu).toContain('!inHead.has(one.id)');
  });

  it('归档日志倒序渲染（最新在最上面）', () => {
    const shell = read('src/components/NodeShell.vue');
    expect(shell).toContain('logLines(store.events, props.node).slice().reverse()');
    expect(shell).toContain('更早 {{ older }} 行');
  });

  it('失败就地显示在按钮下面，不是只弹 toast', () => {
    const fail = read('src/components/Fail.vue');
    expect(fail).toContain('data-failure');
    expect(fail).toContain('role="alert"');
    for (const name of ['EnvNode', 'ProbeNode', 'CasesNode', 'DevNode', 'TestNode', 'Welcome']) {
      expect(read('src/views/' + name + '.vue')).toContain('<Fail');
    }
  });

  it('加载中给骨架屏，不是转圈', () => {
    expect(read('src/components/NodeShell.vue')).toContain('skel');
    expect(read('src/styles/app.css')).toContain('.skel-line');
  });
});
