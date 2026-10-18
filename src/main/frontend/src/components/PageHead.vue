<script setup>
/**
 * 页头：标题 + 状态 + 动作条 + 闸那句话 + 这一页的失败条。
 *
 * **动作全部来自后端声明**（`GET /api/flow` 的 `actions`），这里只负责三件事：
 *   1. 按 `menu` 分成"页头"与「⋯」；
 *   2. `onlyWhileRunning` 的动作只在真跑着时出现，并且**排到页头最前面**——
 *      它是唯一能解开死锁的退路（叫停），藏进「⋯」会让用户看着提示却点不到；
 *   3. `disabled` 是**每次渲染重算的**（读 busy / 局部条件），绝不在挂载时算一次就冻住。
 */
import { computed } from 'vue';
import { store, node, actionsOf, busy, dismiss } from '../store.js';
import { statusWord, pillTone } from '../lib/status.js';

const props = defineProps({
  nodeKey: { type: String, default: '' },
  title: { type: String, required: true },
  subtitle: { type: String, default: '' },
  /** id → 处理函数（视图提供）；声明里有、这里没接的，渲染成灰的并说明原因 */
  handlers: { type: Object, default: () => ({}) },
  /** (id) => ({ disabled, tip }) —— 视图的局部条件，**每次渲染都会重算** */
  local: { type: Function, default: () => ({}) },
});

const view = computed(() => node(props.nodeKey));

const buttons = computed(() => {
  // 「在跑」有两类：① 提交给 Runs 的异步活（`store.running`），② 同步长任务（起环境那种，
  // 请求还没回来时 `busy` 里有一项）。两类都要让「叫停」露面——否则用户只能干看着。
  const busyHere = Object.keys(store.busy).some((one) => one.startsWith(props.nodeKey + ':'));
  const running = store.running === true || busyHere;
  return actionsOf(props.nodeKey)
    .filter((one) => one.onlyWhileRunning !== true || running)
    .map((one) => {
      const handler = props.handlers[one.id];
      const missing = typeof handler !== 'function';
      const extra = typeof props.local === 'function' ? props.local(one.id) || {} : {};
      const loading = busy(`${props.nodeKey}:${one.id}`);
      return {
        id: one.id,
        label: one.label,
        primary: one.primary === true,
        danger: one.danger === true,
        menu: one.menu === true,
        urgent: one.onlyWhileRunning === true,
        loading,
        disabled: missing || loading || extra.disabled === true,
        tip: missing ? '界面还没接上这个动作（后端声明了、前端没绑）' : extra.tip || one.tip || '',
        on: missing ? () => {} : handler,
      };
    });
});

/** 页头最多 2 枚；但"唯一退路"插队到最前。 */
const head = computed(() => {
  const urgent = buttons.value.filter((one) => one.urgent);
  const rest = buttons.value.filter((one) => !one.urgent && !one.menu);
  return urgent.concat(rest).slice(0, 2);
});

const overflow = computed(() => {
  const inHead = new Set(head.value.map((one) => one.id));
  return buttons.value.filter((one) => !inHead.has(one.id));
});

const word = computed(() => statusWord(view.value.state, store.running && store.node === props.nodeKey));
const tone = computed(() => pillTone(view.value.state, store.running && store.node === props.nodeKey,
  String(view.value.facts && view.value.facts.failed) === 'true'));
const gate = computed(() => String(view.value.blockedReason || ''));
/**
 * 这一页的失败条：`act()` 用的 key 是 `env:up` / `probe:agent` 这种「节点:动作」，
 * 所以按**前缀**取。原来这里只比 `nodeKey`，于是后端明明回了 400/409，页面上一片安静——
 * 用户的原话就是「点了跑探针没有反应」。
 */
const mine = computed(() => {
  const gateText = String(gate.value || '').trim();
  // 跟闸那句话**一模一样**的失败条不再单独显示：用户贴过一屏——同一句「还没跑过开发…」
  // 在闸那里一遍、在失败条那里又一遍（还带个 ×），像是引擎说了两遍话。
  return store.failures.filter((one) => {
    const ours = one.scope === props.nodeKey || String(one.scope || '').startsWith(props.nodeKey + ':');
    return ours && String(one.text || '').trim() !== gateText;
  });
});

function run(one) {
  one.on();
  // 「⋯」点完就收起来：它是浮层，不收盘会挡住下一个动作
  const details = document.querySelector('details.more[open]');
  if (details) {
    details.open = false;
  }
}
</script>

<template>
  <header class="head">
    <h1>{{ title }}</h1>
    <span v-if="nodeKey" class="pill" :data-tone="tone">
      <i />
      {{ word }}
    </span>
    <span v-if="subtitle" class="dim">{{ subtitle }}</span>
    <span class="grow" />

    <div class="actions">
      <button
        v-for="one in head"
        :key="one.id"
        type="button"
        class="btn"
        :class="{ primary: one.primary, danger: one.danger }"
        :aria-busy="one.loading ? 'true' : 'false'"
        :disabled="one.disabled"
        :title="one.tip"
        @click="run(one)"
      >{{ one.label }}</button>

      <details v-if="overflow.length" class="more" @click.stop>
        <summary class="btn" title="其余动作" aria-label="其余动作">⋯</summary>
        <div class="menu">
          <button
            v-for="one in overflow"
            :key="one.id"
            type="button"
            :class="{ danger: one.danger }"
            :disabled="one.disabled"
            :title="one.tip"
            @click="run(one)"
          >{{ one.label }}</button>
        </div>
      </details>
    </div>
  </header>

  <p v-if="gate" class="gate">{{ gate }}</p>

  <div v-for="one in mine" :key="one.id" class="banner" data-failure>
    <span class="text">{{ one.text }}</span>
    <button type="button" class="x" title="关掉这条" @click="dismiss(one.id)">×</button>
  </div>
  <slot />
</template>
