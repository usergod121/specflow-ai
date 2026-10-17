<script setup>
/**
 * ④ 开发：白名单 + 先读的 + 上下文 + prompt + 上一轮反馈，**全只读**。
 * 这一页的作用是让人核「② 交代的东西真的进 ④ 了」，所以他必须看得见那几栏。
 */
import { onMounted, ref } from 'vue';
import * as api from '../api.js';
import { act, say } from '../store.js';
import { useNode } from '../lib/useNode.js';
import PageHead from '../components/PageHead.vue';
import EventStream from '../components/EventStream.vue';

const key = 'dev';
useNode(key);

const view = ref({ whitelist: [], readable: [], context: [], prompt: '', feedback: '', compileCommand: '', compilePassed: false });

async function load() {
  const done = await act(`${key}:view`, () => api.devView());
  if (!done.ok) {
    return;
  }
  const one = done.value || {};
  view.value = {
    whitelist: one.whitelist || [],
    readable: one.readable || [],
    context: one.context || [],
    prompt: String(one.prompt || ''),
    feedback: String(one.feedback || ''),
    compileCommand: String(one.compileCommand || ''),
    compilePassed: one.compilePassed === true,
  };
}

const handlers = {
  run: async () => {
    const done = await act(`${key}:run`, () => api.devRun());
    if (done.ok) {
      say('开始改代码；编译没过会自动回喂');
    }
  },
  rollback: async () => {
    const done = await act(`${key}:rollback`, () => api.devRollback());
    if (done.ok) {
      say('已按快照退回原文');
      await load();
    }
  },
  freeze: async () => {
    const done = await act(`${key}:freeze`, () => api.flowFreeze(key));
    if (done.ok) {
      say('④ 冻结 → ⑤ 解锁');
    }
  },
  cancel: () => act(`${key}:cancel`, () => api.cancel()),
};

onMounted(load);
</script>

<template>
  <div class="page">
    <PageHead :node-key="key" title="开发"
              :subtitle="view.compilePassed ? '上一次编译通过' : ''" :handlers="handlers" />

    <section class="sec">
      <div class="sec-head">
        <h2>写白名单</h2>
        <span class="grow" />
        <span class="dim nums">{{ view.whitelist.length }} 个文件</span>
      </div>
      <p v-if="!view.whitelist.length" class="empty">
        <b>还没有白名单。</b>它来自 ② 的「要改的文件」——回 ② 挑一次就同步过来了。
      </p>
      <ul v-else class="mono" style="margin: 0; padding-left: 18px; font-size: var(--fs-sm); line-height: 1.8">
        <li v-for="one in view.whitelist" :key="one" class="wrap">{{ one }}</li>
      </ul>
    </section>

    <section class="sec">
      <div class="sec-head">
        <h2>先读的</h2>
        <span class="grow" />
        <span class="dim nums">{{ view.readable.length }} 个文件</span>
      </div>
      <p v-if="!view.readable.length" class="empty">② 的「开发能读的文件」会出现在这里。</p>
      <ul v-else class="mono" style="margin: 0; padding-left: 18px; font-size: var(--fs-sm); line-height: 1.8">
        <li v-for="one in view.readable" :key="one" class="wrap">{{ one }}</li>
      </ul>
    </section>

    <section class="sec">
      <div class="sec-head">
        <h2>上下文</h2>
        <span class="grow" />
        <span class="dim">② 写的「为什么这些地方相关」，原样进开发那一段话</span>
      </div>
      <p v-if="!view.context.length" class="empty">② 里写一句"为什么相关"，④ 就会读到。</p>
      <ul v-else style="margin: 0; padding-left: 18px; font-size: var(--fs-sm); line-height: 1.7">
        <li v-for="one in view.context" :key="one" class="wrap">{{ one }}</li>
      </ul>
    </section>

    <section class="sec">
      <div class="sec-head">
        <h2>prompt</h2>
        <span class="grow" />
        <span class="dim mono truncate">{{ view.compileCommand }}</span>
      </div>
      <p v-if="!view.prompt" class="empty"><b>还没有 prompt。</b>回 ② 跑一次探针，或者自己写一段。</p>
      <pre v-else class="mono" style="margin: 0; padding: 12px; border: 1px solid var(--line);
        border-radius: 10px; background: var(--surface); color: var(--ink-2);
        font-size: var(--fs-xs); white-space: pre-wrap; max-height: 280px; overflow: auto">{{ view.prompt }}</pre>
    </section>

    <section class="sec">
      <div class="sec-head"><h2>上一轮反馈</h2></div>
      <p v-if="!view.feedback" class="empty">⑤ 提交时写的那段总结会回喂到这里，作为这一轮的目标。</p>
      <pre v-else class="mono" style="margin: 0; padding: 12px; border: 1px solid var(--line);
        border-radius: 10px; background: var(--surface); color: var(--ink-2);
        font-size: var(--fs-xs); white-space: pre-wrap; max-height: 240px; overflow: auto">{{ view.feedback }}</pre>
    </section>

    <section class="sec">
      <div class="sec-head"><h2>留档</h2></div>
      <EventStream :node-key="key" />
    </section>
  </div>
</template>
