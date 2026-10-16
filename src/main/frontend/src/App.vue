<script setup>
// 外壳：顶栏 56 + 侧栏 232 + 主区（最大 1100，左对齐不居中）——DESIGN.md §3。
import { computed, onMounted, ref } from 'vue';
import { useRoute } from 'vue-router';
import { store, boot, runningNow } from './store.js';
import { HOME, nodesOf } from './lib/flow.js';

const route = useRoute();
const collapsed = ref(false);
const nodes = computed(() => nodesOf(store.flow));
const project = computed(() => store.projectName || store.root || '未打开项目');

onMounted(boot);

function lockTitle(one) {
  if (one.blockedReason) {
    return one.blockedReason;
  }
  // 五格自己那句话（`state=READY` 这种）只进 title，正文一个字都不留
  return one.state ? '后端说它现在是 ' + one.state : '后端还没给这一格的话';
}
</script>

<template>
  <div class="shell" :data-rail="collapsed ? 'collapsed' : 'full'">
    <header class="topbar">
      <span class="brand">specflow</span>
      <span class="project mono" :title="store.root || '还没打开项目'">{{ project }}</span>
      <span class="grow"></span>
      <span class="model" :data-tone="store.hasModel ? 'ok' : 'idle'"
            :title="store.hasModel ? '已配好模型' : 'project.yaml 里还没写 llm'">
        <span aria-hidden="true">{{ store.hasModel ? '✓' : '—' }}</span>模型
      </span>
      <button type="button" class="btn tiny ghost" :aria-pressed="collapsed ? 'true' : 'false'"
              title="收起或展开侧栏" @click="collapsed = !collapsed">
        {{ collapsed ? '展开' : '收起' }}
      </button>
    </header>

    <nav class="rail" aria-label="五格">
      <router-link class="rail-item" to="/" :data-current="route.path === '/' ? 'true' : 'false'">
        <span class="rail-seq" aria-hidden="true"></span>
        <span class="rail-name">{{ HOME.name }}</span>
      </router-link>
      <ol class="rail-list">
        <li v-for="one in nodes" :key="one.key">
          <router-link
            class="rail-item"
            :to="one.route"
            :data-current="route.path === one.route ? 'true' : 'false'"
            :data-state="one.state || 'READY'"
            :title="lockTitle(one)"
          >
            <span class="rail-seq" aria-hidden="true">{{ one.seq }}</span>
            <span class="rail-name">{{ one.name }}</span>
            <span class="rail-dot" :data-tone="one.state === 'DONE' ? 'ok' : (one.state === 'LOCKED' ? 'idle' : 'ready')" aria-hidden="true"></span>
          </router-link>
        </li>
      </ol>
      <p v-if="!store.flow" class="rail-empty" title="打开一个项目之后，五格才有状态">还没流程</p>
      <p v-else-if="runningNow()" class="rail-running" title="后端还在跑，事件流一边跑一边往这里写">
        <span class="status-dot" data-tone="run" aria-hidden="true"></span>进行中
      </p>
    </nav>

    <main class="main">
      <p v-if="store.shellError" class="fail" data-failure="true" role="alert">
        <span class="fail-mark" aria-hidden="true">✗</span>
        <span class="fail-text">{{ store.shellError }}</span>
      </p>
      <router-view />
    </main>

    <p v-if="store.toast" class="toast" role="status">{{ store.toast }}</p>
  </div>
</template>
