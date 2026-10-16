<script setup>
// 一枚工具调用 chip。状态**形状 + 文字**（点/叉/对勾），颜色只是第三重信号。
import { chipMark, chipWord } from '../lib/events.js';

defineProps({
  chip: { type: Object, required: true },
});
defineEmits(['toggle']);
</script>

<template>
  <button
    type="button"
    class="chip"
    :data-status="chip.status"
    :title="chip.arg"
    :aria-expanded="chip.open ? 'true' : 'false'"
    @click="$emit('toggle', chip)"
  >
    <span class="chip-mark" :data-status="chip.status" aria-hidden="true">{{ chipMark(chip.status) }}</span>
    <span class="chip-tool mono">{{ chip.tool || '未知' }}</span>
    <span class="chip-arg">{{ chip.arg }}</span>
    <span v-if="chip.status !== 'running' && chip.msText" class="chip-ms num">{{ chip.msText }}</span>
    <span class="chip-word">{{ chipWord(chip.status) }}</span>
  </button>
</template>
