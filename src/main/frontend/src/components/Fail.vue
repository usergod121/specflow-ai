<script setup>
// 失败就地显示：红字（形状 + 文字，不只靠颜色）+ 后端原文 + 重试。
import { computed } from 'vue';
import { failuresOf, dismiss } from '../store.js';

const props = defineProps({
  scope: { type: String, required: true },
  action: { type: String, required: true },
  retry: { type: Function, default: null },
});

const rows = computed(() => failuresOf(props.scope, props.action));
</script>

<template>
  <div v-if="rows.length" class="fail" data-failure="true" role="alert">
    <p v-for="row in rows" :key="row.id" class="fail-row">
      <span class="fail-mark" aria-hidden="true">✗</span>
      <span class="fail-text">{{ row.text }}</span>
      <button v-if="retry" type="button" class="btn tiny" @click="retry">重试</button>
      <button type="button" class="btn tiny ghost" @click="dismiss(row.id)">收起</button>
    </p>
  </div>
</template>
