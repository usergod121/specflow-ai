<script setup>
// 工具调用 chip 流：**默认只画当前那一枚**（跑着 = 呼吸点、无耗时；跑完 = 对勾 + 耗时）。
// 上面一行写「12 次调用 · 1 报错」，右边「更早 8 次」。点一枚展开参数原文 + 结果全文。
import { computed, ref, watch } from 'vue';
import { store } from '../store.js';
import { chipSummary, mergeChips } from '../lib/events.js';
import Chip from './Chip.vue';
import Empty from './Empty.vue';

const props = defineProps({
  node: { type: String, required: true },
  empty: { type: String, default: '还没跑' },
  emptyTip: { type: String, default: '' },
});

const view = computed(() => mergeChips(store.events, {
  node: props.node,
  limit: Number(store.paging[props.node] || 1),
}));
const head = computed(() => chipSummary(view.value));
const busy = computed(() => store.node === props.node && store.running === true);
const showing = ref('');

watch(() => props.node, () => { showing.value = ''; });

function more() {
  store.paging[props.node] = Number(store.paging[props.node] || 1) + 8;
}

function toggle(one) {
  showing.value = showing.value === one.key ? '' : one.key;
}

const opened = computed(() => view.value.chips.find(one => one.key === showing.value) || null);
</script>

<template>
  <div class="chips" :id="'chips-' + node">
    <p v-if="head" class="chips-head">
      <span class="num">{{ head }}</span>
      <span class="grow"></span>
      <button v-if="view.hidden" type="button" class="btn tiny ghost" @click="more">
        更早 {{ view.hidden }} 次
      </button>
    </p>

    <div v-if="view.chips.length" class="chip-row">
      <Chip
        v-for="one in view.chips"
        :key="one.key"
        :chip="one"
        :data-open="opened && opened.key === one.key ? 'true' : 'false'"
        @toggle="toggle(one)"
      />
    </div>

    <div v-if="opened" class="chip-detail">
      <p class="chip-detail-head">
        <span class="mono">{{ opened.tool || '未知' }}</span>
        <span v-if="opened.at" class="num dim">{{ opened.at }}</span>
        <span class="grow"></span>
        <span class="num dim">{{ opened.status === 'running' ? '进行中' : opened.msText || '—' }}</span>
        <button type="button" class="btn tiny ghost" @click="showing = ''">收起</button>
      </p>
      <p class="lbl">参数原文</p>
      <pre class="code">{{ opened.argsRaw || '无参数' }}</pre>
      <p class="lbl">结果全文</p>
      <pre class="code">{{ opened.resultFull || '暂无结果' }}</pre>
    </div>

    <div v-if="!view.total && busy" class="skel" aria-hidden="true">
      <span class="skel-line w60"></span>
      <span class="skel-line w40"></span>
    </div>

    <Empty v-else-if="!view.total" :text="empty" :tip="emptyTip" />
  </div>
</template>
