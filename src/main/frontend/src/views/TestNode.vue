<script setup>
// ⑤ 测试：用例表（带结论与证据）+ 总结框 + 高危命令等人回答（UI.md §3、API.md §7）。
import { computed, ref, watch } from 'vue';
import * as api from '../api.js';
import { store, act, runAction, say } from '../store.js';
import { NODES } from '../lib/flow.js';
import NodeShell from '../components/NodeShell.vue';
import Fail from '../components/Fail.vue';
import Empty from '../components/Empty.vue';
import { head, menu as menuOf } from '../lib/actions.js';

const node = 'test';
const rows = ref([]);
const summary = ref('');
const approvals = ref([]);
const pending = ref(0);

async function load() {
  const found = await act(node, 'load', () => api.cases());
  if (found.ok) {
    const view = found.value || {};
    rows.value = view.cases || [];
    pending.value = Number(view.pending || 0);
  }
  const waiting = await act(node, 'approvals', api.testApprovals);
  approvals.value = (waiting.ok && waiting.value && waiting.value.approvals) || [];
}

load();
watch(() => store.settle, load);

async function run() {
  const done = await runAction(node, 'run', api.testRun);
  if (done.ok) {
    await load();
  }
}

async function submit() {
  const done = await act(node, 'submit', () => api.testSubmit(summary.value));
  if (done.ok) {
    say('已提交');
    await load();
  }
}

async function accept() {
  const done = await act(node, 'accept', api.testAccept);
  if (done.ok) {
    say('已完成');
    await load();
  }
}

async function optimize() {
  const done = await act(node, 'optimize', api.testOptimize);
  if (done.ok) {
    say('回喂一轮');
    await load();
  }
}

async function answer(one, allow) {
  const done = await act(node, 'answer', () => api.testApprove(one.id, allow));
  if (done.ok) {
    await load();
  }
}

const done = computed(() => rows.value.filter(one => String(one.status).toUpperCase() !== 'PENDING').length);
// 名字/顺序/进哪个按钮组由后端 flow/Actions.java 声明，这里只绑处理函数
const handlers = { run, submit, accept, optimize };
const actions = head(node, handlers);
const menu = menuOf(node, handlers);

function back() {
  return NODES[3].route;
}
</script>

<template>
  <NodeShell :node="node" title="测试" :actions="actions" :menu="menu"
             :facts="['testPassed', 'caseCount']" empty="还没跑" empty-tip="点「跑测试」，它会逐条验并写回证据。">
    <section v-if="approvals.length" class="sec">
      <h2>等人回答<span class="grow"></span><span class="warn num">{{ approvals.length }} 条</span></h2>
      <ul class="list">
        <li v-for="one in approvals" :key="one.id">
          <span class="wrap">
            <span class="mono">{{ one.command }}</span>
            <span class="dim"> · {{ one.reason }}</span>
          </span>
          <button type="button" class="btn tiny" title="让它跑" @click="answer(one, true)">放行</button>
          <button type="button" class="btn tiny danger" title="别跑这条" @click="answer(one, false)">拦下</button>
        </li>
      </ul>
      <Fail scope="test" action="answer" />
    </section>

    <section class="sec">
      <h2>结论<span class="grow"></span><span class="dim num">{{ done }} / {{ rows.length }}</span>
        <span v-if="pending" class="warn num" title="未定的条数，提交会被拦">· {{ pending }} 未定</span>
      </h2>
      <table v-if="rows.length" class="tbl">
        <thead>
          <tr>
            <th scope="col">做什么</th>
            <th scope="col">标签</th>
            <th scope="col">结论</th>
            <th scope="col">证据</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="one in rows" :key="one.id" :data-status="String(one.status || 'PENDING').toUpperCase()">
            <td>{{ one.what }}</td>
            <td>{{ (one.tags || []).join('、') }}</td>
            <td class="nowrap"><span aria-hidden="true">{{ String(one.status).toUpperCase() === 'PASS' ? '✓' : (String(one.status).toUpperCase() === 'PENDING' ? '●' : '✗') }}</span> {{ one.status }}</td>
            <td class="mono wrap">{{ one.evidence || '—' }}</td>
          </tr>
        </tbody>
      </table>
      <Empty v-else text="还没清单" tip="③ 里冻住的那份清单会显示在这儿。" />
      <Fail scope="test" action="load" :retry="load" />
      <Fail scope="test" action="run" :retry="run" />
    </section>

    <section class="sec">
      <h2>提交总结</h2>
      <textarea v-model="summary" class="code" rows="5" spellcheck="false"
                placeholder="这一轮改了什么、验到了什么" title="会落成 summary.md，并回喂给 ④"></textarea>
      <Fail scope="test" action="submit" :retry="submit" />
      <Fail scope="test" action="accept" :retry="accept" />
      <Fail scope="test" action="optimize" :retry="optimize" />
      <p class="row">
        <router-link class="btn tiny ghost" :to="back()" title="想再改一轮就回 ④">回开发</router-link>
      </p>
    </section>
  </NodeShell>
</template>
