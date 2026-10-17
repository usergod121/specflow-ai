<script setup>
// 页头动作条：**最多 1 主 + 1 次**，其余进「⋯」（UI.md §2.1）。
// 同一个动作只许有一个入口——页头已经有的动作，「⋯」里自动去掉。
import { computed, ref, onBeforeUnmount } from 'vue';

const props = defineProps({
  scope: { type: String, required: true },
  actions: { type: Array, default: () => [] },
  menu: { type: Array, default: () => [] },
});

/**
 * 页头最多 2 枚，但**唯一能解开死锁的那一枚**（只在跑着时出现的那类，比如叫停）插队到最前面。
 *
 * <p>用户实报：提示写着「点『叫停』」，可它被挤进了「⋯」，页面上看不到——那一枚必须在明面上。
 */
const head = computed(() => {
  const list = props.actions || [];
  const urgent = list.filter(one => one.onlyWhileRunning === true);
  const normal = list.filter(one => one.onlyWhileRunning !== true);
  return urgent.concat(normal).slice(0, 2);
});
const more = computed(() => {
  const inHead = new Set(head.value.map(one => one.id));
  const rest = (props.actions || []).concat(props.menu || []);
  const seen = new Set();
  return rest.filter(one => !one.hidden && !inHead.has(one.id) && (seen.has(one.id) ? false : seen.add(one.id)));
});

const open = ref(false);
const where = ref({ top: 0, left: 0 });

function place() {
  const trigger = document.getElementById('more-' + props.scope);
  if (!trigger) {
    return;
  }
  const rect = trigger.getBoundingClientRect();
  where.value = { top: Math.round(rect.bottom + 6), left: Math.round(Math.max(8, rect.right - 224)) };
}

function toggle() {
  open.value = !open.value;
  if (open.value) {
    place();
    window.addEventListener('resize', place);
  } else {
    window.removeEventListener('resize', place);
  }
}

function run(one) {
  toggle();
  if (typeof one.on === 'function') {
    one.on();
  }
}

onBeforeUnmount(() => window.removeEventListener('resize', place));
</script>

<template>
  <div class="actions" :id="'actions-' + scope">
    <button
      v-for="one in head"
      :key="one.id"
      type="button"
      class="btn"
      :class="{ primary: one.primary === true, danger: one.danger === true }"
      :data-loading="one.busy === true ? 'true' : 'false'"
      :disabled="one.disabled === true || one.busy === true"
      :title="one.tip || undefined"
      @click="one.on()"
    >{{ one.label }}</button>

    <button
      v-if="more.length"
      type="button"
      class="btn more-btn"
      :id="'more-' + scope"
      :aria-expanded="open ? 'true' : 'false'"
      title="其余动作"
      @click="toggle"
    >⋯</button>

    <teleport to="body">
      <div v-if="open" class="more-layer" @click.self="toggle">
        <ul class="more-list" :style="{ top: where.top + 'px', left: where.left + 'px' }">
          <li v-for="one in more" :key="one.id">
            <button
              type="button"
              class="btn ghost"
              :class="{ danger: one.danger === true }"
              :data-loading="one.busy === true ? 'true' : 'false'"
              :disabled="one.disabled === true || one.busy === true"
              :title="one.tip || undefined"
              @click="run(one)"
            >{{ one.label }}</button>
          </li>
        </ul>
      </div>
    </teleport>
  </div>
</template>
