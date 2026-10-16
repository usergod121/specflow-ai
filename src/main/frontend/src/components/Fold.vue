<script setup>
// 折叠区（DESIGN.md §3：44px 整行可点、左侧小三角、展开转 90°）。
defineProps({
  label: { type: String, required: true },
  count: { type: String, default: '' },
  tip: { type: String, default: '' },
  open: { type: Boolean, default: false },
});
</script>

<template>
  <details class="fold" :open="open">
    <summary :title="tip || undefined">
      <span class="tri" aria-hidden="true">▸</span>
      <span class="fold-label">{{ label }}</span>
      <span v-if="count" class="fold-count num">{{ count }}</span>
    </summary>
    <div class="fold-body"><slot /></div>
  </details>
</template>
