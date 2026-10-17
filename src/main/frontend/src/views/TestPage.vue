<script setup>
/**
 * ⑤ 测试：清单逐条给结论 + 高危命令审批 + 断点 + 提交/接受/再优化。
 *
 * 这一页要做的一件事：**让人随时知道每条用例现在是什么状态、卡在哪一条**。
 */
import { computed, onMounted, ref } from 'vue';
import * as api from '../api.js';
import { act, say, store } from '../store.js';
import { useNode } from '../lib/useNode.js';
import PageHead from '../components/PageHead.vue';
import EventStream from '../components/EventStream.vue';

const key = 'test';
useNode(key);

const rows = ref([]);
const summary = ref('');
const approvals = ref([]);
const checkpoint = ref(null);
const pending = computed(() => rows.value.filter((one) => (one.status || 'PENDING') === 'PENDING').length);

async function load() {
  const done = await act(`${key}:view`, () => api.cases());
  if (done.ok) {
    rows.value = ((done.value && done.value.cases) || []).map((one) => ({ ...one }));
  }
  const approvalsView = await act(`${key}:approvals`, () => api.testApprovals());
  if (approvalsView.ok) {
    approvals.value = (approvalsView.value && approvalsView.value.approvals) || [];
  }
  const point = await act(`${key}:checkpoint`, () => api.testCheckpoint());
  if (point.ok) {
    checkpoint.value = point.value && point.value.present ? point.value : null;
  }
}

async function retry(id) {
  const done = await act(`${key}:retry:${id}`, () => api.testRetry(id));
  if (done.ok) {
    say(`重跑 ${id}`);
    setTimeout(load, 800);
  }
}

async function answer(id, allow) {
  const done = await act(`${key}:approve:${id}`, () => api.testApprove(id, allow));
  if (done.ok) {
    await load();
  }
}

const handlers = {
  run: async () => {
    const done = await act(`${key}:run`, () => api.testRun());
    if (done.ok) {
      say('开始在容器副本里逐条验');
    }
  },
  submit: async () => {
    const done = await act(`${key}:submit`, () => api.testSubmit(summary.value));
    if (done.ok) {
      say('已提交（这段总结会回喂 ④）');
    }
  },
  freeze: async () => {
    const done = await act(`${key}:freeze`, () => api.flowFreeze(key));
    if (done.ok) {
      say('⑤ 冻结：这一轮结束');
    }
  },
  accept: async () => {
    const done = await act(`${key}:accept`, () => api.testAccept());
    if (done.ok) {
      say('已接受并闭环');
    }
  },
  optimize: async () => {
    const done = await act(`${key}:optimize`, () => api.testOptimize());
    if (done.ok) {
      say('拿这段总结回喂 ④，再验一遍');
    }
  },
  resume: async () => {
    const done = await act(`${key}:resume`, () => api.testResume());
    if (done.ok) {
      say('从断点接着跑');
    }
  },
  cancel: () => act(`${key}:cancel`, () => api.cancel()),
};

const local = (id) => (id === 'submit' && !summary.value.trim() ? { disabled: true, tip: '先写一段总结' } : {});

onMounted(load);
</script>

<template>
  <div class="page">
    <PageHead :node-key="key" title="测试" :handlers="handlers" :local="local" />

    <p v-if="approvals.length" class="banner warn" data-failure>
      <span class="text">
        有 {{ approvals.length }} 条高危命令在等你点头（最多等 2 分钟，超时按拒）：
      </span>
    </p>
    <table v-if="approvals.length" class="list" style="margin-top: 8px">
      <thead><tr><th>命令</th><th style="width: 200px">为什么问</th><th style="width: 150px">回答</th></tr></thead>
      <tbody>
        <tr v-for="one in approvals" :key="one.id">
          <td class="mono wrap">{{ one.command }}</td>
          <td class="dim">{{ one.reason }}</td>
          <td class="actions-cell">
            <button type="button" class="btn tiny primary" @click="answer(one.id, true)">允许</button>
            <button type="button" class="btn tiny" @click="answer(one.id, false)">拒绝</button>
          </td>
        </tr>
      </tbody>
    </table>

    <section class="sec">
      <div class="sec-head">
        <h2>清单</h2>
        <span class="grow" />
        <span class="dim nums">{{ rows.length }} 条 · 还有 {{ pending }} 条没结论</span>
        <button type="button" class="btn ghost tiny" @click="load">刷新</button>
      </div>

      <p v-if="!rows.length" class="empty"><b>清单是空的。</b>回 ③ 生成或写几条，冻结之后这里才有东西可验。</p>

      <table v-else class="list">
        <thead>
          <tr><th style="width: 86px">id</th><th>这一条验什么</th><th style="width: 170px">期望</th>
              <th style="width: 78px">结论</th><th>证据</th><th style="width: 54px" /></tr>
        </thead>
        <tbody>
          <tr v-for="one in rows" :key="one.id">
            <td class="mono">{{ one.id }}</td>
            <td>{{ one.what }}</td>
            <td class="dim">{{ one.expect }}</td>
            <td><span class="state" :data-s="one.status || 'PENDING'">{{ one.status || 'PENDING' }}</span></td>
            <td class="dim wrap">{{ one.evidence || '' }}</td>
            <td class="actions-cell">
              <button type="button" class="btn ghost tiny" title="只重跑这一条"
                      :aria-busy="store.busy[`${key}:retry:${one.id}`] ? 'true' : 'false'"
                      @click="retry(one.id)">重试</button>
            </td>
          </tr>
        </tbody>
      </table>
    </section>

    <section class="sec">
      <div class="sec-head">
        <h2>总结</h2>
        <span class="grow" />
        <span class="dim">提交时附上；这段话会回喂给 ④</span>
      </div>
      <textarea v-model="summary" rows="4" spellcheck="false" aria-label="总结"
                placeholder="现在还有什么不对：哪几条失败、卡在哪一步、下一步该改哪里" />
    </section>

    <details class="fold" :open="Boolean(checkpoint)">
      <summary>断点{{ checkpoint ? '：' + (checkpoint.status || '') : '（没有）' }}</summary>
      <div class="body">
        <p v-if="!checkpoint" class="dim">没有未完成的断点。</p>
        <template v-else>
          <p class="dim mono">{{ checkpoint.summary || '' }}</p>
          <p class="dim">下一步：{{ checkpoint.next || '—' }} · 已写下 {{ checkpoint.doneCases || 0 }} 条结论</p>
        </template>
      </div>
    </details>

    <section class="sec">
      <div class="sec-head"><h2>留档</h2></div>
      <EventStream :node-key="key" :limit="120" />
    </section>
  </div>
</template>
