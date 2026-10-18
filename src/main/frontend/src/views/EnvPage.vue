<script setup>
/**
 * ① 环境：声明（env.yaml + 编译命令）→ 起容器 → 编译一次。
 *
 * 「探测环境」会**把读到的东西直接写下来**（不是只丢一份草稿回来）：
 * 用户对这四个字的预期就是"读一遍并写好声明"。
 */
import { computed, onMounted, ref } from 'vue';
import * as api from '../api.js';
import { store, act, node, say, waitIdle } from '../store.js';
import { useNode } from '../lib/useNode.js';
import { flag } from '../lib/status.js';
import PageHead from '../components/PageHead.vue';
import EventStream from '../components/EventStream.vue';
import { seconds } from '../lib/format.js';

const key = 'env';
useNode(key);

const envYaml = ref('');
const compile = ref('');
const minutes = ref(30);
const output = ref('');
const openTimeout = ref(false);
const loaded = ref(false);

const facts = computed(() => node(key).facts || {});
const containerOk = computed(() => flag(facts.value, 'containerBuild'));
const envReady = computed(() => flag(facts.value, 'envReady'));
/** 上一次能力校验的那句话：**刷新之后也要看得见**（以前它只活在这一次请求的局部变量里）。 */
const containerDetail = computed(() => String(facts.value.containerDetail || ''));
/** 两条编译的结论打架时要说一句（宿主机过了、容器里没过）。 */
const halfCompiled = computed(() => flag(facts.value, 'compilePassed') && !containerOk.value);
/** 现在是不是正在起环境/编译（同步长任务，刷新后也能看到）。 */
const liveStage = computed(() => (store.running ? store.stage : ''));

async function load() {
  const done = await act(`${key}:view`, () => api.envView());
  if (!done.ok) {
    return;
  }
  envYaml.value = String((done.value && done.value.envYaml) || '');
  compile.value = String((done.value && done.value.compileCommand) || '');
  loaded.value = true;
}

async function loadSettings() {
  const done = await act(`${key}:settings`, () => api.envSettings());
  if (done.ok && done.value) {
    minutes.value = Number(done.value.pullTimeoutMinutes) || 30;
  }
}

async function save() {
  const done = await act(`${key}:save`, () => api.envSave(envYaml.value, compile.value));
  if (done.ok) {
    say('已写回 .specflow/env.yaml 与编译命令');
    await load();
  }
}

async function saveTimeout() {
  const done = await act(`${key}:timeout`, () => api.envSetTimeout(minutes.value));
  if (done.ok) {
    say(`超时改成 ${minutes.value} 分钟`);
    openTimeout.value = false;
  }
}

/** 动作：id 与后端 flow/Actions.java 的声明一一对应。 */
const handlers = {
  agent: async () => {
    const done = await act(`${key}:agent`, () => api.envAgent());
    if (done.ok) {
      // 等它真跑完再读：env.yaml 是后台那段活写下去的
      if (await waitIdle()) {
        await load();
      }
    }
  },
  up: async () => {
    const done = await act(`${key}:up`, () => api.envUp());
    if (done.ok && done.value) {
      const ok = done.value.capability && done.value.capability.ok;
      output.value = [
        `容器：${(done.value.containers || []).join('、') || '（无）'}`,
        `能力校验：${ok ? '通过' : '没通过'}${done.value.capability ? ' · ' + done.value.capability.detail : ''}`,
      ].join('\n');
      say(ok ? '环境起来了，容器里也能构建' : '环境起来了，但容器里构建不起来（见下面）');
    }
  },
  compile: async () => {
    const done = await act(`${key}:compile`, () => api.envCompile());
    if (done.ok && done.value) {
      output.value = `$ ${done.value.command}\n${done.value.output || ''}`;
      say(done.value.ok ? `编译通过（${seconds(done.value.seconds)}）` : '编译没过，原始输出在下面');
    }
  },
  skip: async () => {
    const done = await act(`${key}:skip`, () => api.envSkip());
    if (done.ok) {
      say('已声明：这个项目不需要环境');
    }
  },
  freeze: async () => {
    const done = await act(`${key}:freeze`, () => api.flowFreeze(key));
    if (done.ok) {
      say('① 冻结 → ② 解锁');
    }
  },
  cancel: () => act(`${key}:cancel`, () => api.cancel()),
  clear: () => {
    output.value = '';
  },
  timeout: () => {
    openTimeout.value = !openTimeout.value;
  },
};

