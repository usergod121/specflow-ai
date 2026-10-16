/** 界面能调的每条路由都必须在 API.md 那张表里（接口事实优先）。 */
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';

const api = readFileSync(fileURLToPath(new URL('../../src/api.js', import.meta.url)), 'utf8');
const doc = readFileSync('E:/specflow-v2/docs/API.md', 'utf8');

/** 从 api.js 里把所有字符串字面量的路由抠出来（含模板拼出来的那两条）。 */
function routesInCode() {
  const found = new Set();
  for (const hit of api.matchAll(/'(\/api[^']*)'/g)) {
    found.add(hit[1]);
  }
  // nodeEvents 是拼出来的：/api/node/<key>/events?from=
  if (api.includes("'/api/node/' + encodeURIComponent(key) + '/events?from='")) {
    found.add('/api/node/<key>/events?from=');
  }
  return [...found];
}

function routesInDoc() {
  const found = new Set();
  for (const hit of doc.matchAll(/(GET|POST|DELETE)\s+(\/api[^\s`]*)/g)) {
    found.add(hit[2].replace(/[）)]+$/, '').replace(/\?.*$/, ''));
  }
  return [...found];
}

const normal = path => path
  .replace(/<key>/, '{key}')
  .replace(/[）)]+$/, '')
  .replace(/\?.*$/, '');

describe('api.js 是唯一出口', () => {
  it('全部代码里只有 api.js 直接 fetch', () => {
    const files = ['src/store.js', 'src/App.vue', 'src/views/Welcome.vue', 'src/views/Workbench.vue',
      'src/views/EnvNode.vue', 'src/views/ProbeNode.vue', 'src/views/CasesNode.vue',
      'src/views/DevNode.vue', 'src/views/TestNode.vue', 'src/components/NodeShell.vue'];
    for (const one of files) {
      const text = readFileSync(fileURLToPath(new URL('../../' + one, import.meta.url)), 'utf8');
      expect(text.includes('fetch('), one + ' 直接 fetch 了').toBe(false);
    }
  });

  it('非 2xx 抛的是后端那句原文', () => {
    expect(api).toContain('parsed.error || parsed.message');
    expect(api).toContain('throw new ApiError');
    expect(api).toContain('连不上本地服务');
  });

  it('调到的每一条后端都有（拿 API.md 对账）', () => {
    const table = new Set(routesInDoc().map(normal));
    expect(table.size).toBeGreaterThan(30);
    // 拼路径的碎片（`'/api/node/'`）不算一条路由
    const missing = routesInCode()
      .map(normal)
      .filter(one => /\/[a-z-]+$/.test(one) && !table.has(one));
    expect(missing).toEqual([]);
  });

  it('事件轮询那一条的形状就是文档写的那样', () => {
    expect(api).toContain("get('/api/node/' + encodeURIComponent(key) + '/events?from=' + Number(from || 0))");
  });

  it('七个页面的动作都有出口', () => {
    for (const name of ['envAgent', 'envUp', 'envCompile', 'envSkip', 'probeAgent', 'probeSave',
      'casesAgent', 'casesSave', 'devRun', 'devRollback', 'testRun', 'testApprove', 'testSubmit',
      'testAccept', 'testOptimize', 'cancel', 'freeze', 'goto', 'newFlow', 'browse', 'recent']) {
      expect(api).toContain('export const ' + name);
    }
  });
});
