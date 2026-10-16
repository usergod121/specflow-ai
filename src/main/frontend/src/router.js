import { createRouter, createWebHistory } from 'vue-router';
import { PAGES } from './lib/pages.js';
import Welcome from './views/Welcome.vue';
import Workbench from './views/Workbench.vue';
import EnvNode from './views/EnvNode.vue';
import ProbeNode from './views/ProbeNode.vue';
import CasesNode from './views/CasesNode.vue';
import DevNode from './views/DevNode.vue';
import TestNode from './views/TestNode.vue';

const views = { welcome: Welcome, workbench: Workbench, env: EnvNode, probe: ProbeNode, cases: CasesNode, dev: DevNode, test: TestNode };

// 七个页面，没有第二层导航（UI.md 开头）。
export const routes = PAGES.map(one => ({ ...one, component: views[one.name] }));

export const router = createRouter({
  history: createWebHistory(),
  routes,
});
