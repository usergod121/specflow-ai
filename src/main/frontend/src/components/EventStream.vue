<script setup>
/**
 * 事件流：把这一格的事件按**最新在最上面**渲染（归档日志是只追加的，
 * 最新在底部意味着每次都要滚到底，而底下还在长）。
 *
 * 工具调用是「一枚 chip」：默认只显示摘要与耗时，点开看参数原文与结果全文。
 */
import { computed, ref } from 'vue';
import { store } from '../store.js';
import { clock, head, oneLine, seconds } from '../lib/format.js';

const props = defineProps({
  nodeKey: { type: String, required: true },
  limit: { type: Number, default: 80 },
});

const open = ref('');

const rows = computed(() => {
  const all = (store.events[props.nodeKey] || []).slice();
  return all.reverse().slice(0, props.limit);
});

const live = computed(() => store.running && store.node === props.nodeKey);

function level(one) {
  const raw = String(one.level || '').toLowerCase();
  if (raw === 'error' || raw === 'warn') {
    return raw;
  }
  if (one.type === 'result') {
    return 'ok';
  }
  if (one.type === 'tool') {
    return one.status === 'error' ? 'error' : 'ok';
  }
  if (one.type === 'gate') {
    return 'warn';
  }
  return '';
}

function title(one) {
  const text = one.text || '';
  if (one.type === 'tool') {
    const name = one.tool || 'tool';
    return `${name} · ${oneLine(one.args || '', 90)}`;
  }
  return oneLine(text, 220) || '（空）';
}

function detail(one) {
  return [one.args, one.resultFull].filter(Boolean).join('\n\n');
}
</script>

<template>
  <div class="stream" role="log" aria-live="polite">
    <p v-if="!rows.length" class="empty" style="margin: 12px">
      <b>还没有可看的行。</b>动作一开始跑，这里就会一行一行长出来。
    </p>
    <template v-for="one in rows" :key="one.id">
      <div class="ev" :data-level="level(one)">
        <time>{{ clock(one.at) }}</time>
        <span class="mark" />
        <div>
          <div class="text">
            {{ title(one) }}
            <span v-if="one.type === 'tool' && one.millis" class="meta">· {{ seconds(one.millis / 1000) }}</span>
            <span v-if="one.round" class="meta">· 第 {{ one.round }} 轮</span>
          </div>
          <button
            v-if="detail(one)"
            type="button"
            class="btn ghost tiny"
            style="margin-top: 4px; padding-left: 0"
            @click="open = open === one.id ? '' : one.id"
          >{{ open === one.id ? '收起原文' : '看原文' }}</button>
          <pre v-if="one && open === one.id">{{ head(detail(one), 8000) }}</pre>
        </div>
      </div>
    </template>
  </div>
  <p v-if="live" class="dim" style="margin-top: 8px">正在跑：{{ store.stage || '…' }}</p>
</template>
