<script setup>
// ② 探针：需求（必填）+ 设计（可选）→ 产物四样（UI.md §3）。
import { computed, ref, watch } from 'vue';
import * as api from '../api.js';
import { store, act, runAction, freezeNode, say } from '../store.js';
import NodeShell from '../components/NodeShell.vue';
import Fail from '../components/Fail.vue';
import Empty from '../components/Empty.vue';

const node = 'probe';
const requirement = ref('');
const design = ref('');
const saved = ref({ requirement: '', design: '' });
const art = ref({ files: [], changes: [], context: '', prompt: '' });
const loaded = ref(false);

async function loadView() {
  const found = await act(node, 'view', () => api.probeView());
  if (!found.ok) {
    return;
  }
  const view = found.value || {};
  requirement.value = String(view.requirement || '');
  design.value = String(view.design || '');
  saved.value = { requirement: requirement.value, design: design.value };
  art.value = {
    files: view.files || [],
    changes: view.changes || [],
    context: String(view.context || ''),
    prompt: String(view.prompt || ''),
  };
  loaded.value = true;
}

loadView();
watch(() => store.settle, loadView);

const dirty = computed(() => loaded.value
  && (requirement.value !== saved.value.requirement || design.value !== saved.value.design));
const running = computed(() => store.node === node && store.running === true);

async function brief() {
  const done = await act(node, 'brief', () => api.probeBrief(requirement.value, design.value));
  if (done.ok) {
    saved.value = { requirement: requirement.value, design: design.value };
    say('已存下');
  }
}

async function run() {
  const done = await runAction(node, 'agent', api.probeAgent);
  if (done.ok) {
    await loadView();
  }
}

async function saveArt() {
  const done = await act(node, 'save', () => api.probeSave(
    art.value.files, art.value.changes, art.value.context, art.value.prompt,
  ));
  if (done.ok) {
    say('已保存');
  }
}

async function freeze() {
  const done = await freezeNode(node);
  if (done.ok) {
    await loadView();
  }
}

async function stop() {
  const done = await act(node, 'cancel', api.cancel);
  if (done.ok) {
    say('已叫停');
  }
}

function lines(list) {
  return (list || []).map(one => (typeof one === 'string' ? one : JSON.stringify(one))).join('\n');
}

function readBack(text) {
  return String(text || '').split('\n').map(one => one.trim()).filter(Boolean);
}

const actions = computed(() => [
  { id: 'agent', label: '跑探针', primary: true, tip: '让它只读地读项目，交出四样产物', on: run },
  { id: 'freeze', label: '冻结', tip: '冻结之后 ③ 才可动', on: freeze },
]);
const menu = computed(() => [
  { id: 'brief', label: '存需求', tip: '把需求与设计写回项目文件', disabled: !dirty.value, on: brief },
  { id: 'save', label: '保存产物', tip: '人改过的四样产物存回去', on: saveArt },
  { id: 'cancel', label: '叫停', danger: true, tip: '让它停下来', hidden: !running.value, on: stop },
]);
</script>

<template>
  <NodeShell :node="node" title="上下文探针" :actions="actions" :menu="menu" :facts="['probeDone', 'frozen']"
             empty="还没跑" empty-tip="先写需求，再点「跑探针」；它会只读地把项目读一遍。">
    <Fail scope="probe" action="agent" :retry="run" />
    <Fail scope="probe" action="freeze" :retry="freeze" />

    <section class="sec">
      <h2>需求<span class="grow"></span>
        <button type="button" class="btn tiny" :data-loading="store.busy['probe:brief'] ? 'true' : 'false'"
                :disabled="!dirty" :title="dirty ? '写回项目文件' : '没有改动'" @click="brief">保存</button>
      </h2>
      <textarea v-model="requirement" class="code" rows="6" spellcheck="false"
                placeholder="要改的那一处行为" title="必填：这一轮到底要改什么"></textarea>
      <p class="lbl">设计（可选）</p>
      <textarea v-model="design" class="code" rows="4" spellcheck="false"
                placeholder="打算怎么做" title="可选：写在合同里，探针会照着它读项目"></textarea>
      <Fail scope="probe" action="brief" :retry="brief" />
      <Fail scope="probe" action="view" :retry="loadView" />
    </section>

    <section class="sec">
      <h2>产物</h2>
      <div class="grid">
        <label class="field">
          <span class="lbl">要读的文件</span>
          <textarea :value="lines(art.files)" class="code" rows="5" spellcheck="false"
                    @input="art.files = readBack($event.target.value)"></textarea>
        </label>
        <label class="field">
          <span class="lbl">要改的文件</span>
          <textarea :value="lines(art.changes)" class="code" rows="5" spellcheck="false"
                    @input="art.changes = readBack($event.target.value)"></textarea>
        </label>
      </div>
      <label class="field">
        <span class="lbl">上下文</span>
        <textarea v-model="art.context" class="code" rows="5" spellcheck="false"></textarea>
      </label>
      <label class="field">
        <span class="lbl">prompt</span>
        <textarea v-model="art.prompt" class="code" rows="6" spellcheck="false"></textarea>
      </label>
      <Fail scope="probe" action="save" :retry="saveArt" />
      <Empty v-if="!art.prompt" text="还没产物" tip="跑一次探针，或者自己在这几格里写。" />
    </section>
  </NodeShell>
</template>
