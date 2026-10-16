<script setup>
// 五个节点页共用的骨架（UI.md §3 末段）：页头 + 状态一行 + 主区 + 折叠。
// 各页不许各写一份——所以「页头动作」「状态条」「留档」「工具调用」都收在这里。
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import { store, startStream, stopStream, busyOf } from '../store.js';
import { badgesOf, noteOf, statusTextOf } from '../lib/facts.js';
import { logLines } from '../lib/events.js';
import MoreMenu from './MoreMenu.vue';
import Badge from './Badge.vue';
import Chips from './Chips.vue';
import Fold from './Fold.vue';

const props = defineProps({
  node: { type: String, required: true },
  title: { type: String, required: true },
  subtitle: { type: String, default: '' },
  actions: { type: Array, default: () => [] },
  menu: { type: Array, default: () => [] },
  facts: { type: Array, default: () => [] },
  empty: { type: String, default: '还没跑' },
  emptyTip: { type: String, default: '' },
  logSize: { type: Number, default: 60 },
});

const view = computed(() => {
  const list = (store.flow && store.flow.nodes) || [];
  return list.find(one => one && one.key === props.node)
    || { key: props.node, state: '', blockedReason: '', facts: {} };
});

const status = computed(() => statusTextOf(
  view.value.state,
  store.node === props.node && store.running === true,
  view.value.facts,
));
const statusTip = computed(() => {
  const one = view.value;
  const parts = ['state=' + (one.state || '（后端没给）')];
  if (one.blockedReason) {
    parts.push('闸：' + one.blockedReason);
  }
  return parts.join(' · ');
});
const reason = computed(() => noteOf(view.value));
const marks = computed(() => badgesOf(view.value.facts, props.facts));
/** 动作带上自己的转圈态——页头按钮与「⋯」里的按钮读同一份。 */
const headActions = computed(() => (props.actions || []).map(one => ({ ...one, busy: busyOf(props.node, one.id) })));
const menuActions = computed(() => (props.menu || []).map(one => ({ ...one, busy: busyOf(props.node, one.id) })));

// 留档：**最新在最上面**（倒序渲染）；「更早 N 行」往回翻。
const lines = computed(() => logLines(store.events, props.node).slice().reverse());
const size = ref(props.logSize);
const shown = computed(() => lines.value.slice(0, size.value));
const older = computed(() => Math.max(0, lines.value.length - size.value));

onMounted(() => startStream(props.node));
onBeforeUnmount(stopStream);
</script>

<template>
  <article class="page" :id="'page-' + node">
    <header class="page-head">
      <h1>{{ title }}</h1>
      <span v-if="subtitle" class="sub mono">{{ subtitle }}</span>
      <span class="grow"></span>
      <MoreMenu :scope="node" :actions="headActions" :menu="menuActions" />
    </header>

    <p class="status" :title="statusTip">
      <span class="status-dot" :data-tone="status === '失败' ? 'danger' : (store.running ? 'run' : 'idle')" aria-hidden="true"></span>
      <span class="status-text">{{ status }}</span>
      <span class="grow"></span>
      <Badge v-for="one in marks" :key="one.key" :text="one.text" :tone="one.tone" :mark="one.mark" :tip="one.title" />
    </p>

    <p v-if="reason" class="gate">
      <span class="gate-mark" aria-hidden="true">!</span>{{ reason }}
    </p>

    <div v-if="!store.booted" class="skel" aria-hidden="true">
      <span class="skel-line w60"></span>
      <span class="skel-line w40"></span>
    </div>
    <slot v-else />

    <Fold label="调用" :count="''" tip="这一次跑里它调过哪些工具。点一枚看参数原文与结果全文。">
      <Chips :node="node" :empty="empty" :empty-tip="emptyTip" />
    </Fold>

    <Fold label="留档" :count="lines.length ? lines.length + ' 行' : ''" tip="常驻日志：重启也在。最新的一条在最上面。">
      <ul v-if="shown.length" class="log" :id="'log-' + node">
        <li v-for="line in shown" :key="line.id" :data-level="line.level" :data-failure="line.level === 'error' ? 'true' : undefined">
          <span class="log-at num">{{ line.at }}</span>
          <span class="log-mark" aria-hidden="true">{{ line.level === 'error' ? '✗' : (line.level === 'warn' ? '!' : '·') }}</span>
          <span class="log-text">{{ line.text }}</span>
        </li>
      </ul>
      <p v-if="older" class="row">
        <button type="button" class="btn tiny ghost" @click="size = size + 100">更早 {{ older }} 行</button>
      </p>
      <p v-if="!lines.length" class="empty">还没记录</p>
    </Fold>
  </article>
</template>
