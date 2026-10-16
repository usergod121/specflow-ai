<script setup>
// 欢迎页：挑项目——最近列表 + 手输路径 + 选文件夹（API.md §1）。
import { computed, ref } from 'vue';
import { useRouter } from 'vue-router';
import * as api from '../api.js';
import { store, openAt, loadRecent, act } from '../store.js';
import { baseName } from '../lib/text.js';
import Empty from '../components/Empty.vue';
import Fail from '../components/Fail.vue';

const router = useRouter();
const typed = ref(store.root || '');
const picked = ref(null);

const here = computed(() => picked.value && picked.value.path !== '' ? picked.value.path : (store.root || ''));

async function open() {
  const done = await openAt(typed.value.trim());
  if (done.ok) {
    router.push('/workbench');
  }
}

async function openRecent(one) {
  typed.value = one.path;
  await open();
}

async function forget(one) {
  await act('welcome', 'forget', () => api.forgetRecent(one.path));
  await loadRecent();
}

async function look(path) {
  const done = await act('welcome', 'browse', () => api.browse(path));
  if (done.ok) {
    picked.value = { path: String(done.value.path || path || ''), dirs: done.value.dirs || [], files: done.value.files || [] };
    typed.value = picked.value.path;
  }
  return done;
}

async function up() {
  const path = here.value.replace(/[\\/][^\\/]*$/, '');
  await look(path);
}

function join(name) {
  const head = here.value.replace(/[\\/]+$/, '');
  return head ? head + '\\' + name : name;
}
</script>

<template>
  <article class="page">
    <header class="page-head">
      <h1>打开项目</h1>
      <span class="grow"></span>
    </header>

    <section class="sec">
      <h2>项目路径</h2>
      <div class="row">
        <input
          v-model="typed"
          class="grow mono"
          type="text"
          id="path-input"
          placeholder="E:\my-project"
          title="填项目根目录，然后点「打开」"
          @keyup.enter="open"
        />
        <button type="button" class="btn primary" :data-loading="store.busy['welcome:open'] ? 'true' : 'false'" @click="open">打开</button>
      </div>
      <Fail scope="welcome" action="open" :retry="open" />
      <Fail scope="welcome" action="forget" :retry="loadRecent" />
    </section>

    <section class="sec">
      <h2>最近</h2>
      <ul v-if="store.recent.length" class="list">
        <li v-for="one in store.recent" :key="one.path">
          <button type="button" class="list-main" :title="one.path" @click="openRecent(one)">
            <span class="name">{{ one.name || baseName(one.path) }}</span>
            <span class="where mono">{{ one.path }}</span>
            <span v-if="one.exists === false" class="dim">已不在</span>
          </button>
          <button type="button" class="btn tiny ghost" title="从最近里去掉" @click="forget(one)">移除</button>
        </li>
      </ul>
      <Empty v-else text="还没记录" tip="打开过一次项目之后，这里会记住它，下次点一下就能回到同一个地方。" />
    </section>

    <section class="sec">
      <h2>挑目录</h2>
      <p class="row">
        <button type="button" class="btn" @click="look(here || store.root)">
          {{ picked ? '重读' : '浏览' }}
        </button>
        <button v-if="picked && picked.path" type="button" class="btn ghost" @click="up">上一层</button>
        <span class="grow"></span>
        <span class="mono dim">{{ here || '（空）' }}</span>
      </p>
      <Fail scope="welcome" action="browse" :retry="() => look(here)" />
      <ul v-if="picked && picked.dirs.length" class="list dense">
        <li v-for="one in picked.dirs" :key="one">
          <button type="button" class="list-main" @click="look(join(one))">
            <span class="mono">{{ one }}</span>
          </button>
        </li>
      </ul>
      <p v-if="picked && !picked.dirs.length" class="empty">没有子目录</p>
    </section>
  </article>
</template>
