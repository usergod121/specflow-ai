<script setup>
/**
 * ③ 验收用例：一张表，人能增删改；「生成用例」交给模型写一版（不落盘），
 * 「保存」把它写进 cases.json，冻结即锁定。
 *
 * 一条用例 = 一个 id + 一句 what + 标签 + 期望。**不写"能通过"，写看得见的结果。**
 */
import { onMounted, ref } from 'vue';
import * as api from '../api.js';
import { act, node, say } from '../store.js';
import { useNode } from '../lib/useNode.js';
import PageHead from '../components/PageHead.vue';
import EventStream from '../components/EventStream.vue';

const key = 'cases';
useNode(key);

const rows = ref([]);
const missing = ref([]);

async function load() {
  const done = await act(`${key}:view`, () => api.cases());
  if (!done.ok) {
    return;
  }
  rows.value = ((done.value && done.value.cases) || []).map((one) => ({ ...one }));
  missing.value = (done.value && done.value.missingTags) || [];
}

async function generate() {
  const done = await act(`${key}:agent`, () => api.casesAgent());
  if (!done.ok) {
    return;
  }
  const list = (done.value && (done.value.cases || done.value.list)) || [];
  if (!list.length) {
    say('模型这一版没给出可用的用例');
    return;
  }
  rows.value = list.map((one) => ({ ...one }));
  say(`模型给了 ${rows.value.length} 条（还没落盘，改完点保存）`);
}

async function save() {
  const done = await act(`${key}:save`, () => api.casesSave(rows.value));
  if (done.ok) {
    await load();
    say('清单已写回 cases.json');
  }
}

function add() {
  rows.value = rows.value.concat([{ id: '', what: '', tags: [], expect: '', status: 'PENDING' }]);
}

function drop(at) {
  rows.value = rows.value.filter((one, index) => index !== at);
}

const handlers = {
  agent: generate,
  save,
  freeze: async () => {
    const done = await act(`${key}:freeze`, () => api.flowFreeze(key));
    if (done.ok) {
      say('③ 冻结 → ④ 解锁');
    }
  },
  cancel: () => act(`${key}:cancel`, () => api.cancel()),
};

const local = (id) => (id === 'save' && !rows.value.length ? { disabled: true, tip: '清单是空的' } : {});

onMounted(async () => {
  await load();
  void node(key);
});
</script>

<template>
  <div class="page">
    <PageHead :node-key="key" title="验收用例" :handlers="handlers" :local="local" />

    <section class="sec">
      <div class="sec-head">
        <h2>清单</h2>
        <span class="grow" />
        <span class="dim nums">{{ rows.length }} 条</span>
        <button type="button" class="btn ghost tiny" @click="add">加一条</button>
      </div>

      <p v-if="!rows.length" class="empty">
        <b>还没有用例。</b>点页头「生成用例」让模型依需求写一版，或者「加一条」自己写。
      </p>

      <table v-else class="list">
        <thead>
          <tr>
            <th style="width: 90px">id</th>
            <th>这一条验什么</th>
            <th style="width: 150px">标签</th>
            <th style="width: 170px">期望</th>
            <th style="width: 76px">结论</th>
            <th style="width: 40px" />
          </tr>
        </thead>
        <tbody>
          <tr v-for="(one, at) in rows" :key="at">
            <td><input v-model="one.id" type="text" class="mono" aria-label="id" style="min-width: 70px" /></td>
            <td>
              <textarea v-model="one.what" rows="2" aria-label="这一条验什么"
                        style="min-height: 44px; font-family: var(--font)" />
            </td>
            <td>
              <input :value="(one.tags || []).join(' ')" type="text" aria-label="标签（空格分隔）"
                     @input="one.tags = String($event.target.value).split(/\s+/).filter(Boolean)" />
            </td>
            <td><input v-model="one.expect" type="text" aria-label="期望" /></td>
            <td><span class="state" :data-s="one.status || 'PENDING'">{{ one.status || 'PENDING' }}</span></td>
            <td class="actions-cell">
              <button type="button" class="btn ghost tiny" title="删掉这一条" @click="drop(at)">删</button>
            </td>
          </tr>
        </tbody>
      </table>

      <p v-if="missing.length" class="banner warn" style="margin-top: 10px">
        <span class="text">这些标签缺了：{{ missing.join('、') }}——清单里每条都该有标签，⑤ 靠它挑。</span>
      </p>
    </section>

    <section class="sec">
      <div class="sec-head"><h2>留档</h2></div>
      <EventStream :node-key="key" />
    </section>
  </div>
</template>
