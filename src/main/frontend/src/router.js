import { createRouter, createWebHistory } from 'vue-router';
import Welcome from './views/Welcome.vue';
import Workbench from './views/Workbench.vue';
import EnvPage from './views/EnvPage.vue';
import ProbePage from './views/ProbePage.vue';
import CasesPage from './views/CasesPage.vue';
import DevPage from './views/DevPage.vue';
import TestPage from './views/TestPage.vue';

/**
 * 七个页面，没有第二层导航（docs/UI.md §3）。
 *
 * 用 history 模式（干净 URL：/env、/probe…）：后端有 SPA 回落（非 /api 的路径一律发 index.html），
 * 刷新任意一页都能直接起来。**别换成 hash**：那样地址会变成 /#/env，跟文档里写的"/env"对不上。
 */
export const routes = [
  { path: '/', name: 'welcome', component: Welcome, title: '打开项目' },
  { path: '/workbench', name: 'workbench', component: Workbench, title: '流程' },
  { path: '/env', name: 'env', component: EnvPage, title: '环境搭建', node: 'env' },
  { path: '/probe', name: 'probe', component: ProbePage, title: '上下文探针', node: 'probe' },
  { path: '/cases', name: 'cases', component: CasesPage, title: '验收用例', node: 'cases' },
  { path: '/dev', name: 'dev', component: DevPage, title: '开发', node: 'dev' },
  { path: '/test', name: 'test', component: TestPage, title: '测试', node: 'test' },
];

export const router = createRouter({
  history: createWebHistory(),
  routes: routes.map(({ path, name, component }) => ({ path, name, component })),
});
