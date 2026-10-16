/** 七个页面（UI.md §3）。这张表**不放 router.js 里**：router.js 一进来就建 history，
 *  在 node 里跑不了单测；这里只放纯数据。 */

export const PAGES = [
  { path: '/', name: 'welcome', title: '欢迎' },
  { path: '/workbench', name: 'workbench', title: '工作台' },
  { path: '/env', name: 'env', title: '环境搭建' },
  { path: '/probe', name: 'probe', title: '上下文探针' },
  { path: '/cases', name: 'cases', title: '验收用例' },
  { path: '/dev', name: 'dev', title: '开发' },
  { path: '/test', name: 'test', title: '测试' },
];
