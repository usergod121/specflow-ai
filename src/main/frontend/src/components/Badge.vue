<script setup>
// 徽章：颜色 + 形状 + 文字三样都给（DESIGN.md §5：颜色不是唯一信号）。
defineProps({
  text: { type: String, required: true },
  tone: { type: String, default: 'idle' },
  mark: { type: String, default: '' },
  tip: { type: String, default: '' },
});
</script>

<template>
  <span class="badge" :data-tone="tone" :title="tip || undefined">
    <span v-if="mark" class="badge-mark" aria-hidden="true">{{ mark }}</span>{{ text }}
  </span>
</template>
