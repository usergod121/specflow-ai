<script setup>
/**
 * 欢迎页：挑一个项目。
 *
 * 最近列表里的**每一项都是按钮**（不再是"看起来能点但其实是文本"）；
 * 不存在的条目直接标出来——它们是"被移动/删掉的项目"，不该假装还在。
 */
import { ref } from 'vue';
import { useRouter } from 'vue-router';
import { store, openProject, loadRecent } from '../store.js';

const router = useRouter();
const path = ref('');
const picked = ref('');

async function enter(where) {
  const done = await openProject(where);
  if (done.ok) {
    router.push('/workbench');
  }
}

async function forget(where) {
  try {
    await fetch('/api/recent?path=' + encodeURIComponent(where), { method: 'DELETE' });
  } catch {
    // 忘记失败不重要，下面重拉一次列表
  }
  await loadRecent();
}
</script>

<template>
  <div class="page">
    <header class="head">
      <h1>打开项目</h1>
      <span class="grow" />
      <span class="dim">所有状态都存在项目目录里的 <code class="mono">.specflow/</code></span>
    </header>

    <section class="sec">
      <div class="sec-head"><h2>最近打开</h2></div>
      <p v-if="!store.recent.length" class="empty">
        <b>还没有打开过项目。</b>在下面填一个目录，或者点「选目录」。
      </p>
      <ul v-else style="list-style: none; margin: 0; padding: 0">
        <li v-for="one in store.recent" :key="one.path"
            style="display: flex; align-items: center; gap: 12px; padding: 8px 0; border-bottom: 1px solid var(--line)">
          <button type="button" class="btn ghost" style="padding: 0; justify-content: flex-start; min-width: 0"
                  :disabled="!one.exists" :title="one.exists ? one.path : '这个目录已经不在了'"
                  @click="enter(one.path)">
            <span style="font-weight: 600">{{ one.name }}</span>
          </button>
          <span class="dim mono wrap" style="flex: 1 1 auto; font-size: var(--fs-xs)">{{ one.path }}</span>
          <span v-if="!one.exists" class="dim">已不在</span>
          <button type="button" class="btn ghost tiny" title="从列表里去掉" @click="forget(one.path)">去掉</button>
        </li>
      </ul>
    </section>

    <section class="sec">
      <div class="sec-head"><h2>打开别的目录</h2></div>
      <div style="display: flex; gap: 8px; align-items: flex-end; max-width: 720px">
        <div class="field" style="flex: 1 1 auto">
          <label for="path">项目目录（绝对路径）</label>
          <input id="path" v-model="path" type="text" placeholder="E:\your\project"
                 @keyup.enter="path && enter(path)" />
        </div>
        <button type="button" class="btn primary" :disabled="!path.trim()" @click="enter(path.trim())">打开</button>
      </div>
      <p class="dim" style="margin-top: 8px">
        目录里没有 <code class="mono">.specflow/</code> 也没关系：打开时会铺一份最小配置。
      </p>
      <p v-if="picked" class="dim">{{ picked }}</p>
    </section>
  </div>
</template>
