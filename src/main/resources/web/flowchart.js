/**
 * 流程图渲染：解析 Mermaid 的一个很小的子集，用 SVG 自己画。
 *
 * 为什么不让模型直接给「节点 + 连线」的 JSON，而是解析 Mermaid：
 * Mermaid 是模型训练数据里最多的图表格式，它写得最稳；自定义格式它每次都能写歪。
 * 为什么不用 mermaid.js：那是一个几百 KB 的前端库，
 * 而这个项目到目前为止后端只有五个依赖，为一张图破例不划算。
 *
 * 支持的语法：
 *   flowchart TD
 *       A[文字] --> B{判断}
 *       B -->|是| C[文字]
 *       A --> B --> C                链式
 *       A --> B & C                  一对多
 *       subgraph 订单模块 ... end     分组，画成带标题的框
 *   边的种类：--&gt; --- -.-> -.- ==&gt; === --x --o
 *   标签两种写法：`--&gt;|是|` 与 `-- 是 --&gt;`
 *   形状：[] () (()) [] [[]] {} {{}} &gt;]
 *   标签里可以有引号和换行符 &lt;br/&gt;
 *
 * <p><b>认不出来的行绝不吞掉。</b>以前是把每一行都当成「节点链」硬啃，
 * 于是 `style B fill:#f9f` 会变成一个名叫 `style` 的孤立方块、
 * `subgraph`/`end` 会变成两个方块、`-.->` 连的边一条都画不出来——
 * 用户看到的是「图上多了几个莫名其妙的框、还缺了几条线」。
 * 现在认不出来的行会原样进 {@link parseFlow} 的返回值 `ignored`，界面会如实说出来。
 *
 * 画不出来时不抛异常、不留空白——直接把原文交给调用方显示。
 */

// 颜色只有这一处，和界面上那几个 CSS 变量是同一套值
const PALETTE = {
  nodeFill: '#ffffff',
  nodeStroke: '#c7ccd4',
  decisionFill: '#fff8e1',
  decisionStroke: '#e0b64a',
  text: '#1f2328',
  edge: '#9aa4b2',
  edgeLabel: '#6b7280',
  groupFill: '#fafbfc',
  groupStroke: '#d7dce3',
  groupTitle: '#6b7280',
};

const NODE_HEIGHT = 34;
const LINE_HEIGHT = 17;
const LAYER_GAP = 44;
const NODE_GAP = 24;
const GROUP_PAD = 8;
const GROUP_TITLE = 18;
const CHAR_WIDTH = 7.6;
const MIN_NODE_WIDTH = 76;
const SVG_NS = 'http://www.w3.org/2000/svg';

/** 节点 id：字母/下划线/汉字开头，后面可以带数字、点、横线。 */
const NODE_ID = '[A-Za-z_\\u4e00-\\u9fa5][\\w\\u4e00-\\u9fa5.\\-]*';

/**
 * 边的操作符。**长的写在前面**：否则 `-->` 会先被 `---` 吃掉一半。
 */
const ARROW = /(<-->|<--|-->|==>|-\.->|---|===|-\.-|--x|--o)/g;

/** 形状：顺序要紧，`[[]]` 必须在 `[]` 前面，否则只剩一个空标签。 */
const SHAPE_NAMES = ['subroutine', 'database', 'circle', 'hexagon', 'box', 'rounded', 'decision', 'flag'];
const SHAPE = new RegExp('^(' + NODE_ID + ')\\s*(?:'
    + '\\[\\[([^\\]]*)\\]\\]'      // [[]] 子程序
    + '|\\[\\(([^)]*)\\)\\]'       // [()] 数据库
    + '|\\(\\(([^)]*)\\)\\)'       // (()) 圆
    + '|\\{\\{([^}]*)\\}\\}'       // {{}} 六边形
    + '|\\[([^\\]]*)\\]'           // [] 矩形
    + '|\\(([^)]*)\\)'             // () 圆角
    + '|\\{([^}]*)\\}'             // {} 判断
    + '|>([^\\]]*)\\]'             // >] 旗形
    + ')?');

