<script setup>
/**
 * ② 上下文探针：需求 + 设计 → 探针读项目 → 四栏产物（全部可手改）。
 *
 * 四栏都会进 ④：`files` → 「先读这几处」、`context` → 「为什么相关」、
 * `changes` → 写白名单、`prompt` → 「这一轮要做的事」。
 */
import { onMounted, ref } from 'vue';
import * as api from '../api.js';
import { act, say, waitIdle } from '../store.js';
import { useNode } from '../lib/useNode.js';
import { lines, join } from '../lib/format.js';
import PageHead from '../components/PageHead.vue';
import EventStream from '../components/EventStream.vue';

const key = 'probe';
useNode(key);

const requirement = ref('');
const design = ref('');
const files = ref([]);
const changes = ref([]);
const context = ref([]);
const prompt = ref('');
const dirty = ref(false);

async function load() {
  const done = await act(`${key}:view`, () => api.probeView());
  if (!done.ok) {
    return;
  }
  const one = done.value || {};
  requirement.value = String(one.requirement || '');
  design.value = String(one.design || '');
  files.value = lines(one.files);
  changes.value = lines(one.changes);
  context.value = lines(one.context);
  prompt.value = String(one.prompt || '');
  dirty.value = false;
}

async function saveBrief() {
  const done = await act(`${key}:brief`, () => api.probeBrief(requirement.value, design.value));
  if (done.ok) {
    dirty.value = false;
    say('需求与设计已保存');
  }
}

async function saveArtifacts() {
  const done = await act(`${key}:save`, () =>
    api.probeSave(files.value, changes.value, context.value, prompt.value));
  if (done.ok) {
    say('四栏产物已写回 probe.json');
    await load();
  }
}

const handlers = {
  // 跑探针之前**先把需求写进去**：后端读的是已保存的需求，而用户刚打的字还在框里。
  // 不这么做，点「跑探针」只会得到一句「需求还是空的」——用户看到的就是"点了没反应"。
  agent: async () => {
    if (!requirement.value.trim()) {
      return;
    }
    const saved = await act(`${key}:brief`, () => api.probeBrief(requirement.value, design.value));
    if (!saved.ok) {
      return;
    }
    dirty.value = false;
    const done = await act(`${key}:agent`, () => api.probeAgent());
    if (done.ok) {
      // 等它真跑完再读：后端 202 就返回，写盘发生在后台那段活里。
      // 固定等 800ms 会读到一份还没写的 probe.json（用户实报：要再点一次才刷出来）。
      if (await waitIdle()) {
        await load();
      }
    }
  },
  brief: saveBrief,
  save: saveArtifacts,
  freeze: async () => {
    const done = await act(`${key}:freeze`, () => api.flowFreeze(key));
    if (done.ok) {
      say('② 冻结 → ③ 解锁');
    }
  },
  cancel: () => act(`${key}:cancel`, () => api.cancel()),
};

/**
 * 局部禁用：**每次渲染重算**。
 * 「跑探针」在需求为空时直接灰掉并说明——探针照需求去读项目，没有需求它没法开工
 * （后端也会 400，但让人先看见原因，比让他点一下再读红字好）。
 */
const local = (id) => {
  if (id === 'agent' && !requirement.value.trim()) {
    return { disabled: true, tip: '先在下面「需求」框里写一句：探针照它去读项目' };
  }
  if (id === 'brief' && !dirty.value) {
    return { disabled: true, tip: '没有改动' };
  }
  return {};
};
const bump = () => {
  dirty.value = true;
};

onMounted(load);
</script>

<template>
  <div class="page">
    <PageHead :node-key="key" title="上下文探针" :handlers="handlers" :local="local" />

    <section class="sec">
      <div class="sec-head">
        <h2>需求</h2>
        <span class="grow" />
        <span class="dim">必填 · 探针照它判断该动哪些文件</span>
      </div>
      <textarea v-model="requirement" rows="4" spellcheck="false" aria-label="需求"
                placeholder="这个项目要改成什么样：一句话说清行为，别写空话" @input="bump" />
      <div class="field" style="margin-top: 12px">
        <label for="design">设计（可选）</label>
        <textarea id="design" v-model="design" rows="3" spellcheck="false"
                  placeholder="希望怎么改：动哪个类、什么思路" @input="bump" />
      </div>
      <p v-if="dirty" class="dim" style="margin-top: 6px">有未保存的改动——点页头「存需求」。</p>
    </section>

    <section class="sec">
      <div class="sec-head">
        <h2>产物</h2>
        <span class="grow" />
        <span class="dim">四栏都能改；四栏都会进 ④</span>
        <button type="button" class="btn tiny" @click="saveArtifacts">保存产物</button>
      </div>

      <div style="display: grid; gap: 14px; grid-template-columns: repeat(auto-fit, minmax(280px, 1fr))">
        <div class="field">
          <label for="files">要读的文件（一行一个）</label>
          <textarea id="files" :value="join(files)" rows="5" spellcheck="false"
                    @input="files = lines($event.target.value)" />
        </div>
        <div class="field">
          <label for="changes">要改 / 要新增的文件（写白名单，一行一个；新增的写完整相对路径）</label>
          <textarea id="changes" :value="join(changes)" rows="5" spellcheck="false"
                    @input="changes = lines($event.target.value)" />
        </div>
      </div>

      <div class="field" style="margin-top: 14px">
        <label for="context">上下文（一行一句"为什么这些地方相关"）</label>
        <textarea id="context" :value="join(context)" rows="4" spellcheck="false"
                  placeholder="InventoryStore.java 是唯一扣减处&#10;README 写了期望的返回值"
                  @input="context = lines($event.target.value)" />
      </div>

      <div class="field" style="margin-top: 14px">
        <label for="prompt">给开发的 prompt</label>
        <textarea id="prompt" v-model="prompt" rows="6" spellcheck="false"
                  placeholder="改什么、改成什么样、不许动什么" />
      </div>
    </section>

    <section class="sec">
      <div class="sec-head"><h2>留档</h2></div>
      <EventStream :node-key="key" />
    </section>
  </div>
</template>
