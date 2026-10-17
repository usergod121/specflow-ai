import { computed } from 'vue';
import { store } from '../store.js';

/**
 * 节点动作：**声明在后端（`flow/Actions.java` 那一份表），这里只负责"按 id 绑处理函数"**。
 *
 * <h2>为什么改成这样（一次真实事故）</h2>
 * ① 和 ④ 的页面上**根本没有「冻结」按钮**——活干完了却永远走不到 DONE，下游永远解锁不了。
 * 根因不是谁写错了，而是"有哪些动作"散在 6 个地方（Node 枚举 / 闸表 / 路由注解 /
 * 前端的 flow.js / pages.js / 每个视图各写一遍的动作数组），漏改一处不报错，只会"按钮不见了"。
 *
 * <p>现在：后端在 {@code GET /api/flow} 的每一格里下发 {@code actions}，界面按它渲染；
 * <b>声明里有、界面没接</b>的动作会渲染成灰的并写明原因，而不是静默消失。
 */

/** 这一格声明了哪些动作（后端没给就当空表）。 */
export function declared(key) {
  const node = ((store.flow && store.flow.nodes) || []).find(one => one && one.key === key);
  return (node && node.actions) || [];
}

/**
 * 把声明变成可渲染的按钮。
 *
 * @param key      节点 key
 * @param handlers id → 处理函数（视图提供）
 * @param options  `{ disabled: {id: boolean} }`，**也可以是返回它的函数**（视图提供的局部禁用条件）
 *
 * <h2>为什么允许传函数（一条真 bug）</h2>
 * 这里是在 <b>computed 里每次求值</b>的，可如果视图传的是一个在 setup 时就求好的字面量
 * （`{ disabled: { agent: Boolean(store.flow) === false } }`），那个值就被冻在"组件挂载那一刻"了：
 * 直接打开 `/env` 时 `/api/flow` 还没回来 → `store.flow` 是空的 → 「探测环境」按钮被
 * <b>永久禁用</b>，而页面其它地方都是响应式的（状态、徽章都正常），只有这一个按钮点不动、
 * 还不报任何错。传函数就没有这个问题：每次重算都会重新读一遍 store。
 */
export function bind(key, handlers, options = {}) {
  const resolved = typeof options === 'function' ? options() : options;
  const running = store.running === true;
  const disabled = (resolved && resolved.disabled) || {};
  return declared(key)
    .filter(one => !one.onlyWhileRunning || running)
    .map(one => {
      const on = handlers[one.id];
      const missing = typeof on !== 'function';
      return {
        id: one.id,
        label: one.label,
        tip: missing ? '界面还没接上这个动作（声明里有、处理函数没有）' : one.tip,
        primary: one.primary === true,
        danger: one.danger === true,
        menu: one.menu === true,
        disabled: missing || disabled[one.id] === true,
        // 缺处理函数时点它不会有反应，所以干脆给一个空实现，别让它抛
        on: missing ? () => {} : on,
      };
    });
}

/** 页头那几个（最多 1 主 + 1 次；多余的由 MoreMenu 收进「⋯」）。 */
export function head(key, handlers, options = {}) {
  return computed(() => bind(key, handlers, options).filter(one => !one.menu));
}

/** 「⋯」里那几个。 */
export function menu(key, handlers, options = {}) {
  return computed(() => bind(key, handlers, options).filter(one => one.menu));
}
