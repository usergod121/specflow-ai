<script setup>
// ③ 用例：带标签的验收清单，人能增删改，冻结即锁定（UI.md §3）。
import { computed, ref, watch } from 'vue';
import * as api from '../api.js';
import { store, act, freezeNode, say, nodeView } from '../store.js';
import { isFrozen } from '../lib/facts.js';
import NodeShell from '../components/NodeShell.vue';
import Fail from '../components/Fail.vue';
import { head, menu as menuOf } from '../lib/actions.js';

const node = 'cases';
const rows = ref([]);
const pending = ref(0);
const missing = ref([]);
const loaded = ref(false);
// 「冻没冻」读后端落档的 fact，界面不自己推（UI.md §4）。
const frozen = computed(() => isFrozen(nodeView(node)));

const STATUS = ['PENDING', 'PASS', 'FAIL', 'BLOCKED'];

function toRow(one, index) {
  return {
    id: String(one.id || 'c' + (index + 1)),
    what: String(one.what || ''),
    tags: (one.tags || []).join('、'),
    expect: String(one.expect || ''),
    status: String(one.status || 'PENDING').toUpperCase(),
    evidence: String(one.evidence || ''),
  };
}

async function load() {
  const found = await act(node, 'load', () => api.cases());
  if (!found.ok) {
    return;
  }
  const view = found.value || {};
  rows.value = (view.cases || []).map(toRow);
  pending.value = Number(view.pending || 0);
  missing.value = view.missingTags || [];
  loaded.value = true;
}

load();
watch(() => store.settle, load);

function payload() {
  return rows.value.map(one => ({
    id: one.id,
    what: one.what,
    tags: String(one.tags || '').split(/[、,，\s]+/).filter(Boolean),
    expect: one.expect,
    status: one.status,
    evidence: one.evidence,
  }));
}

async function save() {
  const done = await act(node, 'save', () => api.casesSave(payload()));
  if (done.ok) {
    say('已保存');
    await load();
  }
}

async function generate() {
  const done = await act(node, 'agent', api.casesAgent);
  if (done.ok) {
    const made = (done.value && (done.value.cases || done.value)) || [];
    if (Array.isArray(made) && made.length) {
      rows.value = made.map(toRow);
      say('生成好了');
    }
  }
}

async function freeze() {
  const done = await freezeNode(node);
  if (done.ok) {
    say('已冻结');
  }
}

async function stop() {
  const done = await act(node, 'cancel', api.cancel);
  if (done.ok) {
    say('已叫停');
  }
}

function addRow() {
  rows.value = rows.value.concat([{
    id: 'n' + (rows.value.length + 1), what: '', tags: '', expect: '', status: 'PENDING', evidence: '',
  }]);
}

function drop(id) {
  rows.value = rows.value.filter(one => one.id !== id);
}

// 名字/顺序/进哪个按钮组由后端 flow/Actions.java 声明，这里只绑处理函数与局部禁用条件
const handlers = { save, agent: generate, freeze };
const options = { disabled: { save: !rows.value.length } };
const actions = head(node, handlers, options);
const menu = menuOf(node, handlers, options);
</script>

<template>
  <NodeShell :node="node" title="验收用例" :actions="actions" :menu="menu"
             :facts="['caseCount', 'frozen']" empty="还没跑" empty-tip="点「生成用例」出一版草稿，再逐条改。">
    <Fail scope="cases" action="freeze" :retry="freeze" />

    <section class="sec">
      <h2>清单<span class="grow"></span>
        <span class="dim num">{{ rows.length }} 条</span>
        <span v-if="pending" class="warn num" title="还没结论的条数，提交时会拦">· {{ pending }} 未定</span>
        <span v-if="missing.length" class="warn num" :title="'缺标签：' + missing.join('、')">· 缺标签</span>
      </h2>
      <p class="row">
        <button type="button" class="btn tiny" :disabled="frozen" :title="frozen ? '冻结之后不许再改' : '加一条空行'" @click="addRow">加一条</button>
      </p>
      <table v-if="rows.length" class="tbl">
        <thead>
          <tr>
            <th scope="col">做什么</th>
            <th scope="col">标签</th>
            <th scope="col">预期</th>
            <th scope="col">状态</th>
            <th scope="col">证据</th>
            <th scope="col"><span class="sr">操作</span></th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="one in rows" :key="one.id">
            <td><textarea v-model="one.what" rows="2" :disabled="frozen" title="这一条要验的行为"></textarea></td>
            <td><input v-model="one.tags" type="text" :disabled="frozen" title="标签用、或逗号隔开"></td>
            <td><textarea v-model="one.expect" rows="2" :disabled="frozen" title="怎样算过"></textarea></td>
            <td>
              <select v-model="one.status" :disabled="frozen">
                <option v-for="one in STATUS" :key="one" :value="one">{{ one }}</option>
              </select>
            </td>
            <td><input v-model="one.evidence" type="text" :disabled="frozen" title="跑出来的证据"></td>
            <td><button type="button" class="btn tiny ghost" :disabled="frozen" :title="'去掉 ' + one.id" @click="drop(one.id)">删</button></td>
          </tr>
        </tbody>
      </table>
      <p v-else class="empty" title="点「生成用例」或者「加一条」">还没用例</p>
      <Fail scope="cases" action="save" :retry="save" />
      <Fail scope="cases" action="agent" :retry="generate" />
      <Fail scope="cases" action="load" :retry="load" />
    </section>
  </NodeShell>
</template>
