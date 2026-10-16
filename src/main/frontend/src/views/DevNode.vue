<script setup>
// ④ 开发：白名单 + prompt + 上一轮反馈（只读）（UI.md §3）。
import { ref, watch } from 'vue';
import * as api from '../api.js';
import { store, act, runAction, say } from '../store.js';
import NodeShell from '../components/NodeShell.vue';
import Fail from '../components/Fail.vue';
import Empty from '../components/Empty.vue';

const node = 'dev';
const view = ref({ whitelist: [], readable: [], prompt: '', feedback: '', compileCommand: '', compilePassed: false });
const loaded = ref(false);

async function load() {
  const found = await act(node, 'view', () => api.devView());
  if (!found.ok) {
    return;
  }
  const one = found.value || {};
  view.value = {
    whitelist: one.whitelist || [],
    readable: one.readable || [],
    prompt: String(one.prompt || ''),
    feedback: String(one.feedback || ''),
    compileCommand: String(one.compileCommand || ''),
    compilePassed: one.compilePassed === true,
  };
  loaded.value = true;
}

load();
watch(() => store.settle, load);

async function run() {
  const done = await runAction(node, 'run', api.devRun);
  if (done.ok) {
    await load();
  }
}

async function rollback() {
  const done = await act(node, 'rollback', api.devRollback);
  if (done.ok) {
    say('已退回');
    await load();
  }
}

const actions = [
  { id: 'run', label: '跑开发', primary: true, tip: '只在白名单里改真项目，每次交差编译一次', on: run },
];
const menu = [
  { id: 'rollback', label: '回滚改动', danger: true, tip: '按快照把真项目退回原文', on: rollback },
];
</script>

<template>
  <NodeShell :node="node" title="开发" :actions="actions" :menu="menu"
             :facts="['compilePassed', 'rounds', 'devDone']"
             empty="还没跑" empty-tip="先冻结 ③ 的清单，「跑开发」才有东西可照。">
    <section class="sec">
      <h2>白名单<span class="grow"></span><span class="dim num">{{ view.whitelist.length }} 个文件</span></h2>
      <ul v-if="view.whitelist.length" class="list dense mono">
        <li v-for="one in view.whitelist" :key="one"><span class="wrap">{{ one }}</span></li>
      </ul>
      <Empty v-else text="还没白名单" tip="② 探针里挑的「要改的文件」会变成这里白名单。" />
      <Fail scope="dev" action="view" :retry="load" />
    </section>

    <section class="sec">
      <h2>prompt</h2>
      <pre v-if="view.prompt" class="code">{{ view.prompt }}</pre>
      <Empty v-else text="还没 prompt" />
    </section>

    <section class="sec">
      <h2>上一轮反馈</h2>
      <pre v-if="view.feedback" class="code">{{ view.feedback }}</pre>
      <Empty v-else text="还没反馈" tip="⑤ 提交时写的总结会回喂到这里。" />
      <p v-if="view.compileCommand" class="lbl mono" title="每次交差要跑的那条命令">
        {{ view.compilePassed ? '✓' : '✗' }} {{ view.compileCommand }}
      </p>
      <Fail scope="dev" action="rollback" :retry="rollback" />
      <Fail scope="dev" action="run" :retry="run" />
    </section>
  </NodeShell>
</template>
