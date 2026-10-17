<script setup>
/**
 * 工作台：五格一览 + 新开一条流程 + 跨流程一览。
 *
 * 每一行都是「哪一步 / 现在什么状态 / 为什么不能往下（后端原话）/ 去处理」——
 * 不判任何东西，只把 `/api/flow` 与 `/api/flows` 摊开。
 */
import { computed, onMounted, ref } from 'vue';
import { RouterLink, useRouter } from 'vue-router';
import * as api from '../api.js';
import { store, act, refresh } from '../store.js';
import { statusWord, toneOf } from '../lib/status.js';

const router = useRouter();
const list = ref([]);
const pick = computed(() => (store.flow && store.flow.nodes) || []);

async function load() {
  const done = await act('flows', () => api.flows(20));
  if (done.ok) {
    list.value = done.value.flows || [];
  }
}

async function createFlow() {
  const done = await act('flow:new', () => api.flowNew());
  if (done.ok) {
    await refresh();
    router.push('/env');
  }
}

onMounted(async () => {
  await refresh().catch(() => {});
  await load();
});
</script>

<template>
  <div class="page">
    <header class="head">
      <h1>流程</h1>
      <span class="grow" />
      <button type="button" class="btn primary" :aria-busy="store.busy['flow:new'] ? 'true' : 'false'"
              :disabled="store.busy['flow:new'] === true" title="新开一条流程：五格回到未开始"
              @click="createFlow">新开流程</button>
    </header>

    <p v-if="!store.open" class="empty" style="margin-top: 16px">
      <b>还没有打开项目。</b><RouterLink to="/">先挑一个项目</RouterLink>。
    </p>

    <section v-else class="sec">
      <div class="sec-head">
        <h2>当前流程</h2>
        <span class="grow" />
        <span class="dim mono">{{ store.flow && store.flow.flowId }}</span>
      </div>
      <table class="list">
        <thead>
          <tr><th style="width: 44px">#</th><th style="width: 130px">这一步</th><th style="width: 90px">状态</th>
              <th>为什么还不能往下</th><th style="width: 90px" /></tr>
        </thead>
        <tbody>
          <tr v-for="(one, at) in pick" :key="one.key">
            <td class="mono dim">{{ ['①', '②', '③', '④', '⑤'][at] }}</td>
            <td>{{ one.title }}</td>
            <td>
              <span class="pill" :data-tone="toneOf(one.state, store.running && store.node === one.key)">
                <i />{{ statusWord(one.state, store.running && store.node === one.key) }}
              </span>
            </td>
            <td class="dim">{{ one.blockedReason || '—' }}</td>
            <td class="actions-cell">
              <RouterLink :to="'/' + one.key" class="btn tiny">去处理</RouterLink>
            </td>
          </tr>
        </tbody>
      </table>
    </section>

    <section class="sec">
      <div class="sec-head">
        <h2>所有流程</h2>
        <span class="grow" />
        <button type="button" class="btn ghost tiny" @click="load">刷新</button>
      </div>
      <p v-if="!list.length" class="empty"><b>还没有别的流程。</b>上面的「新开流程」会开一条新的。</p>
      <table v-else class="list">
        <thead>
          <tr><th>流程</th><th style="width: 90px">阶段</th><th style="width: 90px">用例</th>
              <th style="width: 70px">闭环</th><th style="width: 170px">时间</th></tr>
        </thead>
        <tbody>
          <tr v-for="one in list" :key="one.id">
            <td class="mono">{{ one.id }}</td>
            <td>{{ one.stage || '—' }}</td>
            <td class="nums">{{ one.caseCount || 0 }}</td>
            <td>{{ one.done ? '✓' : '' }}</td>
            <td class="dim mono">{{ one.createdAt || '' }}</td>
          </tr>
        </tbody>
      </table>
    </section>
  </div>
</template>