/** 带引号的标签：`A["里面有 | 和 [ 的标签"]`——先按引号取，才不会被括号带偏。 */
const QUOTED = new RegExp('^(' + NODE_ID + ')\\s*([\\[\\({>])[^"\\n]*?"([^"]*)"');

/**
 * 老式标签写法：`A -- 是 --> B`、`B -. 否 .-> C`、`C == 重要 ==> D`。
 * 这种写法把一条边拆成「前缀 + 标签 + 后缀」三段，先归一成 `-->|是|` 再统一处理。
 */
const OLD_LABEL = /(--|-\.|==)\s+(?![-=>.>])([^|<>]+?)\s+(-->|---|\.->|==>)/g;

const HEADER = /^(flowchart|graph)\b/;
/** 整段文本里有没有图表头（模型有时先写一行 `%% 说明`）。 */
const HAS_HEADER = /(^|\n)\s*(flowchart|graph)\b/i;
const COMMENT = /^%%/;
const INIT_DIRECTIVE = /^%%\{/;
const DIRECTIVE = /^(style|classDef|class|linkStyle|click|direction)\b/;
const CLASS_SUFFIX = /:::{1}[\w-]+/g;
const SUBGRAPH = /^subgraph\b\s*(.*)$/;
const BR = /<br\s*\/?>/gi;

const KINDS = {
  '-->': { style: 'solid', end: 'arrow' },
  '<-->': { style: 'solid', end: 'both' },
  '<--': { style: 'solid', end: 'both' },
  '---': { style: 'solid', end: 'none' },
  '-.->': { style: 'dashed', end: 'arrow' },
  '-.-': { style: 'dashed', end: 'none' },
  '==>': { style: 'thick', end: 'arrow' },
  '===': { style: 'thick', end: 'none' },
  '--x': { style: 'solid', end: 'cross' },
  '--o': { style: 'solid', end: 'circle' },
};

/**
 * 把 Mermaid 文本拆成节点、边、分组，以及「认不出来但也没吞掉」的那些行。
 *
 * @returns {{nodes: Map, edges: Array, groups: Array, ignored: Array}}
 *          ignored 里每项是 {@code {line, reason}}，界面据此说明「哪几行没画进图里」
 */
function parseFlow(text) {
  const nodes = new Map();
  const edges = [];
  const groups = [];
  const ignored = [];
  let open = null;

  const ignore = (line, reason) => ignored.push({ line: line, reason: reason });

  /** 定义（或补全）一个节点；认不完全就把「还有没认出来的部分」报回去。 */
  const define = token => {
    const text = token.trim();
    if (!text) return null;

    const quoted = text.match(QUOTED);
    if (quoted) {
      const shape = quoted[2] === '{' ? 'decision' : quoted[2] === '(' ? 'rounded'
          : quoted[2] === '>' ? 'flag' : 'box';
      remember(quoted[1], cleanLabel(quoted[3]), shape);
      return { id: quoted[1], rest: text.slice(quoted[0].length).trim() };
    }

    const match = text.match(SHAPE);
    if (!match) return null;
    const label = match.slice(2).find(value => value !== undefined);
    const shape = SHAPE_NAMES[match.slice(2).findIndex(value => value !== undefined)] || 'box';
    remember(match[1], label === undefined || label === '' ? match[1] : cleanLabel(label), shape);
    return { id: match[1], rest: text.slice(match[0].length).trim() };
  };

  const remember = (id, label, shape) => {
    const known = nodes.get(id);
    if (!known) nodes.set(id, { id, label, shape });
    else if (label && label !== id) { known.label = label; known.shape = shape; }
  };

  const join = id => {
    if (open && !open.members.includes(id)) open.members.push(id);
  };

  /** 一段文本可能是一组节点：`A & B`。返回 id 数组；认不出来的部分记进 ignored。 */
  const refs = (segment, line) => {
    const ids = [];
    for (const part of segment.split('&')) {
      const defined = define(part);
      if (!defined) {
        if (part.trim()) ignore(line, '认不出来的节点写法');
        continue;
      }
      if (defined.rest) ignore(line, '节点后面还有认不出来的内容');
      ids.push(defined.id);
      join(defined.id);
    }
    return ids;
  };

  for (const raw of String(text === null || text === undefined ? '' : text).split('\n')) {
    // 行尾的分号是 Mermaid 允许的写法；`:::` 是样式简写，剥掉但要记账
    const line = raw.trim().replace(/;$/, '');
    if (!line) continue;
    if (COMMENT.test(line) && !INIT_DIRECTIVE.test(line)) continue; // 注释不是内容
    if (INIT_DIRECTIVE.test(line)) { ignore(line, '图表初始化指令（样式类）'); continue; }
    if (HEADER.test(line)) continue;
    if (DIRECTIVE.test(line)) { ignore(line, '样式/交互指令（不影响内容）'); continue; }

    const sub = line.match(SUBGRAPH);
    if (sub) {
      open = { title: cleanLabel(sub[1]) || '（未命名分组）', members: [] };
      groups.push(open);
      continue;
    }
    if (line === 'end') { open = null; continue; }
    if (line.includes('~~~')) { ignore(line, '不可见的连接（画不出来）'); continue; }

    const cleaned = line.replace(CLASS_SUFFIX, '');
    if (cleaned !== line) ignore(line, '样式简写 :::（不影响内容）');

    // `-. 否 .->` 的后缀是 `.->`，补回 `-.` 才是那条点线
    const normalized = cleaned.replace(OLD_LABEL,
        (all, prefix, label, suffix) => (suffix === '.->' ? '-.->' : suffix) + '|' + label.trim() + '|');

    let cursor = 0;
    let previous = null;
    let pendingLabel = '';
    let pendingKind = KINDS['-->'];
    let match;
    ARROW.lastIndex = 0;
    while ((match = ARROW.exec(normalized)) !== null) {
      const sources = refs(normalized.slice(cursor, match.index), line);
      if (previous && sources.length) {
        for (const from of previous) {
          for (const to of sources) edges.push({ from, to, label: pendingLabel, kind: pendingKind });
        }
      }
      if (sources.length) previous = sources;

      cursor = ARROW.lastIndex;
      // 箭头后面紧跟的 |标签| 属于这条边
      const label = normalized.slice(cursor).match(/^\s*\|([^|]*)\|/);
      if (label) {
        pendingLabel = cleanLabel(label[1]);
        cursor += label[0].length;
      } else {
        pendingLabel = '';
      }
      pendingKind = KINDS[match[1]] || KINDS['-->'];
    }

    const targets = refs(normalized.slice(cursor), line);
    if (previous && targets.length) {
      for (const from of previous) {
        for (const to of targets) edges.push({ from, to, label: pendingLabel, kind: pendingKind });
      }
    } else if (!previous && !targets.length) {
      ignore(line, '这一行既不是节点也不是连线');
    }
  }

  // 没闭合的分组照样留着（框还在，只是不知道到哪儿结束）
  const linked = new Set();
  for (const edge of edges) {
    linked.add(edge.from);
    linked.add(edge.to);
  }
  // 一条边都没连上的节点：多数是模型写歪了（比如把一句说明写成了单独一行——那在 Mermaid 里
  // 也算一个节点声明）。报出来，用户就不会对着一个孤零零的框猜它是什么。
  const isolated = [...nodes.keys()].filter(id => !linked.has(id));
  return { nodes, edges, groups, ignored, isolated };
}

/** 标签清理：去引号、`<br>` 换算行、压掉多余空白。 */
function cleanLabel(text) {
  const value = String(text === null || text === undefined ? '' : text).trim();
  const unquoted = value.replace(/^"(.*)"$/s, '$1');
  return unquoted.replace(BR, '\n').replace(/[ \t]+/g, ' ').trim();
}

/**
 * 分层：每个节点的层号 = 「从任一入口到它的最长路径长度」。
 * 节点数很少，直接用松弛迭代，不引拓扑排序那一套。
 * 迭代次数以节点数为上界，所以即使图里成环也不会死循环。
 */
function layerize(nodes, edges) {
  const rank = new Map();
  nodes.forEach((_, id) => rank.set(id, 0));
  for (let round = 0; round < nodes.size; round++) {
    let changed = false;
    for (const edge of edges) {
      if (!rank.has(edge.from) || !rank.has(edge.to) || edge.from === edge.to) continue;
      const want = rank.get(edge.from) + 1;
      if (rank.get(edge.to) < want) {
        rank.set(edge.to, want);
        changed = true;
      }
    }
    if (!changed) break;
  }
  const layers = [];
  for (const [id, level] of rank) {
    if (!layers[level]) layers[level] = [];
    layers[level].push(id);
  }
  // 层内顺序按出现顺序，画出来才和模型写的顺序一致
  const order = new Map([...nodes.keys()].map((id, index) => [id, index]));
  layers.forEach(layer => layer.sort((a, b) => order.get(a) - order.get(b)));
  return layers.filter(Boolean);
}

/** 粗略估宽：中日韩字符按两个西文字符算。不用测量文本，够用就行。 */
function labelWidth(label) {
  let units = 0;
  for (const ch of label) {
    units += /[\u2e80-\u9fff\uff00-\uffef]/.test(ch) ? 2 : 1;
  }
  return Math.max(MIN_NODE_WIDTH, Math.round(units * CHAR_WIDTH) + 28);
}

/** 节点盒子的尺寸：标签里的换行要占多行，所以高度不是常数。 */
function nodeSize(node) {
  const lines = String(node.label).split('\n');
  const widest = lines.reduce((max, line) => Math.max(max, labelWidth(line)), 0);
  return { w: widest, h: Math.max(NODE_HEIGHT, lines.length * LINE_HEIGHT + 17) };
}

/**
 * 画进指定容器。
 *
 * @param text      模型给出的 Mermaid 原文
 * @param container 目标 DOM 元素；画不出来时会写入原文并加上 flow-fallback 类
 * @returns {{ignored: Array, groups: Array, nodes: number, edges: number}}
 *          调用方拿 ignored 去说明「哪几行没画进图里」
 */
function renderFlowchart(text, container) {
  const flow = parseFlow(text);
  const { nodes, edges, groups, ignored, isolated } = flow;
  const report = { ignored, groups, isolated, nodes: nodes.size, edges: edges.length };

  // 画不出来就原样显示：一段能读的源码也比一个空框子有用
  if (!HAS_HEADER.test(String(text || '')) || nodes.size < 2) {
    container.className = 'flow-fallback';
    container.textContent = text || '（模型没有给出流程图）';
    return report;
  }

  const { positions, totalWidth, totalHeight } = place(nodes, edges, groups);

  const svg = document.createElementNS(SVG_NS, 'svg');
  svg.setAttribute('viewBox', '0 0 ' + totalWidth + ' ' + totalHeight);
  svg.setAttribute('width', totalWidth);
  svg.setAttribute('height', totalHeight);
  svg.appendChild(arrowMarker());

  // 分组框画在最底下：它只是「这几步属于同一个模块」的底衬，不该压住线
  for (const group of groups) {
    drawGroup(svg, group, positions);
  }
  for (const edge of edges) {
    drawEdge(svg, edge, positions);
  }
  for (const node of nodes.values()) {
    drawNode(svg, node, positions.get(node.id));
  }

  container.className = 'flow';
  container.innerHTML = '';
  container.appendChild(svg);
  return report;
}

/**
 * 算位置。
 *
 * <p><b>分组各占一竖条</b>，不在任何组里的节点占最左边那条。这不是为了好看：
 * 如果照「每层居中排一行」的老办法，组框会横着盖住旁边那一层的节点，
 * 那个节点看起来就像组员——**图会撒谎**。分列之后，组框的左边界永远比别人的右边界还靠左，
 * 两者之间隔着一条列间距，谁也框不住谁。
 *
 * <p>没有分组时只有一列，位置和从前一模一样。
 */
function place(nodes, edges, groups) {
  const layers = layerize(nodes, edges);
  const sizes = new Map();
  nodes.forEach(node => sizes.set(node.id, nodeSize(node)));

  const owner = new Map();
  for (const group of groups) {
    for (const id of group.members) {
      if (nodes.has(id) && !owner.has(id)) owner.set(id, group);
    }
  }
  const columns = [{ group: null, ids: [...nodes.keys()].filter(id => !owner.has(id)) }];
  for (const group of groups) {
    columns.push({ group, ids: group.members.filter(id => nodes.has(id) && owner.get(id) === group) });
  }
  const columnOf = new Map();
  columns.forEach((column, index) => column.ids.forEach(id => columnOf.set(id, index)));

  const rowWidth = ids => ids.reduce((sum, id) => sum + sizes.get(id).w, 0)
      + NODE_GAP * Math.max(0, ids.length - 1);
  const inRow = (layer, index) => layer.filter(id => columnOf.get(id) === index);

  const columnWidth = columns.map((column, index) =>
      Math.max(0, ...layers.map(layer => rowWidth(inRow(layer, index)))));

  const columnX = [];
  let cursor = 20;
  columnWidth.forEach((width, index) => {
    if (index > 0 && columnWidth[index - 1] > 0) cursor += NODE_GAP;
    columnX[index] = cursor;
    cursor += width;
  });
  const totalWidth = cursor + 20;

  const layerHeight = layers.map(layer =>
      layer.reduce((max, id) => Math.max(max, sizes.get(id).h), NODE_HEIGHT));
  const heightAt = [];
  let used = 12;
  layers.forEach((layer, level) => {
    heightAt[level] = used;
    used += layerHeight[level] + LAYER_GAP;
  });
  const totalHeight = used - LAYER_GAP + 12;

  const positions = new Map();
  layers.forEach((layer, level) => {
    const y = heightAt[level];
    columns.forEach((column, index) => {
      const ids = inRow(layer, index);
      let x = columnX[index] + (columnWidth[index] - rowWidth(ids)) / 2;
      for (const id of ids) {
        positions.set(id, { x, y, w: sizes.get(id).w, h: sizes.get(id).h });
        x += sizes.get(id).w + NODE_GAP;
      }
    });
  });

  return { positions, totalWidth, totalHeight };
}

function arrowMarker() {
  const defs = document.createElementNS(SVG_NS, 'defs');
  defs.innerHTML = '<marker id="sf-arrow" viewBox="0 0 10 10" refX="9" refY="5" '
      + 'markerWidth="6" markerHeight="6" orient="auto-start-reverse">'
      + '<path d="M 0 0 L 10 5 L 0 10 z" fill="' + PALETTE.edge + '"/></marker>';
  return defs;
}

/** 分组框的位置：包住所有组员的圆角矩形，顶上留一条放组名。组里没人就不画。 */
function groupBox(group, positions) {
  const boxes = group.members.map(id => positions.get(id)).filter(Boolean);
  if (!boxes.length) return null;
  return {
    left: Math.min(...boxes.map(b => b.x)) - GROUP_PAD,
    right: Math.max(...boxes.map(b => b.x + b.w)) + GROUP_PAD,
    top: Math.min(...boxes.map(b => b.y)) - GROUP_PAD - GROUP_TITLE,
    bottom: Math.max(...boxes.map(b => b.y + b.h)) + GROUP_PAD,
  };
}

/** 分组框：包住成员节点的圆角矩形 + 左上角的组名。 */
function drawGroup(svg, group, positions) {
  const box = groupBox(group, positions);
  if (!box) return;

  const rect = document.createElementNS(SVG_NS, 'rect');
  rect.setAttribute('x', box.left);
  rect.setAttribute('y', box.top);
  rect.setAttribute('width', box.right - box.left);
  rect.setAttribute('height', box.bottom - box.top);
  rect.setAttribute('rx', 10);
  rect.setAttribute('fill', PALETTE.groupFill);
  rect.setAttribute('stroke', PALETTE.groupStroke);
  rect.setAttribute('stroke-width', '1');
  rect.setAttribute('stroke-dasharray', '4 3');
  rect.setAttribute('class', 'flow-group');
  svg.appendChild(rect);

  const title = document.createElementNS(SVG_NS, 'text');
  title.setAttribute('x', box.left + 8);
  title.setAttribute('y', box.top + 13);
  title.setAttribute('font-size', '11.5');
  title.setAttribute('fill', PALETTE.groupTitle);
  title.textContent = group.title;
  svg.appendChild(title);
}

function drawEdge(svg, edge, positions) {
  const from = positions.get(edge.from);
  const to = positions.get(edge.to);
  if (!from || !to) return;

  const x1 = from.x + from.w / 2;
  const y1 = from.y + from.h;
  const x2 = to.x + to.w / 2;
  const y2 = to.y;
  const midY = (y1 + y2) / 2;
  const kind = edge.kind || KINDS['-->'];

  // 贝塞尔而不是直线：先垂直下行一段再拐向目标，避免线穿过中间的节点
  const path = document.createElementNS(SVG_NS, 'path');
  path.setAttribute('d', 'M ' + x1 + ' ' + y1
      + ' C ' + x1 + ' ' + midY + ', ' + x2 + ' ' + midY + ', ' + x2 + ' ' + y2);
  path.setAttribute('fill', 'none');
  path.setAttribute('stroke', PALETTE.edge);
  path.setAttribute('stroke-width', kind.style === 'thick' ? '2.6' : '1.4');
  if (kind.style === 'dashed') path.setAttribute('stroke-dasharray', '5 4');
  if (kind.end === 'arrow' || kind.end === 'both') path.setAttribute('marker-end', 'url(#sf-arrow)');
  if (kind.end === 'both') path.setAttribute('marker-start', 'url(#sf-arrow)');
  svg.appendChild(path);

  // --x / --o 的收尾记号：画不出来就别画成箭头，那是两种意思
  if (kind.end === 'cross' || kind.end === 'circle') {
    const glyph = document.createElementNS(SVG_NS, 'text');
    glyph.setAttribute('x', x2);
    glyph.setAttribute('y', y2 + 3);
    glyph.setAttribute('font-size', '11');
    glyph.setAttribute('fill', PALETTE.edge);
    glyph.setAttribute('text-anchor', 'middle');
    glyph.textContent = kind.end === 'cross' ? '×' : '○';
    svg.appendChild(glyph);
  }

  if (!edge.label) return;
  const tag = document.createElementNS(SVG_NS, 'text');
  tag.setAttribute('x', (x1 + x2) / 2 + (x2 >= x1 ? 6 : -6));
  tag.setAttribute('y', midY + 4);
  tag.setAttribute('font-size', '11');
  tag.setAttribute('fill', PALETTE.edgeLabel);
  tag.setAttribute('text-anchor', x2 >= x1 ? 'start' : 'end');
  tag.textContent = edge.label;
  svg.appendChild(tag);
}

function drawNode(svg, node, at) {
  const decision = node.shape === 'decision';
  const shape = document.createElementNS(SVG_NS, 'polygon');
  const cx = at.x + at.w / 2;
  const cy = at.y + at.h / 2;
  if (decision) {
    // 菱形：判断节点，一眼能认出来
    shape.setAttribute('points', [cx + ',' + at.y, (at.x + at.w) + ',' + cy,
      cx + ',' + (at.y + at.h), at.x + ',' + cy].join(' '));
  } else if (node.shape === 'circle') {
    shape.setAttribute('points', ellipsePoints(cx, cy, at.w / 2, at.h / 2, 16));
  } else if (node.shape === 'hexagon') {
    const cut = Math.min(14, at.w / 4);
    shape.setAttribute('points', [(at.x + cut) + ',' + at.y, (at.x + at.w - cut) + ',' + at.y,
      (at.x + at.w) + ',' + cy, (at.x + at.w - cut) + ',' + (at.y + at.h),
      (at.x + cut) + ',' + (at.y + at.h), at.x + ',' + cy].join(' '));
  } else if (node.shape === 'flag') {
    const cut = Math.min(12, at.w / 4);
    shape.setAttribute('points', [at.x + ',' + at.y, (at.x + at.w - cut) + ',' + at.y,
      (at.x + at.w) + ',' + cy, (at.x + at.w - cut) + ',' + (at.y + at.h),
      at.x + ',' + (at.y + at.h)].join(' '));
  } else {
    // 圆角矩形/圆柱/子程序都按圆角矩形画：形状差别不影响读图，边框样式有一点点区分就够
    shape.setAttribute('points', roundedPoints(at.x, at.y, at.w, at.h,
        node.shape === 'database' || node.shape === 'subroutine' ? at.h / 2 : 7));
  }
  shape.setAttribute('fill', decision ? PALETTE.decisionFill : PALETTE.nodeFill);
  shape.setAttribute('stroke', decision ? PALETTE.decisionStroke : PALETTE.nodeStroke);
  shape.setAttribute('stroke-width', '1.2');
  shape.setAttribute('class', decision ? 'flow-node flow-decision' : 'flow-node');
  svg.appendChild(shape);

  const lines = String(node.label).split('\n');
  const text = document.createElementNS(SVG_NS, 'text');
  text.setAttribute('x', cx);
  text.setAttribute('y', cy - (lines.length - 1) * LINE_HEIGHT / 2 + 4);
  text.setAttribute('font-size', '12.5');
  text.setAttribute('fill', PALETTE.text);
  text.setAttribute('text-anchor', 'middle');
  lines.forEach((line, index) => {
    const span = document.createElementNS(SVG_NS, 'tspan');
    span.setAttribute('x', cx);
    span.setAttribute('dy', index === 0 ? '0' : String(LINE_HEIGHT));
    span.textContent = line;
    text.appendChild(span);
  });
  svg.appendChild(text);
}

/** 圆角矩形的点集（RX 相同，四角各用一段短弧近似）。 */
function roundedPoints(x, y, w, h, r) {
  const radius = Math.max(0, Math.min(r, h / 2, w / 2));
  const arc = (cx, cy, from) => {
    const points = [];
    for (let i = 0; i <= 4; i++) {
      const angle = from + (Math.PI / 2) * (i / 4);
      points.push(Math.round((cx + Math.cos(angle) * radius) * 10) / 10
          + ',' + Math.round((cy + Math.sin(angle) * radius) * 10) / 10);
    }
    return points;
  };
  return [
    (x + radius) + ',' + y, (x + w - radius) + ',' + y,
    ...arc(x + w - radius, y + radius, -Math.PI / 2),
    ...arc(x + w - radius, y + h - radius, 0),
    ...arc(x + radius, y + h - radius, Math.PI / 2),
    ...arc(x + radius, y + radius, Math.PI),
  ].join(' ');
}

function ellipsePoints(cx, cy, rx, ry, count) {
  const points = [];
  for (let i = 0; i < count; i++) {
    const angle = (Math.PI * 2 * i) / count - Math.PI / 2;
    points.push(Math.round((cx + Math.cos(angle) * rx) * 10) / 10
        + ',' + Math.round((cy + Math.sin(angle) * ry) * 10) / 10);
  }
  return points.join(' ');
}
