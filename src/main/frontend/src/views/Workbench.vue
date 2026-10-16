<script setup>
// 工作台：当前流程五格速览 + 最近项目（UI.md §3）。
import { computed } from 'vue';
import * as api from '../api.js';
import { store, act, loadFlow, loadRecent, openAt } from '../store.js';
import { nodesOf } from '../lib/flow.js';
import { badgesOf, statusTextOf } from '../lib/facts.js';
import { baseName } from '../lib/text.js';
import Badge from '../components/Badge.vue';
import Empty from '../components/Empty.vue';
import Fail from '../components/Fail.vue';
import { useRouter } from 'vue-router';

const router = useRouter();
const rows = computed(() => nodesOf(store.flow));

function open(one) {
  router.push(one.route);
}

async function again() {
  const done = await act('flow', 'new', () => api.newFlow());
  if (done.ok) {
    await loadFlow();
    store.paging = {};
  }
}

async function openRecent(one) {
  const done = await openAt(one.path);
  if (done.ok) {
    await loadRecent();
  }
}
</script>

<template>
  <article class="page">
    <header class="page-head">
      <h1>流程</h1>
      <span v-if="store.flow && store.flow.flowId" class="sub mono">{{ store.flow.flowId }}</span>
      <span class="grow"></span>
      <button type="button" class="btn" :data-loading="store.busy['flow:new'] ? 'true' : 'false'" title="把五格清空，从头再走一遍" @click="again">新开流程</button>
    </header>
    <Fail scope="flow" action="new" :retry="again" />

    <ul v-if="rows.length" class="steps">
      <li v-for="one in rows" :key="one.key">
        <button type="button" class="step" :title="one.blockedReason || '点开这一格'" @click="open(one)">
          <span class="step-name">{{ one.seq }}{{ one.name }}</span>
          <span class="step-state">{{ statusTextOf(one.state, false, one.facts) }}</span>
          <span class="grow"></span>
          <Badge v-for="badge in badgesOf(one.facts)" :key="badge.key" :text="badge.text" :tone="badge.tone" :mark="badge.mark" />
        </button>
        <p v-if="one.blockedReason" class="gate"><span class="gate-mark" aria-hidden="true">!</span>{{ one.blockedReason }}</p>
      </li>
    </ul>
    <Empty v-else text="还没流程" tip="先在欢迎页打开一个项目，这里就会出现五格。" />

    <section class="sec">
      <h2>最近项目</h2>
      <ul v-if="store.recent.length" class="list">
        <li v-for="one in store.recent" :key="one.path">
          <button type="button" class="list-main" :title="one.path" @click="openRecent(one)">
            <span class="name">{{ one.name || baseName(one.path) }}</span>
            <span class="where mono">{{ one.path }}</span>
          </button>
        </li>
      </ul>
      <Empty v-else text="还没记录" />
      <Fail scope="welcome" action="open" :retry="() => openRecent({ path: store.root })" />
    </section>
  </article>
</template>
