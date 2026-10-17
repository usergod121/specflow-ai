<script setup>
// ① 环境：env.yaml 与编译命令两个框 + 起容器 + 编译一次（UI.md §3）。
import { computed, ref, watch } from 'vue';
import * as api from '../api.js';
import { store, act, runAction, say, freezeNode } from '../store.js';
import NodeShell from '../components/NodeShell.vue';
import Fail from '../components/Fail.vue';
import Empty from '../components/Empty.vue';

const node = 'env';
const envYaml = ref('');
const compileCommand = ref('');
const loaded = ref(false);
const output = ref('');
const minutes = ref(30);
const minutesOpen = ref(false);

async function loadView() {
  const found = await act(node, 'view', () => api.envView());
  if (!found.ok) {
    return;
  }
  const view = found.value || {};
  envYaml.value = String(view.envYaml || '');
  compileCommand.value = String(view.compileCommand || '');
  // 已保存的那一份就是「脏不脏」的判据；只有点「保存」才在别处改写它。
  store.envSaved = { envYaml: envYaml.value, compileCommand: compileCommand.value };
  const settings = await api.envSettingsView().catch(() => null);
  if (settings && settings.pullTimeoutMinutes) {
    minutes.value = Number(settings.pullTimeoutMinutes);
  }
  loaded.value = true;
}

loadView();
watch(() => store.settle, loadView);

const dirty = computed(() => loaded.value && (envYaml.value !== (store.envSaved.envYaml || '')
  || compileCommand.value !== (store.envSaved.compileCommand || '')));
const running = computed(() => store.node === node && store.running === true);

async function save() {
  const done = await act(node, 'save', () => api.envSave(envYaml.value, compileCommand.value));
  if (done.ok) {
    store.envSaved = { envYaml: envYaml.value, compileCommand: compileCommand.value };
    say('已保存');
  }
}

async function agent() {
  const done = await runAction(node, 'agent', api.envAgent);
  if (done.ok) {
    await loadView();
  }
}

async function up() {
  const done = await runAction(node, 'up', api.envUp, accepted => {
    output.value += '# 起环境 ' + (accepted && accepted.seconds ? accepted.seconds + 's' : '') + '\n';
    return accepted;
  });
  if (done.ok) {
    const view = done.value || {};
    output.value += (view.ok ? '✓ ' : '✗ ') + (view.seconds || 0) + 's\n'
      + (view.containers || []).join('\n') + '\n';
  }
}

async function compile() {
  const done = await act(node, 'compile', api.envCompile);
  if (done.ok) {
    const view = done.value || {};
    output.value += '$ ' + (view.command || compileCommand.value) + '\n' + String(view.output || '') + '\n'
      + (view.ok ? '✓ 编译过了' : '✗ 编译没过') + ' ' + (view.seconds || 0) + 's\n';
  }
}

async function skip() {
  const done = await act(node, 'skip', api.envSkip);
  if (done.ok) {
    say('已跳过');
  }
}

/** 叫停：只在真跑着的时候出现在「⋯」里（PRODUCT.md：能中途叫停）。 */
async function stop() {
  const done = await act(node, 'cancel', api.cancel);
  if (done.ok) {
    say('已叫停');
  }
}

async function keepMinutes() {
  const done = await act(node, 'settings', () => api.envSettings(Number(minutes.value)));
  if (done.ok) {
    minutesOpen.value = false;
  }
}

/** 冻结：① 这一格的收尾。没有它，② 永远解锁不了（闸判的是「前置是不是 DONE」）。 */
async function freeze() {
  const done = await freezeNode(node);
  if (done.ok) {
    say('已冻结');
  }
}

const actions = computed(() => [
  {
    id: 'agent', label: '探测环境', primary: true, tip: '只读地读一遍项目，交回 env.yaml 草稿',
    disabled: Boolean(store.flow) === false, on: agent,
  },
  { id: 'up', label: '起环境', tip: '拉镜像 + init + reset，长任务', on: up },
  { id: 'freeze', label: '冻结', tip: '做完了，冻住：冻住之后 ② 才可动', on: freeze },
]);
const menu = computed(() => [
  { id: 'compile', label: '编译', tip: '跑一次编译命令，秒级', on: compile },
  { id: 'clear', label: '清空输出', tip: '只清屏幕上的输出，不动文件', on: () => { output.value = ''; } },
  {
    id: 'timeout', label: '改超时', tip: '拉镜像的超时（分钟）',
    on: async () => { minutesOpen.value = true; },
  },
  { id: 'skip', label: '跳过这步', danger: true, tip: '声明这个项目不需要容器环境', on: skip },
  { id: 'cancel', label: '叫停', danger: true, tip: '让它停下来', hidden: !running.value, on: stop },
]);
</script>

<template>
  <NodeShell :node="node" title="环境搭建" :actions="actions" :menu="menu" :facts="['envReady', 'compilePassed']"
             empty="还没跑" empty-tip="点「探测环境」让它先读一遍项目，或者自己把下面两个框填好。">
    <!-- 页头那三枚动作的失败落点（它们没有"下面"，所以贴在这一页的最上面） -->
    <Fail scope="env" action="agent" :retry="agent" />
    <Fail scope="env" action="up" :retry="up" />
    <Fail scope="env" action="compile" :retry="compile" />
    <Fail scope="env" action="skip" :retry="skip" />

    <section class="sec">
      <h2>环境声明<span class="grow"></span>
        <button type="button" class="btn tiny" :data-loading="store.busy['env:save'] ? 'true' : 'false'"
                :disabled="!dirty" :title="dirty ? '写回项目里的 .specflow/env.yaml' : '没有改动'"
                @click="save">保存</button>
      </h2>
      <textarea v-model="envYaml" class="code" rows="12" spellcheck="false"
                placeholder="image: eclipse-temurin:17" title="写回项目里的 .specflow/env.yaml"></textarea>
      <Fail scope="env" action="save" :retry="save" />
    </section>

    <section class="sec">
      <h2>编译命令</h2>
      <textarea v-model="compileCommand" class="code" rows="3" spellcheck="false"
                placeholder="mvn -q -DskipTests package" title="一次编译要跑的那条命令"></textarea>
      <Fail scope="env" action="view" :retry="loadView" />
      <div v-if="minutesOpen" class="row">
        <label class="lbl" for="timeout">超时分钟</label>
        <input id="timeout" v-model="minutes" type="number" min="1" max="240" class="num" />
        <button type="button" class="btn tiny" :data-loading="store.busy['env:settings'] ? 'true' : 'false'" @click="keepMinutes">存下</button>
        <button type="button" class="btn tiny ghost" @click="minutesOpen = false">收起</button>
      </div>
      <Fail scope="env" action="settings" :retry="keepMinutes" />
    </section>

    <section class="sec">
      <h2>输出<span class="grow"></span><span class="dim">看完即弃</span></h2>
      <pre v-if="output" class="code out">{{ output }}</pre>
      <Empty v-else text="还没输出" tip="「起环境」和「编译」的结果会贴在这儿，同时也会进归档。" />
    </section>
  </NodeShell>
</template>
