import { onMounted, onUnmounted } from 'vue';
import { store, refresh, pullEvents, openStream, closeStreams } from '../store.js';

/**
 * 进哪一格，就把它设成「当前格」，并把这一格的事件流接上。
 *
 * 页面代码因此只需要 `useNode('env')`，不必各自处理"订阅/退订/补历史"——
 * 上一版每个页面各写一遍，写得还不一样（于是有的页面 running 永远不更新）。
 */
export function useNode(key) {
  onMounted(async () => {
    store.node = key;
    await refresh().catch(() => {});
    await pullEvents(key);
    openStream(key);
  });

  onUnmounted(() => {
    if (store.node === key) {
      store.node = '';
    }
    closeStreams();
  });
}