/** 局部禁用条件：**每次渲染重算**（写成字面量会被冻在挂载那一刻）。 */
const local = (id) => {
  if (id === 'compile' && !compile.value.trim()) {
    return { disabled: true, tip: '先填一条编译命令' };
  }
  return {};
};

onMounted(async () => {
  await load();
  await loadSettings();
});
</script>

<template>
  <div class="page">
    <PageHead :node-key="key" title="环境搭建"
              :subtitle="containerOk ? '容器里能构建' : (envReady ? '容器里构建不起来' : '')"
              :handlers="handlers" :local="local" />

    <section class="sec">
      <div class="sec-head">
        <h2>环境声明</h2>
        <span class="grow" />
        <span v-if="!envYaml" class="dim">空</span>
        <button type="button" class="btn tiny" :aria-busy="store.busy[`${key}:save`] ? 'true' : 'false'"
                title="写回项目里的 .specflow/env.yaml" @click="save">保存</button>
      </div>
      <div class="field">
        <textarea v-model="envYaml" rows="12" spellcheck="false" aria-label="env.yaml"
                  placeholder="image: maven:3.9-eclipse-temurin-17&#10;workdir: /work"></textarea>
      </div>
      <p class="dim" style="margin-top: 6px">
        镜像里最好自带构建工具（Java 用 <code class="mono">maven:3.9-eclipse-temurin-17</code>）——
        ① 的冻结要求<b>容器里也能构建</b>，不只是宿主机。
      </p>
    </section>

    <section class="sec">
      <div class="sec-head">
        <h2>编译命令</h2>
        <span class="grow" />
        <span class="dim">④ 每次交差也用它</span>
      </div>
      <input v-model="compile" type="text" spellcheck="false" aria-label="编译命令"
             placeholder="mvn -q -DskipTests compile" class="mono" />
      <p v-if="halfCompiled" class="banner warn" style="margin-top: 8px">
        <span class="text">宿主机过了、容器里没过：这条命令在宿主机上能跑，但容器镜像里没有构件工具。
          换一个自带它的 image，或者在 init 里装一次，再点「起环境」。</span>
      </p>
      <!-- 上一次能力校验的原话：刷新之后也留在页面上（以前它只活在这一次请求里，刷新就没了） -->
      <p v-if="containerDetail" class="dim" style="margin-top: 8px" title="上一次「起环境」的能力校验结论">
        上次校验：{{ containerDetail }}
      </p>
      <p v-if="liveStage" class="dim" style="margin-top: 8px" title="正在进行的步骤">进行中：{{ liveStage }}</p>
      <details class="fold" style="margin-top: 8px" :open="openTimeout" @toggle="openTimeout = $event.target.open">
        <summary>起环境愿意等多久（{{ minutes }} 分钟）</summary>
        <div class="body" style="display: flex; gap: 8px; align-items: flex-end; max-width: 420px">
          <div class="field" style="flex: 1 1 auto">
            <label for="minutes">分钟（1–240）</label>
            <input id="minutes" v-model.number="minutes" type="number" min="1" max="240" />
          </div>
          <button type="button" class="btn" @click="saveTimeout">存下</button>
        </div>
        <p class="dim" style="margin-top: 6px">拉镜像、容器里构建、⑤ 的单条命令，全按这一个数走。</p>
      </details>
    </section>

    <section v-if="output" class="sec">
      <div class="sec-head">
        <h2>输出</h2>
        <span class="grow" />
        <span class="dim">同时也会进下面的留档</span>
      </div>
      <pre class="mono" style="margin: 0; padding: 12px; max-height: 300px; overflow: auto;
        border: 1px solid var(--line); border-radius: var(--radius, 10px); background: var(--surface);
        font-size: var(--fs-xs); color: var(--ink-2); white-space: pre-wrap">{{ output }}</pre>
    </section>

    <section class="sec">
      <div class="sec-head"><h2>留档</h2></div>
      <EventStream :node-key="key" />
    </section>
  </div>
</template>
