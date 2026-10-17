<script setup>
/**
 * 外壳：左边五格流水（真正的顺序，所以用编号），右边放这一页。
 *
 * 五格的状态、能不能进，全部读 `/api/flow`——这一层**一行判据都没有**：
 * 它只是把 `state` 翻译成一个小圆点，把 `blockedReason` 交给页面去说。
 */
import { computed, onMounted } from 'vue';
import { RouterLink, RouterView, useRoute } from 'vue-router';
import { store, boot, node, closeStreams } from './store.js';
import { statusWord, toneOf } from './lib/status.js';
import { routes } from './router.js';

const route = useRoute();

const steps = computed(() =>
  routes
    .filter((one) => one.node)
    .map((one) => {
      const view = node(one.node);
      return {
        path: one.path,
        key: one.node,
        title: one.title,
        state: view.state,
        blocked: view.blockedReason,
        tone: toneOf(view.state, store.running && store.node === one.node),
      };
    }));

const index = computed(() => steps.value.findIndex((one) => one.path === route.path));

onMounted(() => {
  boot();
  closeStreams();
});
</script>

<template>
  <div class="shell">
    <aside class="rail">
      <div class="brand">specflow <small>v3</small></div>

      <nav aria-label="五步">
        <RouterLink
          v-for="(one, at) in steps"
          :key="one.key"
          :to="one.path"
          class="step"
          :class="{ on: route.path === one.path }"
          :title="one.blocked || one.title"
        >
          <span class="num">{{ ['①', '②', '③', '④', '⑤'][at] }}</span>
          <span>{{ one.title }}</span>
          <span class="dot" :class="one.tone" />
        </RouterLink>
        <RouterLink to="/workbench" class="step" :class="{ on: route.path === '/workbench' }">
          <span class="num">·</span>
          <span>工作台</span>
          <span class="dot" />
        </RouterLink>
      </nav>

      <div class="rail-foot">
        <div class="project-line" :title="store.root">
          <span class="dot" :class="store.open ? 'done' : ''" />
          <span class="name">{{ store.projectName || '还没打开项目' }}</span>
        </div>
        <RouterLink v-if="store.open" to="/" class="dim">换项目</RouterLink>
        <span v-if="store.open && !store.hasModel" class="dim" title="项目里没有可用的 key">
          没有密钥
        </span>
      </div>
    </aside>

    <main class="main">
      <div v-if="!store.boot" class="page">
        <div class="sk" style="width: 40%" />
        <div class="sk" style="width: 70%; margin-top: 12px" />
        <div class="sk" style="width: 55%; margin-top: 12px" />
      </div>
      <RouterView v-else />
    </main>

    <p v-if="store.toast" class="toast" role="status">{{ store.toast }}</p>
  </div>
</template>
