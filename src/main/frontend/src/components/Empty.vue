<script setup>
// 空状态只给一句（≤6 个汉字）；怎么开始的话进 title。
defineProps({
  text: { type: String, required: true },
  tip: { type: String, default: '' },
});
</script>

<template>
  <p class="empty" :title="tip || undefined">{{ text }}</p>
</template>
