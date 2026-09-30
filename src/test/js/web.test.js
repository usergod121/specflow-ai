/**
 * 前端纯逻辑的测试。
 *
 * 直接读真源码，测的不是副本——改了实现而忘了改测试会立刻暴露。
 * 碰 DOM 的部分（组装、绘制）不在这里测，那些只有在浏览器里才能验；
 * 流程图渲染的肉眼确认请打开 http://127.0.0.1:<port>/flowchart-demo.html
 *
 * 运行方式：node src/test/js/web.test.js
 * 它没有被接进 mvn，因为那需要为 Maven 引入一个执行 Node 的插件——
 * 对只有两个前端文件的项目来说，代价大于收益。
 */
'use strict';

const fs = require('fs');
const path = require('path');

const WEB = path.join(__dirname, '..', '..', 'main', 'resources', 'web');

/** 从源码里取出指定片段并求值，这样测试和运行时读的是同一份文件。 */
function load(file, names, fromMarker, toMarker) {
  // 换行先统一成 LF 再找标记：这两个文件在 Windows 的编辑器里很容易变成 CRLF，
  // 而标记里写死 \r\n 的话，一条测试会红成「看谁的编辑器」——那种红没有任何信息量。
  const source = fs.readFileSync(path.join(WEB, file), 'utf8').replace(/\r\n/g, '\n');
  const from = fromMarker ? source.indexOf(fromMarker) : 0;
  const to = toMarker ? source.indexOf(toMarker) : source.length;
  if (from < 0 || to < 0 || to <= from) {
    console.error('找不到 ' + file + ' 里的目标片段，结构可能变了');
    process.exit(1);
  }
  return eval(source.slice(from, to) + '\n({' + names.join(',') + '})');
}

/** 页面里的 state 是全局的，被测函数会读它；这里放一份假的，纯逻辑测试就不用开浏览器。 */
globalThis.state = { files: [], selected: new Set(), stepStates: new Map(), stepDetail: null };

let failed = 0;
function check(condition, message) {
  if (condition) {
    console.log('  ok   ' + message);
  } else {
    console.log('  FAIL ' + message);
    failed++;
  }
}

// ---------- 流程图 ----------
const { parseFlow, layerize, labelWidth, place, groupBox, familyIds } =
    load('flowchart.js', ['parseFlow', 'layerize', 'labelWidth', 'place', 'groupBox', 'familyIds']);

console.log('流程图解析：');
const sample = `flowchart TD
    A[GET /orders] --> B{page 是否合法}
    B -->|否| C[返回 400]
    B -->|是| D[OrderService 列表查询]
    D --> E[OrderMapper 查库]
    E --> F[组装 DTO 并返回]`;

const { nodes, edges } = parseFlow(sample);
check(nodes.size === 6, '六个节点');
check(edges.length === 5, '五条边');
check(nodes.get('A').label === 'GET /orders', '节点标签被取出来');
check(nodes.get('B').shape === 'decision', '花括号解析成判断节点');
check(edges.find(e => e.to === 'C').label === '否', '箭头标签属于它后面那条边');
check(edges.find(e => e.to === 'D').label === '是', '两个分支的标签不串位');
check(edges.find(e => e.to === 'E').label === '', '没有标签的边为空串');

const chain = parseFlow('flowchart TD\n  X[一] --> Y[二] --> Z[三]');
check(chain.nodes.size === 3 && chain.edges.length === 2, '链式写法 A --> B --> C');

console.log('流程图分层：');
const layers = layerize(nodes, edges);
check(layers.length === 5, '最长路径为 4，共 5 层');
check(layers[0].join() === 'A', '第一层只有入口');
check(layers[2].sort().join() === 'C,D', '同层节点并排');
check(layers[4].join() === 'F', '最后一层是出口');

const cyclic = parseFlow('flowchart TD\n  A[一] --> B[二]\n  B --> A');
check(layerize(cyclic.nodes, cyclic.edges).length <= 2, '成环时分层仍有界，不会死循环');

// ---------- 流程图：不能被吞掉的东西 ----------
// 这一段全是实测出来的现场：模型爱写 style / subgraph / 点线，
// 而老解析器把「每一行」都当成节点链硬啃，于是图上多出名叫 style、end 的方块，
// 点线连的边一条都画不出来。用户看到的是「画得越多、缺得越多」。
console.log('流程图：样式指令不变成节点：');
const styled = parseFlow(`flowchart TD
    A[入口] --> B{判断}
    B -->|是| C[处理]
    style B fill:#f9f,stroke:#333
    classDef big font-size:20px
    linkStyle 0 stroke:#f00
    click B "http://example.com"`);
check(styled.nodes.size === 3, '指令行不产生节点：' + [...styled.nodes.keys()].join(','));
check(!styled.nodes.has('style') && !styled.nodes.has('classDef') && !styled.nodes.has('linkStyle'),
    '没有名叫 style/classDef/linkStyle 的幻影节点');
check(styled.edges.length === 2, 'A→B、B→C 两条边都在');
check(styled.ignored.length === 4, '四条指令行都被记下来，而不是悄悄消失');
check(styled.ignored.every(item => item.line && item.reason), '每条都带着原文和原因');

console.log('流程图：分组：');
const grouped = parseFlow(`flowchart TD
    A[入口] --> B[下单]
    subgraph 订单模块
      B --> C[扣库存]
    end
    C --> D[返回]`);
check(grouped.nodes.size === 4, 'subgraph/end 不当成节点：' + [...grouped.nodes.keys()].join(','));
check(grouped.groups.length === 1 && grouped.groups[0].title === '订单模块', '分组标题取到了');
check(grouped.groups[0].members.join(',') === 'B,C', '组员是组里出现过的节点：'
    + grouped.groups[0].members.join(','));
check(grouped.ignored.length === 0, '分组本身不算「没认出来」');

console.log('流程图：嵌套的分组：');
// 用户的现场：模型画了嵌套的 subgraph。以前 open 只有一个，内层一开外层就再也收不到组员，
// 内层 end 之后的节点还挂到了内层名下——于是外层成了空框（一个框都不画），
// 而内层的框圈着一堆不属于它的节点。图上少一块，剩下的那块还在撒谎。
const nested = parseFlow(`flowchart TD
    subgraph 订单模块
      A[入口] --> B[下单]
      subgraph 库存子模块
        B --> C[扣库存]
      end
      C --> D[记账]
    end
    D --> E[返回]`);
check(nested.groups.length === 2, '两层分组都在：' + nested.groups.map(g => g.title).join(','));
check(nested.groups[0].members.join(',') === 'A,B,D',
    '内层 end 之后的组员回到外层，而不是挂在内层名下：' + nested.groups[0].members.join(','));
check(nested.groups[1].members.join(',') === 'C',
    '内层只有它自己那一块：' + nested.groups[1].members.join(','));
check(nested.groups[0].children.length === 1 && nested.groups[0].children[0] === nested.groups[1],
    '内层挂在外层底下');
check(nested.groups[1].depth === 1, '内层记着自己第几层——框要按它往上让位');
check(nested.nodes.size === 5 && nested.edges.length === 4, '节点和边一条不少');
check(nested.ignored.length === 0, '嵌套是认得的语法，不该记成「没认出来」');

// 一个节点只归一个分组。否则第二个分组的框会把第一个分组的那块也圈进来，
// 那个节点看起来就像两家的组员——正是「图会撒谎」那条底线。
const twice = parseFlow(`flowchart TD
    subgraph 甲
      A --> B
    end
    subgraph 乙
      B --> C
    end`);
check(twice.groups[0].members.join(',') === 'A,B' && twice.groups[1].members.join(',') === 'C',
    '同一个节点只算最先收下它的那个分组：' + twice.groups[0].members.join(',') + ' / '
    + twice.groups[1].members.join(','));

console.log('流程图：边的花样：');
const kinds = parseFlow(`flowchart TD
    A -.-> B
    B ==> C
    C --- D
    D --x E
    E --o F`);
check(kinds.edges.length === 5, '五种边都认出来了：' + kinds.edges.length);
check(kinds.edges.map(e => e.kind.style).join(',') === 'dashed,thick,solid,solid,solid',
    '线型分别记成点线/粗线/实线');
check(kinds.edges.map(e => e.kind.end).join(',') === 'arrow,arrow,none,cross,circle',
    '收尾方式分别记成箭头/无/叉/圈');

console.log('流程图：一对多与老式标签：');
const fan = parseFlow('flowchart TD\n    A --> B & C');
check(fan.edges.length === 2 && fan.nodes.size === 3, 'A --> B & C 画出两条边，C 不再消失');
const oldLabel = parseFlow('flowchart TD\n    A -- 是 --> B\n    B -. 否 .-> C');
check(oldLabel.edges[0].label === '是', '老式写法 `-- 是 -->` 的标签取到了');
check(oldLabel.edges[1].label === '否' && oldLabel.edges[1].kind.style === 'dashed',
    '`-. 否 .->` 的标签和线型都对');

console.log('流程图：标签里的引号与换行：');
const fancy = parseFlow(`flowchart TD
    A["带 | 竖线 的标签"] --> B[两行<br/>文字]`);
check(fancy.nodes.get('A').label === '带 | 竖线 的标签', '引号标签里的竖线没被切开也不带引号：'
    + fancy.nodes.get('A').label);
check(fancy.nodes.get('B').label === '两行\n文字', '标签里的 <br/> 变成长度二的两行');
check(fancy.edges.length === 1, '这两条边照样连得上');

console.log('流程图：形状与认不出来的行：');
const shapes = parseFlow(`flowchart TD
    A(圆角) --> B((圆))
    C[(数据库)] --> D{{六边形}}
    E>旗形] --> F[[]]`);
check(shapes.nodes.get('A').shape === 'rounded' && shapes.nodes.get('B').shape === 'circle',
    '圆角/圆分得开');
check(shapes.nodes.get('C').shape === 'database' && shapes.nodes.get('D').shape === 'hexagon',
    '数据库/六边形分得开');
check(shapes.nodes.get('E').shape === 'flag' && shapes.nodes.get('F').shape === 'subroutine',
    '旗形/子程序分得开');
const broken = parseFlow('flowchart TD\n    A[没收尾 --> B[二]\n    这一行不是图\n    这是说明文字');
check(broken.ignored.length >= 1, '认不出来的行进了 ignored：'
    + JSON.stringify(broken.ignored.map(i => i.reason)));
check(broken.isolated.join(',') === '这一行不是图,这是说明文字',
    '「一行说明文字」在 Mermaid 里也算节点，但它一条边都没有——这种孤立体要报出来：'
    + broken.isolated.join(','));

console.log('流程图：注释与图表头不算内容：');
const withComment = parseFlow('%% 这是说明\nflowchart TD\n    A --> B %% 行尾说明');
check(withComment.nodes.size === 2, '整行注释不影响节点数');
check(withComment.ignored.length === 1, '行尾那种「节点后面还有话」会被记一笔：'
    + JSON.stringify(withComment.ignored.map(i => i.reason)));

// ---------- 流程图摆位（纯算数，不用开浏览器） ----------
// 「图上看着像组员」比「图上少一个框」更坏：前者是图在撒谎。
// 所以这里的核心断言是：分组框里除了它的组员，不能有别人。
console.log('流程图摆位：');
const placed = parseFlow(`flowchart TD
    A[收到下单请求] --> B{库存够不够}
    B -->|够| C[创建订单]
    B -->|不够| D[返回缺货]
    subgraph 订单模块
      C --> E[扣减库存]
    end
    E -.-> F[发通知]`);
const layout = place(placed.nodes, placed.edges, placed.groups);
const boxOf = id => layout.positions.get(id);
check(layout.positions.size === 6, '六个节点都摆上了位置');
const pairs = [...placed.nodes.keys()];
const overlaps = [];
for (let i = 0; i < pairs.length; i++) {
  for (let j = i + 1; j < pairs.length; j++) {
    const a = boxOf(pairs[i]), b = boxOf(pairs[j]);
    if (a.x < b.x + b.w && b.x < a.x + a.w && a.y < b.y + b.h && b.y < a.y + a.h) {
      overlaps.push(pairs[i] + '/' + pairs[j]);
    }
  }
}
check(overlaps.length === 0, '节点框互不重叠：' + overlaps.join(','));
check(pairs.every(id => boxOf(id).x >= 0 && boxOf(id).y >= 0
    && boxOf(id).x + boxOf(id).w <= layout.totalWidth
    && boxOf(id).y + boxOf(id).h <= layout.totalHeight), '所有节点都在画布内');

const group = groupBox(placed.groups[0], layout.positions);
const box = id => layout.positions.get(id);
const hits = (b, area) => b.x < area.right && area.left < b.x + b.w
    && b.y < area.bottom && area.top < b.y + b.h;
const inside = id => {
  const b = box(id);
  return b.x >= group.left && b.x + b.w <= group.right && b.y >= group.top && b.y + b.h <= group.bottom;
};
check(placed.groups[0].members.every(inside), '组员都在组框里：' + placed.groups[0].members.join(','));
// 「不整个在里面」还不够：被组框压住一半的节点看起来同样像组员（甚至像画坏了）
const intruders = pairs.filter(id => !placed.groups[0].members.includes(id))
    .filter(id => hits(box(id), group));
check(intruders.length === 0, '非组员一个都不和组框相交，连压到一半都不行：' + intruders.join(','));
check(group.right <= layout.totalWidth && group.left >= 0 && group.top >= 0,
    '组框也在画布内');

// 嵌套：子分组的框必须整个套在父分组的框里，而且两层的组名不许叠在一起。
// 组的框比节点靠外一圈、顶上还要一条组名带，所以「第一层里就有组员」时整张图要往下让位——
// 不让的话框顶连组名一起被画布裁掉，用户看到的是一个没有名字的框。
console.log('流程图摆位：嵌套的框：');
const nestLayout = place(nested.nodes, nested.edges, nested.groups);
const outer = groupBox(nested.groups[0], nestLayout.positions);
const inner = groupBox(nested.groups[1], nestLayout.positions);
const boxIn = (b, area) => b.x >= area.left && b.x + b.w <= area.right
    && b.y >= area.top && b.y + b.h <= area.bottom;
check(inner.left >= outer.left && inner.right <= outer.right
    && inner.top >= outer.top && inner.bottom <= outer.bottom,
    '子分组的框整个套在父分组的框里：内 ' + JSON.stringify(inner) + ' / 外 ' + JSON.stringify(outer));
check(inner.top - outer.top >= 14, '父子两层的组名各占一条带子，不叠在一起：'
    + Math.round(inner.top - outer.top));
check(outer.top >= 0, '最靠上的那个分组框也没被画布裁掉：top=' + Math.round(outer.top));
check([...nested.nodes.keys()].every(id => {
  const b = nestLayout.positions.get(id);
  return b.x >= 0 && b.y >= 0
      && b.x + b.w <= nestLayout.totalWidth && b.y + b.h <= nestLayout.totalHeight;
}), '让位之后所有节点仍在画布内');

const family = new Set(familyIds(nested.groups[0]));
const strangers = [...nested.nodes.keys()].filter(id => !family.has(id));
const hitsBox = (b, area) => b.x < area.right && area.left < b.x + b.w
    && b.y < area.bottom && area.top < b.y + b.h;
check(strangers.length > 0 && strangers.every(id => !hitsBox(nestLayout.positions.get(id), outer)),
    '家族之外的节点一个都不和父分组的框相交：' + strangers.join(','));
check(nested.groups[1].members.every(id => boxIn(nestLayout.positions.get(id), inner)),
    '内层的组员都在内层的框里');

// 没有分组时不能因为「分列」把老样子改掉
const plain = parseFlow('flowchart TD\n    A[一] --> B[二]\n    A --> C[三]');
const plainLayout = place(plain.nodes, plain.edges, plain.groups);
const boxes = ['A', 'B', 'C'].map(id => plainLayout.positions.get(id));
check(Math.round(boxes[1].y) === Math.round(boxes[2].y), '同层节点还是并排');
check(Math.round(boxes[1].x + boxes[1].w + 24) === Math.round(boxes[2].x),
    '同层之间还是留一个固定间距');
const leftMargin = Math.min(...boxes.map(b => b.x));
const rightMargin = plainLayout.totalWidth - Math.max(...boxes.map(b => b.x + b.w));
check(Math.round(leftMargin) === Math.round(rightMargin),
    '整张图还是左右居中的：左 ' + Math.round(leftMargin) + ' / 右 ' + Math.round(rightMargin));

console.log('流程图估宽：');
const cjk = '中文标签';
const ascii = 'a longer ascii label';
check(labelWidth(cjk) === Math.round(cjk.length * 2 * 7.6) + 28, '中文按双宽估算');
check(labelWidth(ascii) === Math.round(ascii.length * 7.6) + 28, '西文按单宽估算');
check(labelWidth('short') === 76, '太短的标签取最小宽度');
check(labelWidth('') === 76, '空标签取最小宽度');

// ---------- 模板下拉框 ----------
// 下拉框里混着一个「动作」项（＋ 新建模板…），所以「重建之后该选中哪一项」
// 不是想当然的：选错了的表现是「改完模板，选中的东西悄悄跳了」。
const { resolveTemplateChoice, FREE, NEW_TEMPLATE } = load('index.html',
    ['resolveTemplateChoice', 'FREE', 'NEW_TEMPLATE'], 'const FREE = ', 'const state = {');

console.log('模板下拉框选中项：');
const choices = ['spring-backend', '修缺陷', NEW_TEMPLATE, FREE];
check(resolveTemplateChoice(choices, '修缺陷', 'spring-backend') === '修缺陷',
    '原来选的那个还在，就留着不动');
check(resolveTemplateChoice(choices, '刚被删掉的', 'spring-backend') === 'spring-backend',
    '原来选的那个被删了，退回第一个模板');
check(resolveTemplateChoice(choices, null, 'spring-backend') === 'spring-backend',
    '从没选过时取第一个模板');
check(resolveTemplateChoice(choices, null, FREE) === FREE,
    '一个模板都没有时落到自由输入');
check(resolveTemplateChoice(choices, NEW_TEMPLATE, 'spring-backend') !== NEW_TEMPLATE,
    '「新建模板」是动作不是模式，不能被留成选择');
check(resolveTemplateChoice([NEW_TEMPLATE, FREE], '修缺陷', FREE) === FREE,
    '模板被删光时，原来选的那个已经不在候选项里了');

// 下拉框的选中值同时存在 DOM 和 state.templateChoice 两处，靠 selectTemplate 对齐。
// 曾经就是多了一个写入点（载入任务草稿时直接改 DOM 的 value），
// 结果这份「上次选了什么」和界面上实际选着的对不上，选中动作项会跳回很久以前的模板。
console.log('模板下拉框的写入点：');
const indexHtml = fs.readFileSync(path.join(WEB, 'index.html'), 'utf8');
check((indexHtml.match(/state\.templateChoice = /g) || []).length === 1,
    '「上次选中的模板」只有一个写入点');
check((indexHtml.match(/\$\('tpl'\)\.value = /g) || []).length === 1,
    '下拉框的 value 也只有一个写入点');

// ---------- 样式约束 ----------
// 把颜色收敛成 token 之后，最容易的退化就是在某个新规则里随手写个 #b91c1c：
// 浅色下看不出来，暗色下那一块就是瞎的。所以拿测试钉住这几条。
console.log('样式约束：');
const styleBlock = indexHtml.slice(indexHtml.indexOf('<style>'), indexHtml.indexOf('</style>'));
const lightTokens = new Set();
const darkTokens = new Set();
const darkAt = styleBlock.indexOf('@media (prefers-color-scheme: dark)');
for (const match of styleBlock.slice(0, darkAt).matchAll(/^\s+(--[\w-]+):/gm)) lightTokens.add(match[1]);
for (const match of styleBlock.slice(darkAt).matchAll(/^\s+(--[\w-]+):/gm)) darkTokens.add(match[1]);

check(lightTokens.size >= 20, '浅色一套 token 定下来了：' + lightTokens.size + ' 个');
check(darkTokens.size > 0 && [...darkTokens].every(name => lightTokens.has(name)),
    '暗色每个 token 在浅色里都有对应（多出来的读不到值）：'
        + [...darkTokens].filter(name => !lightTokens.has(name)).join(','));

const rawColors = styleBlock.split('\n')
    .map((line, index) => ({ text: line.trim(), no: index + 1 }))
    .filter(item => !item.text.startsWith('--') && !item.text.startsWith('/*'))
    .filter(item => /#[0-9a-fA-F]{3,8}\b|rgba?\(/.test(item.text))
    .filter(item => !/#[0-9a-fA-F]{3,8}-/.test(item.text)); // #add-acceptance 是 id 不是颜色
check(rawColors.length === 0, '样式里没有裸色值（token 定义处除外）：' + JSON.stringify(rawColors));

const usedTokens = new Set([...styleBlock.matchAll(/var\((--[\w-]+)\)/g)].map(m => m[1]));
check([...usedTokens].every(name => lightTokens.has(name)),
    '用到的 token 都定义过（拼错名字会静默失效：什么都没变，但就是不生效）：'
        + [...usedTokens].filter(name => !lightTokens.has(name)).join(','));

check(/button:focus-visible\s*\{[^}]*outline/.test(styleBlock), '按钮有键盘焦点样式');
check(/input\[type=text\]:focus[^{]*\{[^}]*box-shadow/.test(styleBlock), '输入框聚焦有看得见的环');
check(/@media \(prefers-color-scheme: dark\)/.test(styleBlock), '有跟随系统的暗色');

// ---------- 目标路径 ----------
// 从目录行上的「＋」和手动输入都走这里：用户粘进来的路径什么样都有，
// 收不干净就会多出一个「src//Foo.java」这样的目标，运行时报找不到。
const { cleanPath, suffixHint } = load('index.html', ['cleanPath', 'suffixHint'],
    'function cleanPath', 'function renderSuffixHint');
console.log('目标路径清洗：');
check(cleanPath('  src/main/java/Foo.java  ') === 'src/main/java/Foo.java', '两边空格去掉');
check(cleanPath('src\\main\\java\\Foo.java') === 'src/main/java/Foo.java', '反斜杠换成斜杠');
check(cleanPath('/src/Foo.java') === 'src/Foo.java', '开头的斜杠去掉（粘进来的绝对路径）');
check(cleanPath('') === '', '空串还是空串，交给调用方去判断');

// ---------- 目标路径的后缀提示 ----------
// 现场：清单里写的是 `…/dto/SummaryDTO`（少打了 .java），白名单按字符串比，
// 模型写对的 `SummaryDTO.java` 反被判越界，它只好写出三个没有后缀的文件。
// 这条提示不拦人，只在输入时拿"同目录已有文件"提醒一句。
console.log('目标路径后缀提示：');
state.files = ['src/main/java/com/demo/Foo.java', 'src/main/java/com/demo/Bar.java'];

const hinted = suffixHint('src/main/java/com/demo/Baz');
check(hinted && hinted.suggestion === 'src/main/java/com/demo/Baz.java',
    '同目录里都是 .java 而这条没后缀：直接建议补上：' + JSON.stringify(hinted));
check(suffixHint('src/main/java/com/demo/Baz.java') === null, '已经写了 .java 就不唠叨');
check(suffixHint('src/main/java/com/other/Baz') === null, '那个目录里没有文件可参照：不说话');

state.files = ['src/impl/A.cpp', 'src/impl/B.py'];
const ambiguous = suffixHint('src/impl/C');
check(ambiguous && ambiguous.candidates.length === 2 && !ambiguous.suggestion,
    '两种扩展名并存：只列候选，不替用户挑：' + JSON.stringify(ambiguous));
check(ambiguous.candidates.join() === 'src/impl/C.cpp,src/impl/C.py', '候选是完整路径，点了就能用');

state.files = ['README.md'];
check(suffixHint('src/nope/Thing') === null, '目录对不上：不说话（不猜）');
check(suffixHint('没有斜杠的名字') === null, '连目录都没有：不说话');

// ---------- 模板源码视图 ----------
// 这段是手写的 YAML 序列化，唯一的用处是「切到源码视图时让你看到当前内容」。
// 它一旦漏字段，你会在源码视图里看到一份不完整的模板，然后把不完整的存回去。
const { templateToYaml } = load('index.html', ['templateToYaml'],
    'function templateToYaml', 'function loadDraft');

console.log('模板转 YAML：');
const template = {
  name: '接口1',
  tags: ['class', 'java'],
  description: '新增一个 REST 接口',
  system: '你是后端工程师。',
  context: [
    { name: 'UserController.java', ref: 'src/main/java/demo/UserController.java', note: '照它的风格写' },
    { name: '订单表结构', text: 'CREATE TABLE orders (id BIGINT)', note: '' },
  ],
};

const yaml = templateToYaml(template);
check(yaml.includes('name: "接口1"'), '名字被写出来');
check(yaml.includes('tags: ["class", "java"]'), '标签被写出来');
check(yaml.includes('description: "新增一个 REST 接口"'), '说明被写出来');
check(yaml.includes('system: |'), '多行文本用块标量');
check(!yaml.includes('user:'), '模板不装需求：不写 user');
check(!yaml.includes('fields:'), '模板不声明输入字段');
check(yaml.includes('  - ref: "src/main/java/demo/UserController.java"'), '上下文引用被写出来');
check(yaml.includes('  - name: "订单表结构"'), '上下文的内联条目被写出来');
check(yaml.includes('CREATE TABLE orders'), '内联内容不丢');
check(typeof templateToYaml({}) === 'string', '空对象也能转，不抛异常');

// ---------- 模板自带的上下文（界面必须说出来） ----------
// 模板的 context 是「选它时自动加进依赖清单」的东西，而它只写在 YAML 里。
// 不点破的话，依赖清单里会凭空多出两条用户没加过的条目——他既看不出是谁加的，
// 也不知道能不能删（能：取消勾选就只是这一次不用）。
const { templateContextNote } = load('index.html', ['templateContextNote'],
    'function templateContextNote(template) {', '\n\n/**\n * 模板切换时只更新');

console.log('模板自带的上下文：');
check(templateContextNote({ name: 't', context: [] }) === '' && templateContextNote(null) === '',
    '不带上下文时一个字都不说（不留一条空提示）');
const ctxNote = templateContextNote({
  context: [{ name: '订单表结构' }, { name: '接口约定' }],
});
check(ctxNote.includes('2 条') && ctxNote.includes('订单表结构') && ctxNote.includes('接口约定'),
    '带上下文时把条数和名字都写出来：' + ctxNote);
check(ctxNote.includes('上下文依赖'), '而且说清它会落到下面那份清单里');

// ---------- 项目根下的明文密钥文件 ----------
// 不引依赖就没有跨平台的密钥库，所以这件事只能停在「提示」上。既然是提示，
// 那它必须点名是哪几个文件、并且给出该放哪儿——否则等于没说。
const { secretsWarning } = load('index.html', ['secretsWarning'],
    'function secretsWarning(files) {', '\n\nfunction renderSecrets() {');

console.log('明文密钥文件的提醒：');
check(secretsWarning([]) === '' && secretsWarning(null) === '' && secretsWarning(undefined) === '',
    '没有这种文件时一个字都不说（不留一块空警示）');
const keyNote = secretsWarning(['key.env', '.env']);
check(keyNote.includes('key.env') && keyNote.includes('.env'), '点名是哪几个文件：' + keyNote);
check(keyNote.includes('.specflow/local.env') && keyNote.includes('.gitignore'),
    '给出该放哪儿：密钥写进 .specflow/local.env（那份已经在 .gitignore 里）');
check(keyNote.includes('别提交'), '最后一句是「别提交」：这条提示的全部用处就在这里');

// ---------- 待处置的改动 ----------
// 这块面板最要紧的判断只有一个：canAccept 真和假时，两个按钮的主次与文案正好相反。
// 弄反了的后果不对称——该恢复原样的那一次，用户顺手点了主按钮，
// 于是把一份没校验过的改动留在了磁盘上。而两块面板看起来又几乎一样，
// 肉眼过一遍很容易漏，所以把它渲染成字符串，直接对着字符串断言。
const { pendingPanelHtml, diffLineClass, pendingActionPath } = load('index.html',
    ['pendingPanelHtml', 'diffLineClass', 'pendingActionPath'],
    // 从施工单那段开始切：待处置面板现在要按施工单分组，changeGroups 在那儿
    '// ---------- 施工单 ----------', 'async function refreshPending');

console.log('差异行的分类：');
check(diffLineClass('+class New {}') === 'add', '+ 开头是新增行');
check(diffLineClass('-int a = 1;') === 'del', '- 开头是删除行');
check(diffLineClass(' int a = 1;') === '', '空格开头是上下文，不标底色');
check(diffLineClass('') === '', '空行不标底色');

console.log('处置动作打到哪个接口：');
check(pendingActionPath('accept') === '/api/accept', '「保留」打 /api/accept');
check(pendingActionPath('rollback') === '/api/rollback', '「撤回」打 /api/rollback');
check(pendingActionPath('accept') !== pendingActionPath('rollback'),
    '两个动作不会落到同一个接口上（落同一个就是「点保留却撤回了」）');

console.log('待处置的改动面板：');
const pendingFile = (path, created, diff) => ({ path, created, diff });
const verified = {
  present: true,
  id: '20260214-103012-451.pending',
  canAccept: true,
  summary: '2 个文件：新增 1、修改 1',
  files: [
    pendingFile('src/main/java/demo/New.java', true, '+class New {}'),
    pendingFile('src/main/java/demo/Foo.java', false, '-int a = 1;\n+int a = 2;'),
  ],
};

check(pendingPanelHtml({ present: false, id: null, canAccept: false, summary: '没有待处置的改动', files: [] }) === '',
    '没有待处置的改动时一个字符都不渲染（#pending 靠 :empty 收掉）');

const okHtml = pendingPanelHtml(verified);
check(okHtml.includes('2 个文件：新增 1、修改 1'), 'summary 摆出来了');
check(okHtml.includes('<button type="button" data-act="accept">保留改动</button>'),
    '校验过时主按钮是「保留改动」，不带 ghost（它就是主路径）');
check(okHtml.includes('<button type="button" class="ghost" data-act="rollback">撤回改动</button>'),
    '次按钮是「撤回改动」');
check(okHtml.indexOf('data-act="accept"') < okHtml.indexOf('data-act="rollback"'),
    '主按钮排在次按钮前面（两个按钮长得几乎一样，顺序就是唯一的提示）');
check(!okHtml.includes('class="pending-alert"'), '校验过的这一份不该挂「没校验」的警示');
check(okHtml.includes('才能开始新的一次运行'), '面板里写明「运行」为什么按不动');

check(okHtml.includes('新建 src/main/java/demo/New.java'), '新建的文件标成「新建」');
check(okHtml.includes('修改 src/main/java/demo/Foo.java'), '改过的文件标成「修改」');
check(okHtml.includes('<div class="diff">'), 'diff 复用运行结果区那套 .diff');
check(okHtml.includes('<div class="add">+class New {}</div>'), '新增行渲染成 .add');
check(okHtml.includes('<div class="del">-int a = 1;</div>'), '删除行渲染成 .del');
check(okHtml.includes('<div class="add">+int a = 2;</div>'), '同一段 diff 里增删混着也不会串类');

const unverified = pendingPanelHtml({ ...verified, canAccept: false, summary: '1 个文件：修改 1' });
check(unverified.includes('<button type="button" data-act="rollback">恢复到运行前</button>'),
    '没校验过时主按钮是「恢复到运行前」');
check(unverified.includes('data-act="accept">保留当前内容</button>'), '次按钮是「保留当前内容」');
check(unverified.indexOf('data-act="rollback"') < unverified.indexOf('data-act="accept"'),
    '没校验过时主次正好反过来');
check(!unverified.includes('保留改动'), '这一份里不该出现「保留改动」这个说法（它和上面那份不是一回事）');
check(unverified.includes('data-act="accept">保留当前内容</button><span class="pending-alert">'),
    '「没经过校验」这句就贴在这个次按钮旁边');
check(unverified.includes('没有经过校验'), '警示必须写明没验过');

const noFiles = pendingPanelHtml({ present: true, id: 'x.pending', canAccept: true, summary: '磁盘上没留下改动', files: [] });
check(noFiles.includes('磁盘上没留下改动') && noFiles.includes('data-act="accept"'),
    'present 为真但 files 为空时照样把两个按钮摆出来，不是一片空白');
check(!noFiles.includes('<details'), '没有文件明细就不画文件块');

console.log('待处置面板的收起与展开：');
const many = { ...verified, files: [1, 2, 3, 4].map(i => pendingFile('src/F' + i + '.java', false, '+x')) };
check(pendingPanelHtml(verified).includes('data-open="true"'), '改动少时默认展开');
check(pendingPanelHtml(many).includes('data-open="false"'), '改动多时默认收起');
check(pendingPanelHtml(many).includes('<div class="pending-body" hidden>'), '收起时正文整块藏起来');
check(pendingPanelHtml(verified).includes('<details class="change" open>'), '改动少时每个文件的 diff 直接摊开');
check(!pendingPanelHtml(many).includes('<details class="change" open>'), '改动多时只给一行摘要');
check(pendingPanelHtml(many, true).includes('data-open="true"')
    && pendingPanelHtml(many, true).includes('>收起</button>'), '用户自己展开过就听用户的，不按文件数猜');
check(pendingPanelHtml(verified).includes('>收起</button>')
    && pendingPanelHtml(many).includes('>展开</button>'), '收起/展开按钮的文案跟着状态走');

console.log('待处置面板的转义：');
const nasty = pendingPanelHtml({
  present: true, id: 'x.pending', canAccept: true, summary: '1 个文件',
  files: [pendingFile('src/<b>a</b>.java', false, '+<img src=x onerror=1>')],
});
check(nasty.includes('src/&lt;b&gt;a&lt;/b&gt;.java') && !nasty.includes('<b>a</b>'),
    '路径里的尖括号被转义（路径是模型写出来的）');
check(nasty.includes('&lt;img src=x onerror=1&gt;'), 'diff 正文里的尖括号也被转义');

// ---------- 日志行：轮次只说一遍 ----------
// 实测渲染成「第 1 轮第 1 步第 1 轮：调用模型…」：轮级那句文案自己带着轮号，
// 而事件/留档里另有一个 round 字段，两处都画就重了。
// 判据是纯函数，所以在这里钉住；画成什么样由浏览器链盯着。
const { saysRound } = load('index.html', ['saysRound'],
    'function saysRound', 'function appendLog');

console.log('日志行：轮次只说一遍：');
check(saysRound('第 2 轮：调用模型…', 2) === true,
    '文案自己写着「第 2 轮」：不再挂轮次标签（挂了就是「第 2 轮第 2 轮：调用模型…」）');
check(saysRound('编译校验：通过', 2) === false,
    '文案里没提轮次：标签照挂（它自己不说是第几轮，标签是唯一的轮次信息）');
check(saysRound('已写入 2 个文件：a/A.java', 2) === false,
    '数字对得上也不算提过轮次：要比的是「第 2 轮」整句，不是那个 2');
check(saysRound('第 12 轮：调用模型…', 2) === false,
    '第 12 轮不是第 2 轮：不能因为里面有个 2 就不挂标签');
check(saysRound('第 1 轮第 1 步：调用模型…', 1) === true, '文案里出现轮号就认（不管它前后还有什么）');
check(saysRound(undefined, 1) === false && saysRound(null, 1) === false && saysRound('', 1) === false,
    '没有文案时也不炸，而且照样挂标签');

// ---------- 挂起的运行 ----------
// 这块面板最要紧的一条：接着跑**不能**把上下文重发一遍（那份钱用户已经付过了），
// 所以两个动作必须打到两个不同的请求上；而「直接继续」还要带上 force，
// 否则引擎会以为用户补过料，回一句软话，于是它可能又停下来要东西。
const { suspendedPanelHtml, suspendedActionPath } = load('index.html',
    ['suspendedPanelHtml', 'suspendedActionPath'],
    // 从施工单那段开始切：escapeHtml / diffLineClass 都在那儿，挂起面板要用它们
    '// ---------- 施工单 ----------', 'async function refreshSuspended');

console.log('挂起动作打到哪个接口：');
check(suspendedActionPath('resume') === '/api/continue', '「补充后继续」打 /api/continue');
check(suspendedActionPath('force') === '/api/continue?force=1', '「直接继续」多带一个 force=1');
check(suspendedActionPath('resume') !== suspendedActionPath('force'),
    '两个动作不落同一个请求上（落同一个就是「点了直接继续，却当成补过料」）');

console.log('挂起面板：');
check(suspendedPanelHtml({ present: false, runId: null, need: '', attempts: 0, repeated: 0 }) === '',
    '没有挂起时一个字符都不渲染');

const waiting = suspendedPanelHtml({
  present: true, runId: 'r1', attempts: 2, repeated: 1,
  need: 'NEED_CONTEXT: 我要给 OrderService 加查询\n需要: OrderMapper.java 的现有写法\n为什么: 猜错整包返工',
});
check(waiting.includes('它在等信息'), '标题说清它在等什么');
check(waiting.includes('NEED_CONTEXT: 我要给 OrderService 加查询'), '它那句话原样摆出来（用户要看的正是这个）');
check(waiting.includes('<div class="pending-need">'), '那句话按它自己的分行显示');
check(waiting.includes('第 2 轮停下'), '说清是第几轮停下的');
check(waiting.includes('data-act="resume">补充后继续</button>'), '主按钮是「补充后继续」');
check(waiting.includes('data-act="force">直接继续运行</button>'), '次按钮是「直接继续运行」');
check(waiting.indexOf('data-act="resume"') < waiting.indexOf('data-act="force"'), '主按钮排在前面');
check(waiting.includes('class="pending suspended"'), '挂起面板带自己的修饰类（底色和待处置那份分得开）');
check(!waiting.includes('class="pending-alert"'), '第一次说缺时不劝退，只给两条路');

const chatty = suspendedPanelHtml({ present: true, runId: 'r', need: 'x', attempts: 1, repeated: 2 });
check(chatty.includes('连着 2 次说信息不足'), '连着两次以上开始劝人补料或改需求');

const rude = suspendedPanelHtml({ present: true, runId: 'r', need: '<b>x</b>', attempts: 1, repeated: 1 });
check(rude.includes('&lt;b&gt;x&lt;/b&gt;') && !rude.includes('<b>x</b>'),
    '它那句话也转义（那段文字同样是模型写出来的）');

check(suspendedPanelHtml({ present: true, runId: 'r', need: '', attempts: 1, repeated: 1 })
        .includes('（它没写清要什么）'),
    '它没写内容时给一句兜底，而不是留一块空白');

// ---------- 施工单 ----------
// 施工单这一段有三件事最要紧，而且都是「弄错了不容易被发现」的那种：
// 每步的状态画成什么、用户在界面上改的那份有没有跟着发回去、
// 点「运行」被拦下来时说的到底是哪一步。全是纯逻辑，直接喂输入看一眼。
const step = load('index.html',
    ['STEP_STATES', 'STEP_SOURCE_LABEL', 'stepStateMeta', 'stepState',
     'normalizeSteps', 'moveStep', 'removeStep', 'appendStep', 'planForRun', 'roundBudget',
     'stepFindingLabel', 'stepAuditFindings', 'blockedRunMessage', 'changeGroups', 'fileKey'],
    '// ---------- 施工单 ----------', 'async function refreshPending');

console.log('施工单：步态 → class 与文案：');
check(step.stepStateMeta('RUNNING').join('|') === 'running|进行中', '进行中');
check(step.stepStateMeta('SUCCESS').join('|') === 'success|成功', '成功');
check(step.stepStateMeta('INTERMEDIATE').join('|') === 'intermediate|中间态未通过',
    '中间态单说一个词：它不是「成功」，也不是「失败」');
check(step.stepStateMeta('FAILED').join('|') === 'failed|失败', '失败');
check(step.stepStateMeta('PENDING').join('|') === 'pending|未做', '没轮到它就是「未做」');
check(step.stepStateMeta('谁也没见过的值').join('|') === 'pending|未做',
    '认不出的状态按「未做」画，而不是留一个没颜色的空白标签：'
        + step.stepStateMeta('谁也没见过的值').join('|'));
check(step.STEP_STATES.PENDING[1] !== step.STEP_STATES.RUNNING[1]
    && step.STEP_STATES.SUCCESS[1] !== step.STEP_STATES.INTERMEDIATE[1],
    '五档文案两两不同（同一个词出现在两档上，标签就等于没写）');
check(Object.keys(step.STEP_SOURCE_LABEL).join(',') === 'APPROVED,GENERATED,RESUMED,SINGLE',
    '施工单的四个来源都有对应的说法（续跑那份是从留档里拿的，不能和「现生成」混成一句）');
check(step.STEP_SOURCE_LABEL.RESUMED !== step.STEP_SOURCE_LABEL.GENERATED
    && step.STEP_SOURCE_LABEL.RESUMED !== step.STEP_SOURCE_LABEL.APPROVED,
    '续跑那份的说法和另外两个都不一样：它既不是人确认的，也不是这次现生成的');
// 事件里的步态就是这几个名字：Java 那边发的是 StepState 的枚举名加一个 RUNNING，
// 界面多认或少认一档都会让某一步静默停在别的状态上
check(Object.keys(step.STEP_STATES).join(',') === 'PENDING,RUNNING,SUCCESS,INTERMEDIATE,FAILED',
    '步态档位与引擎发的名字一一对应：' + Object.keys(step.STEP_STATES).join(','));

console.log('施工单：某一步此刻的状态：');
state.stepStates = new Map();
state.stepDetail = null;
check(step.stepState(7) === 'PENDING', '界面里没记过这一步，它就是「未做」');
state.stepStates = new Map([[7, 'FAILED']]);
check(step.stepState(7) === 'FAILED', '记过之后按记的来');
check(step.stepState(8) === 'PENDING', '别的步号不受影响');
state.stepStates = new Map();

console.log('施工单：步态只认事件上的结构化字段：');
// 以前这里测的是一个靠中文文案推步态的函数（「：成功」结尾就是成功）。那套机制的问题不是
// 推得准不准，而是「错位时没有任何东西会红」：Java 里改一句措辞，界面静默停在旧步态上，
// 两边的测试都还是绿的。现在步态由引擎写在事件的 stepState 字段上，界面只读它。
// 行为（读字段之后画成什么）由浏览器链那条桩出来的事件流验；这里钉住的是那段判断本身：
// 它只许看字段，一个字都不许去看文案。
const pageSource = fs.readFileSync(path.join(WEB, 'index.html'), 'utf8');
const judgeAt = pageSource.indexOf('function applyStepEvent');
const judge = pageSource.slice(judgeAt, pageSource.indexOf('\n}\n', judgeAt));
check(judgeAt > 0 && judge.includes('event.stepState'), '步态判定读的是事件上的 stepState 字段');
check(!judge.includes('event.text'),
    '步态判定不看 text（一去看文案，措辞一改界面就静默错位，而且没有测试会红）');
check(!/includes\(|endsWith\(|indexOf\(/.test(judge),
    '步态判定里没有字符串比对：' + judge.split('\n').filter(line => /includes\(|endsWith\(/.test(line)).join(' '));
check(!pageSource.includes('stepEventState'),
    '靠中文文案猜步态的那个函数已经删掉（不留兼容壳：留着它就是留一条会静默错位的路）');

console.log('施工单：编辑（改、增删、上下移、勾中间态）：');
const rawSteps = [
  { index: 1, goal: '  加接口  ', files: [' a/A.java ', ''], check: '能编译', intermediate: false },
  { index: 2, goal: '加实现', files: ['a/B.java'], check: '', intermediate: true },
  { index: 0, goal: '改调用点', files: [], check: '', intermediate: false },
];
const normalized = step.normalizeSteps(rawSteps);
check(normalized.map(s => s.index).join(',') === '1,2,3', '步号按顺序重排成 1..N：'
    + normalized.map(s => s.index).join(','));
check(normalized[0].goal === '加接口', 'goal 收掉首尾空格');
check(normalized[0].files.join(',') === 'a/A.java', '文件里的空白项被丢掉');
check(normalized[2].index === 3, '引擎没给步号（≤0）时按位置补一个');
check(normalized[1].intermediate === true && normalized[2].intermediate === false,
    '中间态原样带过去');

const keptIndex = step.normalizeSteps([{ index: 4, goal: 'x' }, { index: 9, goal: 'y' }], false);
check(keptIndex.map(s => s.index).join(',') === '4,9',
    '来自事件/留档的那一份不重排步号：事件里的 step 就是它，重排会让两者对不上号');

const twoSteps = step.normalizeSteps([{ goal: '一' }, { goal: '二' }, { goal: '三' }]);
check(step.moveStep(twoSteps, 0, -1).map(s => s.goal).join(',') === '一,二,三',
    '已经在最上面了：上移不生效（不是把它扔到末尾）');
check(step.moveStep(twoSteps, 2, 1).map(s => s.goal).join(',') === '一,二,三', '已经在最下面了：下移不生效');
const movedUp = step.moveStep(twoSteps, 2, -1);
check(movedUp.map(s => s.goal).join(',') === '一,三,二', '下移一步：顺序真的换了');
check(movedUp.map(s => s.index).join(',') === '1,2,3',
    '换完之后步号重新排：上移下移之后「第 3 步」指的仍然是第 3 个位置');
check(twoSteps.map(s => s.goal).join(',') === '一,二,三', '编辑函数不改传进来的那一份（纯函数）');

const removed = step.removeStep(twoSteps, 0);
check(removed.map(s => s.goal).join(',') === '二,三' && removed[0].index === 1, '删掉一步，步号跟着重排');
check(step.removeStep(step.normalizeSteps([{ goal: '只剩这一步' }]), 0).length === 1,
    '只剩一步时删不掉：空施工单在引擎那边等于「没有施工单」，会静默退化成单步');
const appended = step.appendStep(twoSteps);
check(appended.length === 4 && appended[3].index === 4 && appended[3].goal === ''
    && appended[3].intermediate === false && appended[3].files.length === 0,
    '加一步：加在末尾，步号接上，默认不标中间态');

console.log('施工单：编辑之后怎么发回去：');
const plan = {
  summary: '加一个查询接口', flowchart: 'flowchart TD\n A-->B', missing: [],
  steps: [{ index: 1, goal: '旧的第一步' }],
};
const edited = [{ goal: '改过的第一步', files: ['a/A.java'], check: '能编译', intermediate: true },
                { goal: '刚加的一步' }];
const sentPlan = step.planForRun(plan, edited);
check(sentPlan.steps.length === 2 && sentPlan.steps[0].goal === '改过的第一步',
    '发回去的是界面上改过的那一份（发旧的等于用户白改）');
check(sentPlan.steps.map(s => s.index).join(',') === '1,2', '步号重排之后再发（引擎按列表顺序走）');
check(sentPlan.steps[0].files.join() === 'a/A.java' && sentPlan.steps[0].intermediate === true
    && sentPlan.steps[0].check === '能编译',
    '四个字段一个不少：少一个，引擎那边这一步就不完整了');
check(sentPlan.summary === plan.summary && sentPlan.flowchart === plan.flowchart,
    '方案里别的东西原样带着（改的是施工单，不是摘要和流程图）');
check(sentPlan.steps !== plan.steps, '不是直接把原始数组塞进去（改完还能改回来）');
const untouched = step.planForRun(plan, []);
check(untouched.steps && untouched.steps.length === 1 && untouched.steps[0].goal === '旧的第一步',
    '施工单空着时原样返回：steps: [] 和「没这个字段」在引擎那边是同一件事');
check(step.planForRun(null, twoSteps) === null, '压根没有方案时返回 null，不凭空造一份');

console.log('施工单：总轮次预算：');
check(step.roundBudget('') === 0 && step.roundBudget('   ') === 0,
    '留空是「自动」（0），不是「一轮都不给」');
check(step.roundBudget('0') === 0, '写 0 也是自动（后端约定的「没配」值）');
check(step.roundBudget('12') === 12, '写了数字就用它');
check(step.roundBudget('-5') === 0, '负数是没意义的输入，退回自动而不是发出一个负数');
check(step.roundBudget('abc') === 0, '打了汉字也是自动：不静默变成「一次都不给」');
check(step.roundBudget('999') === 60, '上限收在 60：'
    + step.roundBudget('999'));
check(step.roundBudget('3.7') === 3, '小数取整，不把 3.7 当字符串发出去');
check(step.roundBudget(undefined) === 0, '连输入框都没有时也不炸');

console.log('施工单：哪一步有问题：');
check(step.stepFindingLabel(0) === '整份施工单', 'step=0 说的是整份单子（步数超过上限这一类）');
check(step.stepFindingLabel(3) === '第 3 步', 'step>0 说的是那一步');
check(step.stepAuditFindings({ findings: [{ step: 1, reason: 'r' }], hints: ['h'] }).length === 1,
    '只取 findings');
check(step.stepAuditFindings({ findings: [], hints: ['中间态超过三分之一'] }).length === 0,
    'hints 不是 findings：它只说事，绝不拦人');
check(step.stepAuditFindings({ hints: ['空施工单会退化成单步'] }).length === 0,
    '一份纯 hints 的审查结果，findings 仍然是空的（拿它去拦人就是「提示挡用户」）');
check(step.stepAuditFindings(null).length === 0 && step.stepAuditFindings(undefined).length === 0,
    '没有施工单审查结果时是空的，不是 undefined');

console.log('施工单：点运行被拦下来的那句话：');
check(step.blockedRunMessage([], []) === '', '两处都没有问题时没有话说');
const onlyPlan = step.blockedRunMessage([{ path: 'a/A.java', reason: '清单外' }], []);
check(onlyPlan.includes('这份方案有 1 处执行不了') && onlyPlan.includes('a/A.java')
    && onlyPlan.includes('仍然继续'), '只有方案的问题时，还是原来那句话：' + onlyPlan);
check(!onlyPlan.includes('施工单'), '没有施工单问题时不许提施工单（否则用户去改错东西）');
const onlySteps = step.blockedRunMessage([], [{ step: 3, reason: '第 3 步要动 a/X.java：它不在清单里' }]);
check(onlySteps.includes('这份施工单有 1 处执行不了') && onlySteps.includes('第 3 步：'),
    '施工单的问题说清是哪一步：' + onlySteps);
check(onlySteps.includes('仍然继续'), '出路也写了：点「我知道，仍然继续」就放行');
const both = step.blockedRunMessage([{ path: 'a/A.java', reason: '清单外' }],
    [{ step: 0, reason: '施工单有 8 步，超过上限 7 步' }, { step: 5, reason: '最后一步标了中间态' }]);
check(both.includes('这份方案有 1 处执行不了') && both.includes('这份施工单有 2 处执行不了'),
    '两处问题一次说全，而不是先说一处、点完再冒出另一处');
check(both.includes('整份施工单：') && both.includes('第 5 步：'),
    '整份的问题和某一步的问题分得开：' + both);
check(both.split('执行不了').length === 3, '两段分开写，不是糊成一句');

console.log('施工单：改动按步分组：');
const files = ['a/A.java', 'a/B.java', 'a/C.java'];
const twoStepPlan = step.normalizeSteps([
  { index: 1, goal: '加接口', files: ['a/A.java'] },
  { index: 2, goal: '加实现', files: ['a/B.java'] },
]);
const changes = files.map(path => ({ path, created: false, bytes: 1, diff: '+x' }));
/** 把分组结果压成一行，好读也好比：`第1步:a/A.java | 无:a/C.java`。 */
const groupLine = groups => groups.map(group =>
    (group.step ? '第' + group.step.index + '步' : '无') + ':'
    + group.changes.map(change => change.path).join('+')).join(' | ');

const byFiles = step.changeGroups(changes, null, twoStepPlan);
check(groupLine(byFiles) === '第1步:a/A.java | 第2步:a/B.java | 无:a/C.java',
    '按施工单里「这一步要动哪些文件」归位；对不上任何一步的单独列在最后，而不是被丢掉：'
        + groupLine(byFiles));
check(byFiles[1].step.goal === '加实现', '每一组还带着「这一步做什么」，不是只有一个号');
check(step.changeGroups(changes, null, twoStepPlan.slice(0, 1)).length === 1,
    '只有一步时不分组（单步执行就是老样子）');
check(step.changeGroups(changes, null, []).length === 1, '没有施工单时不分组');
const countChanges = groups => groups.reduce((sum, group) => sum + group.changes.length, 0);
check(countChanges(byFiles) === 3, '分组不吞改动：三个文件一个不少（'
    + countChanges(byFiles) + '）');
check(step.changeGroups([{ path: 'z/Z.java' }], null, twoStepPlan)[0].step === null,
    '一个文件都对不上时别硬分组（分错了比不分更坏）');
check(step.changeGroups([{ path: 'a\\A.java' }], null, twoStepPlan)[0].changes.length === 1,
    '反斜杠的路径也能对上（Windows 上两边写法都可能出现）');

const detail = [
  { index: 1, goal: '加接口', state: 'SUCCESS', rounds: 2, changes: [{ path: 'a/A.java' }] },
  { index: 2, goal: '加实现', state: 'INTERMEDIATE', rounds: 3, changes: [{ path: 'a/B.java' }] },
];
const byDetail = step.changeGroups(changes, detail, twoStepPlan);
check(groupLine(byDetail) === '第1步:a/A.java | 第2步:a/B.java | 无:a/C.java',
    '留档在的时候以它为准，连顺序都按留档来：' + groupLine(byDetail));
check(byDetail[0].step.state === 'SUCCESS' && byDetail[0].step.rounds === 2,
    '状态和轮次都是留档里记下来的，不是界面猜的');
check(byDetail[1].step.state === 'INTERMEDIATE', '第 2 步的中间态也照着留档画');
check(step.changeGroups([], detail, twoStepPlan).length === 2,
    '一步都没改文件时也把那两步列出来（「这一步没动东西」本身是信息）');
check(step.changeGroups(changes, detail.slice(0, 1), twoStepPlan)[0].step === null,
    '留档只有一步时不当成分步的证据（单步执行走老样子）');
check(step.fileKey(' a\\b/C.java ') === 'a/b/C.java', '路径归一：反斜杠、两头空格都收干净');
check(step.fileKey(null) === '' && step.fileKey(undefined) === '', '没有路径时给空串，不炸');

console.log('施工单：待处置面板按步分组：');
// 面板的按步依据只能是**每步留档**。快照里只有「运行前 vs 现在」这一份累计 diff，
// 拿它配到步上，第 1 步就会顶着一份含第 2 步改动的 diff——实测就是这个现象。
const pendingTwo = {
  present: true, id: 'x.pending', canAccept: true, summary: '2 个文件：新增 1、修改 1',
  files: [pendingFile('a/A.java', false, '+x\n+第2步补的一行'), pendingFile('a/B.java', true, '+y')],
};
const detailTwo = [
  { index: 1, goal: '加接口', state: 'SUCCESS', rounds: 2,
    changes: [{ path: 'a/A.java', created: false, diff: '+x' }] },
  { index: 2, goal: '加实现', state: 'INTERMEDIATE', rounds: 3,
    changes: [{ path: 'a/B.java', created: true, diff: '+y' },
              { path: 'a/A.java', created: false, diff: '+第2步补的一行' }] },
];
const groupedPending = pendingPanelHtml(pendingTwo, null, detailTwo);
check(groupedPending.includes('<div class="step-group">'), '给了每步留档就按步分组');
check(groupedPending.includes('第 1 步：加接口') && groupedPending.includes('第 2 步：加实现'),
    '每一组写明是哪一步、做什么：' + groupedPending.slice(0, 200));
check(groupedPending.indexOf('修改 a/A.java') < groupedPending.indexOf('第 2 步'),
    'a/A.java 落在第 1 步那一组里');
check(groupedPending.split('修改 a/A.java').length - 1 === 2,
    '同一个文件被两步都改过时，两组里各出现一次（实测那个 Calculator.java 就是这个形状）');
// 这条就是那个 bug：分组没错，但每组的 diff 是「到目前为止的累计」
const firstGroup = groupedPending.slice(0, groupedPending.indexOf('第 2 步'));
check(firstGroup.includes('<div class="add">+x</div>'),
    '第 1 步那组里是它自己写的那一行');
check(!firstGroup.includes('第2步补的一行'),
    '第 1 步那组里不含第 2 步的改动（累计 diff 会把它带进来）：' + firstGroup);
check(groupedPending.includes('<div class="add">+y</div>')
    && groupedPending.includes('<div class="add">+第2步补的一行</div>'),
    '第 2 步自己的两处改动也在（每组各说各的，不是只剩一组）');
check(!pendingPanelHtml(pendingTwo, null, []).includes('step-group')
    && !pendingPanelHtml(pendingTwo, null, null).includes('step-group'),
    '没有每步留档时一块都不分组：宁可一个文件一块，也不给改动乱安步号');
check(pendingPanelHtml(pendingTwo).indexOf('<div class="step-group">') < 0,
    '连留档都不给时也是老样子（链 20 盯着两个文件各占一行）');
check(pendingPanelHtml(pendingTwo, null, detailTwo).includes(
    '<button type="button" data-act="accept">保留改动</button>'),
    '分组之后那两个按钮还在（分组只动正文）');
check(pendingPanelHtml(pendingTwo, null, detailTwo).includes('<div class="add">+x</div>'),
    '分组之后 diff 的底色也还在');
// 留档里一步都没改文件时，那一步也得露出来（「这一步没动东西」本身是信息）
const emptyStepDetail = [
  { index: 1, goal: '加接口', state: 'SUCCESS', rounds: 1, changes: [{ path: 'a/A.java', diff: '+x' }] },
  { index: 2, goal: '加实现', state: 'SUCCESS', rounds: 1, changes: [] },
];
check(pendingPanelHtml(pendingTwo, null, emptyStepDetail).includes('第 2 步：加实现'),
    '没改文件的那一步也在（它只是没有文件块）');
const nastyGoal = pendingPanelHtml(pendingTwo, null,
    [{ index: 1, goal: '<b>坏</b>', changes: [{ path: 'a/A.java', diff: '+x' }] },
     { index: 2, goal: 'x', changes: [{ path: 'a/B.java', diff: '+y' }] }]);
check(nastyGoal.includes('&lt;b&gt;坏&lt;/b&gt;') && !nastyGoal.includes('<b>坏</b>'),
    '分组标题里的 goal 也是模型写出来的，照样转义');

console.log('施工单：样式（状态靠自己那一档的 token 上色）：');
// 每一档的底色与字色都点名到具体 token：写成 var(--error) 这种前缀相同、
// 但不是同一个 token 的退化，必须能被抓出来（否则状态之间就分不开了）
for (const [name, bg, text] of [['pending', 'chip', 'muted'], ['running', 'accent-weak', 'accent'],
                                ['success', 'ok', 'ok-text'], ['intermediate', 'warn', 'warn-text'],
                                ['failed', 'error', 'error-text']]) {
  check(new RegExp('^\\s*\\.step-state\\.' + name + ' \\{[^}]*background: var\\(--' + bg
      + '\\)[^}]*color: var\\(--' + text + '\\)', 'm').test(styleBlock),
  '「' + name + '」这一档：底色 ' + bg + '、字色 ' + text + '，都是它自己那组 token');
}
check(/\.step\[data-state=running\]\s*\{[^}]*var\(--accent-line\)/.test(styleBlock),
    '整行的边框也跟着状态走，不是只有那个小标签变了');
check(/\.step-state\s*\{[^}]*border: 1px solid/.test(styleBlock)
    && /\.step-goal[^{]*\{[^}]*font-size/.test(styleBlock),
    '状态标签和输入框都有自己的样式，不是浏览器默认长相');

// ---------- 施工单：运行结束后的校正 ----------
// 运行详情是按记录 id 取的，而 /api/run 回的是运行标识（UUID），两者对不上号——
// 只能靠「开跑前那条最新的是谁」认出刚跑完的那一条。这里认错的表现很具体：
// 界面把**上一次**运行的步态画到了这一次头上，而且看不出来是错的。
const { newRecordSince } = load('index.html', ['newRecordSince'],
    '// ---------- 施工单 ----------', 'async function refreshPending');

console.log('施工单：跑完认哪条留档：');
// 认错了的表现是「把上一次运行的步态画到这一次头上」，所以这里连着认不出来的
// 三种情况一起钉死。取 id 写成这样是为了让「认不出来」变成一条干净的断言，
// 而不是把整轮测试抛掉
const since = (baseline, runs) => {
  const hit = newRecordSince(baseline, runs);
  return hit && hit.id ? hit.id : null;
};
check(since('a', [{ id: 'b' }, { id: 'a' }]) === 'b',
    '列表按时间倒序，第一条不是开跑前那条，那它就是这一次的');
check(since('a', [{ id: 'a' }, { id: 'z' }]) === null,
    '最新的还是开跑前那一条：这次没留下记录，没什么可校正的');
check(since('', [{ id: 'b' }]) === 'b',
    '跑之前一条记录都没有（空串）→ 这条新的就是本次的（空串和 null 不是一回事）');
check(since(null, [{ id: 'b' }]) === null,
    '基线不知道（请求失败了）→ 宁可不校正，也不拿一条老记录去改界面');
check(since('a', []) === null && since('a', undefined) === null,
    '一条记录都没有时什么都不做');

// ---------- 状态表 ----------
// 界面有**两张**按状态查行的表：结果面板画这一次（STATUS_TEXT），历史列表画留档里的每一次
// （STATUS_LABEL）。两张都是「拿枚举名去查一行」，少一行不会报错——它会画成红底 + 英文枚举名，
// 看起来像系统坏了，而不像一句能读懂的话（CLI 撞门禁留下的 PENDING_DECISION 就是这么露出来的）。
// 所以这里照着 Java 的枚举核一遍，而且不手抄枚举名：抄一份就等于又开了第二个会漂的地方。
console.log('状态表：AgentResult.Status 每一项都要有说法：');
const agentResultSource = fs.readFileSync(path.join(__dirname, '..', '..', 'main', 'java',
    'com', 'specflow', 'agent', 'AgentResult.java'), 'utf8');
const enumAt = agentResultSource.indexOf('public enum Status {');
const statuses = agentResultSource
    .slice(enumAt, agentResultSource.indexOf('public static AgentResult success', enumAt))
    .split('\n').map(line => line.trim())
    .filter(line => /^[A-Z][A-Z_]*[,]?$/.test(line))
    .map(line => line.replace(',', ''));
check(statuses.length === 9, '从 AgentResult.Status 里读出的终态共 9 个：' + statuses.join(','));
const historyTable = load('index.html', ['STATUS_LABEL'], 'const STATUS_LABEL', 'async function openHistory');
const resultTable = load('index.html', ['STATUS_TEXT'], 'const STATUS_TEXT', 'function renderResult');
const notInHistory = statuses.filter(name => !historyTable.STATUS_LABEL[name]);
check(notInHistory.length === 0, '历史列表那张表一项不缺，少了就是红底 + 英文枚举名：'
    + (notInHistory.join('、') || '一项不缺'));
const notInResult = statuses.filter(name => !resultTable.STATUS_TEXT[name]);
check(notInResult.length === 0, '结果面板那张表一项不缺：'
    + (notInResult.join('、') || '一项不缺'));
// 这一档单独再钉一眼：它是「上一次的改动还没处置」那条记录，正是漏过的那一项
check(!!historyTable.STATUS_LABEL.PENDING_DECISION,
    'CLI 撞门禁留下的那一条在历史里读得懂：'
        + JSON.stringify(historyTable.STATUS_LABEL.PENDING_DECISION));

// ---------- 用例清单：分档、通过率、chip ----------
// 这一批的主战场。判据全是「喂一份清单和一份结果，看那句话对不对」，
// 所以把它们写成纯函数在这里钉住——浏览器里能看到的只是它们的一个结果，
// 而它们错了的表现很安静：通过率分母虚高、空档整块不显示，看着都像「正常」。
console.log('用例分档：');
const caseApi = load('index.html', [
  'CASE_LEVELS', 'CASE_OUTCOME_MARKS', 'CASE_OUTCOME_TEXT', 'FAILURE_KINDS',
  'caseLevel', 'caseLevelMeta', 'caseTiers', 'caseByIndex', 'caseOutcome', 'tierRate',
  'casePassRate', 'caseChipText', 'numbersIn', 'failureOf', 'failureKind', 'testsReport',
  'testsSummaryText', 'failPairHtml', 'guessHtml', 'failRowHtml', 'testActionPath', 'startsRun',
  'refeedText', 'putRefeedContext', 'caseCodeHtml', 'caseDetailHtml', 'caseItemHtml',
  'casesPanelHtml', 'casesFootHtml', 'hasCases', 'caseSignature', 'targetSignature',
  'planSignature', 'staleFreeze', 'needsConfirm', 'confirmCases', 'regenBlockHtml',
  'testsActionsHtml', 'testsPanelHtml', 'rateOfAll', 'caseListForTests',
], '// ---------- 用例与测试结果 ----------', 'async function refreshPending');

const {
  caseLevel, caseLevelMeta, caseTiers, caseByIndex, caseOutcome, tierRate, casePassRate,
  caseChipText, numbersIn, failureOf, failureKind, testsReport, testsSummaryText, failRowHtml,
  testActionPath, startsRun, refeedText, caseDetailHtml, casesPanelHtml, casesFootHtml,
  hasCases, caseSignature, targetSignature, planSignature, staleFreeze, needsConfirm,
  confirmCases, regenBlockHtml, testsActionsHtml, testsPanelHtml, rateOfAll,
} = caseApi;

check(caseLevel({ level: 'MUST' }) === 'MUST' && caseLevel({ level: '可选' }) === 'UNKNOWN',
    '分级只认引擎那四个枚举名；认不出来的按「未标」，不替它升级成「必须过」');
check(caseLevelMeta('SHOULD')[2] === '建议过', '档位带着中文标签');

const caseSample = [
  { index: 1, what: '按订单号查得到', how: '拿 id=1 查一次', level: 'MUST', expected: 'id=1', acceptance: 'R-1' },
  { index: 2, what: '查不到时返回 404', how: '拿 id=999 查一次', level: 'SHOULD', expected: '404', acceptance: 'R-1' },
  { index: 3, what: '参数为空时报 400', how: '传空 id', level: 'OPTIONAL', expected: '400', acceptance: '无' },
  { index: 4, what: '模型没写分级的那条', how: '随手验一下', level: '', expected: 'x', acceptance: '无' },
];
const tiers = caseTiers(caseSample);
check(tiers.length === 4, '四个档位一个不少，顺序固定：' + tiers.map(t => t.level).join(','));
check(tiers.map(t => t.items.length).join(',') === '1,1,1,1', '每条用例都落到了它自己那一档');
const emptyTier = caseTiers([caseSample[1]]);
check(emptyTier.length === 4 && emptyTier[0].items.length === 0,
    '某一档为空也照样显示那一档（不是把它整块省掉）');

console.log('通过率：');
check(casePassRate(caseSample, null) === '必须过 0/1 · 建议过 0/1 · 可选 0/1 · 未标 0/1',
    '还没跑时通过率也显示（分子 0——「没验」不能算「过了」）：' + casePassRate(caseSample, null));
const halfDone = { cases: [{ index: 1, passed: true }, { index: 2, passed: false }], failures: [] };
check(casePassRate(caseSample, halfDone) === '必须过 1/1 · 建议过 0/1 · 可选 0/1 · 未标 0/1',
    '过了的算过了、没过的算没过：' + casePassRate(caseSample, halfDone));
check(casePassRate([{ index: 1, level: 'SHOULD' }, { index: 2, level: 'SHOULD' }],
    { cases: [{ index: 1, passed: true }, { index: 2, passed: true }], failures: [] })
    === '必须过 0/0 · 建议过 2/2 · 可选 0/0 · 未标 0/0',
    '一级为空也照常通过：必须过那一档写 0/0，而两条建议过的都算过了');

// §15.3 那个例子：必须过 3/3 · 建议过 4/5 · 可选 2/6
const bulk = [];
for (let i = 1; i <= 3; i++) bulk.push({ index: i, level: 'MUST', what: '必须' + i });
for (let i = 4; i <= 8; i++) bulk.push({ index: i, level: 'SHOULD', what: '建议' + i });
for (let i = 9; i <= 14; i++) bulk.push({ index: i, level: 'OPTIONAL', what: '可选' + i });
const bulkReport = {
  cases: [
    ...[1, 2, 3].map(i => ({ index: i, passed: true })),
    ...[4, 5, 6, 7].map(i => ({ index: i, passed: true })),
    { index: 8, passed: false },
    ...[9, 10].map(i => ({ index: i, passed: true })),
    ...[11, 12, 13, 14].map(i => ({ index: i, passed: false })),
  ],
  failures: [],
};
check(casePassRate(bulk, bulkReport) === '必须过 3/3 · 建议过 4/5 · 可选 2/6 · 未标 0/0',
    '分级通过率就是这句话原本的样子：' + casePassRate(bulk, bulkReport));
check(rateOfAll(bulk, bulkReport) === '9/14', '总通过率是过了几条 / 一共几条：'
    + rateOfAll(bulk, bulkReport));

console.log('某条用例这一次的下场：');
check(caseOutcome(null, 1) === 'unknown', '还没跑过测试：未跑');
check(caseOutcome({ cases: [{ index: 1, passed: true }] }, 1) === 'passed', '报过 PASS：过了');
check(caseOutcome({ cases: [{ index: 1, passed: false }] }, 1) === 'failed', '报过 FAIL：没过');
check(caseOutcome({ cases: [{ index: 2, passed: true }] }, 1) === 'missing',
    '清单上有、脚本一条都没报：单算一档「没报」（它不是断言失败，是压根没验）');

console.log('chip 上那句话：');
check(caseChipText(caseSample[0]) === '按订单号查得到　·　拿 id=1 查一次',
    '口语化「要测什么 + 怎么测」两样都在：' + caseChipText(caseSample[0]));
check(caseChipText({ what: '只写了要测什么' }) === '只写了要测什么', '没写怎么测就只说一半，不编');
check(caseChipText({}) === '（这条没写要测什么）', '两样都没写时明说，不留空 chip');
check(numbersIn('用例 12') .join() === '12' && numbersIn('').length === 0,
    '「哪条用例」那一栏是脚本原话，按里面的数字对号');
check(caseByIndex(caseSample, '2').what === '查不到时返回 404', '按编号取那条用例（编号可能是字符串）');
check(caseByIndex(caseSample, 99) === null, '清单里没有就返回 null，不编一条出来');

// ---------- 失败清单 ----------
console.log('失败清单四要素：');
const failure = {
  kind: 'ASSERTION',
  testCase: '2',
  expected: '404',
  actual: '500',
  opinion: 'I think the product code is wrong',
};
const report = {
  directory: 'tools/20260930-120000',
  files: ['tools/20260930-120000/run.cmd', 'tools/20260930-120000/Check.java'],
  sources: {
    'tools/20260930-120000/run.cmd': '@echo off\necho FAIL ^| 2 ^| 404 ^| 500\n',
    'tools/20260930-120000/Check.java': 'class Check { boolean ok() { return assert404(); } }',
  },
  exit: 1,
  passed: false,
  cases: [{ index: 1, passed: true }, { index: 2, passed: false }],
  failures: [failure],
  output: 'PASS | 1\nFAIL | 2 | 404 | 500 | it thinks code is wrong\n',
};
check(failureKind('ASSERTION')[1] === '断言失败' && failureKind('TEST_CODE')[1] === '测试代码问题'
    && failureKind('ENVIRONMENT')[1] === '环境问题' && failureKind('TIMEOUT')[1] === '测试超时',
    '四类失败各有各的说法（合并成一句「测试没过」，用户就会去翻产品代码）');
check(failureKind('WEIRD')[1] === 'WEIRD', '认不出来的类型原样显示，不假装认出来了');

const failText = failRowHtml(failure, caseSample, new Set(), new Set());
check(failText.includes('用例 2'), '① 哪条用例（编号）在里面：' + failText.slice(0, 120));
check(failText.includes('查不到时返回 404'), 'chip 的语义描述跟着走，人不用回去翻清单');
check(failText.includes('断言失败'), '② 失败原因是机器判的那一档');
check(failText.includes('期望：') && failText.includes('404'), '③ 期望在里面');
check(failText.includes('实际：') && failText.includes('500'), '③ 实际在里面');
check(failText.includes('class="fail-guess"') && failText.includes('这是 AI 的猜测'),
    '④ AI 的猜测单独一块，并且标明它是猜测');
check(!failRowHtml({ ...failure, opinion: '' }, caseSample, new Set(), new Set())
    .includes('fail-guess'), '它没说谁错时那一段整个不出现（不留一个空壳占位置）');
check(failRowHtml({ ...failure, testCase: '', expected: '', actual: '' }, caseSample, new Set(), new Set())
    .includes('（没写）'), '脚本没写期望/实际时明说「没写」，不留空行让人以为是漏显示');
check(failText.includes('data-pick="2"'), '每条失败带一个勾选框，勾了才能回喂给开发');
check(failRowHtml(failure, caseSample, new Set([2]), new Set()).includes('data-pick="2" checked'),
    '勾上的那条画出来就是勾着的');

console.log('失败清单那一块的转义与出处：');
const nastyFail = failRowHtml({
  kind: 'ASSERTION', testCase: '1', expected: '<img src=x onerror=1>', actual: '</div>',
  opinion: '<b>代码错了</b>',
}, caseSample, new Set(), new Set());
check(nastyFail.includes('&lt;img src=x onerror=1&gt;') && !nastyFail.includes('<img src=x'),
    '期望/实际是脚本打印的原话，一律转义');
check(nastyFail.includes('&lt;b&gt;代码错了&lt;/b&gt;'), 'AI 那句猜测也转义');

const bigReport = testsReport(report);
check(testsReport(null) === null && testsReport('x') === null, '没有测试结果时返回 null（整块不画）');
check(bigReport.passed === false && bigReport.exit === 1, '归一化：没过、退出码 1');
check(testsReport({ exit: 0, failures: [] }).passed === true, '退出码 0 且没有失败 = 过了');
check(testsReport({ exit: 1, failures: [], verification: { output: 'oops' } }).output === 'oops',
    '留档里那份没有 output 字段，退回 verification.output 取原始输出');

const panel = testsPanelHtml(report, caseSample, { picked: new Set([2]), known: new Set(), pending: true });
check(panel.includes('测试结果'), '这一块有自己的标题：测试结果');
check(panel.includes('断言失败') && panel.includes('404') && panel.includes('500'),
    '失败清单在结果面板里（四要素都在）');
check(panel.includes('必须过 1/1') && panel.includes('建议过 0/1'),
    '这块里也报一遍通过率（只给失败清单的话，一次只跑了三条用例的运行会被读成满分）');
check(panel.includes('这四条') === false && panel.includes('谁错了由你判'),
    '这块说清它的立场：机器摆事实，谁错了由人判');
check(testsPanelHtml(report, caseSample, { open: false }).includes('data-open="false"'),
    '收起状态画得出来');
check(testsPanelHtml(report, caseSample, { open: false }).includes('<div class="pending-body" hidden>'),
    '收起时正文整块藏起来');
check(testsPanelHtml(report, caseSample, { actions: false }).includes('data-act="next-round"') === false,
    'actions: false（历史详情那种场合）时一个动作按钮都不画——它们动的是此刻的磁盘');

console.log('失败清单：已知失败那一条：');
const knownPanel = testsPanelHtml(report, caseSample, { known: new Set([2]), picked: new Set() });
check(knownPanel.includes('已知失败'), '标过的那条改写成「已知失败」');
check(knownPanel.indexOf('已知失败') > 0 && knownPanel.includes('data-known="true"'),
    '整行也跟着走，不只是那个小标签');

// ---------- 四个动作 ----------
console.log('四个动作各打哪儿：');
check(testActionPath('next-round') === '/api/run', '① 开发 AI 错了 → 下一轮 → /api/run');
check(testActionPath('regenerate') === '/api/tests/regenerate',
    '② 测试代码错了 → 重新生成 → /api/tests/regenerate（只换测试代码）');
check(testActionPath('known') === '', '③ 不重要/误报：不发请求（没有任何机器动作配得上这个判断）');
check(testActionPath('accept') === '/api/accept', '④ 接受 → /api/accept');
check(testActionPath('interrupt') === '/api/rollback', '通用出口「中断 / 恢复到初始」→ /api/rollback');
check(startsRun('next-round') === true, '只有「下一轮」会开一次运行');
check(startsRun('regenerate') === false, '修测试那条路不自动重跑（十五.6）');
check(startsRun('known') === false && startsRun('accept') === false && startsRun('interrupt') === false,
    '另外三条路都不会顺手开运行');
const paths = ['next-round', 'regenerate', 'accept', 'interrupt'].map(testActionPath);
check(new Set(paths).size === paths.length,
    '四条路两两不同（接到同一个接口上就是「点张三打了李四」）：' + paths.join(','));

const actionsHtml = testsActionsHtml(bigReport, caseSample, new Set([2]), new Set(), { pending: true });
check(actionsHtml.includes('data-act="next-round"') && actionsHtml.includes('data-act="regenerate"')
    && actionsHtml.includes('data-act="known"') && actionsHtml.includes('data-act="accept"')
    && actionsHtml.includes('data-act="interrupt"'),
    '五个按钮都在（四个动作 + 一个通用出口）');
check(actionsHtml.includes('下一轮（回喂选中的 1 条）'), '「下一轮」把回喂的条数写在按钮上');
check(actionsHtml.includes('我的设计错了') === false,
    '界面上不许出现「我的设计错了」这种按钮（十五.6）');
check(testsActionsHtml(bigReport, caseSample, new Set(), new Set(), { pending: true })
    .includes('data-act="next-round" disabled'),
    '一条都没勾时「下一轮」是按住的（回喂得先说出要修哪几条）');
check(testsActionsHtml(bigReport, caseSample, new Set([2]), new Set(),
    { pending: true, regenerated: { released: false } }).includes('data-act="next-round" disabled'),
    '重新生成的那批还没放行之前，「下一轮」也是按住的（十五.6 的那条顺序）');
check(testsActionsHtml(bigReport, caseSample, new Set([2]), new Set(), { pending: false })
    .includes('data-act="accept"') === false,
    '磁盘上没有待处置的改动时不画「接受 / 中断」（点了只会拿到 409）');

console.log('重新生成那一块（等人放行）：');
const regen = {
  directory: 'tools/20260930-121500',
  files: ['tools/20260930-121500/run.cmd'],
  sources: { 'tools/20260930-121500/run.cmd': 'echo PASS ^| 1\n' },
  released: false,
};
const regenText = regenBlockHtml(regen);
check(regenText.includes('tools/20260930-121500/run.cmd') && regenText.includes('echo PASS'),
    '重新生成的测试代码连正文一起摊开给人 review');
check(regenText.includes('还没有跑') || regenText.includes('没有被执行'),
    '并且说清它没跑过（不自动重跑）');
check(regenText.includes('data-act="release"'), '等人点「放行」');
check(regenBlockHtml({ ...regen, released: true }).includes('已放行'), '放行之后这一块不再是按钮');

console.log('回喂给开发的那段话（十五.7 的固定模板）：');
const refeed = refeedText([2], caseSample, report, ['src/main/java/com/demo/Foo.java']);
check(refeed.includes('用例 2') && refeed.includes('查不到时返回 404'), '带用例的语义描述');
check(refeed.includes('断言失败'), '带失败类型');
check(refeed.includes('期望 404') && refeed.includes('实际 500'), '带期望 vs 实际');
check(refeed.includes('src/main/java/com/demo/Foo.java'), '带涉及的目标文件');
check(!refeed.includes('echo FAIL') && !refeed.includes('assert404'),
    '不给测试代码、不给断言源码（给了它就会照着断言改代码）');
check(refeedText([2], caseSample, report, []).includes('（没勾任何目标文件）'),
    '一条目标文件都没勾时明说，不留空');

// ---------- 确认 → 冻结 ----------
console.log('确认与冻结：');
const frozenCases = caseSample.slice(0, 3);
check(planSignature(frozenCases, ['b/Foo.java', 'a/Bar.java'])
    === planSignature(frozenCases, ['a/Bar.java', 'b/Foo.java']),
    '目标文件的勾选顺序不算「变了」（排序后再比）');
check(planSignature(frozenCases, ['a/Bar.java'])
    !== planSignature(frozenCases, ['a/Bar.java', 'b/Foo.java']),
    '目标文件集多一个就是变了：要重新确认');
check(planSignature(frozenCases, ['a/Bar.java'])
    !== planSignature(frozenCases.slice(1), ['a/Bar.java']),
    '用例集变了也要重新确认');
check(planSignature([{ index: 1, what: 'a   b' }], []) === planSignature([{ index: 1, what: 'a b' }], []),
    '只有空白差别不算变了（否则模型少打一个空格就要人再确认一次）');

state.plan = { cases: frozenCases };
state.selected = new Set(['a/Bar.java']);
state.frozen = null;
check(hasCases() === true && needsConfirm() === true, '有用例、还没确认过：要人点一下');
// confirmCases 除了记下指纹，还会重画面板、解锁「运行」按钮、弹一句提示——那三件都碰 DOM，
// 这里只想知道它留下的状态，所以给三个空替身（它们仨各自的行为由浏览器链盯着）
globalThis.renderPlan = () => {};
globalThis.notice = () => {};
globalThis.updateRunButton = () => {};
confirmCases();
check(!needsConfirm(), '点过「确认」之后不再打扰人');
check(state.frozen.signature === planSignature(frozenCases, state.selected), '冻住的是那个指纹');
state.selected = new Set(['a/Bar.java', 'b/Foo.java']);
check(staleFreeze() === true && needsConfirm() === true, '目标文件集变了：要重新确认');
state.selected = new Set(['a/Bar.java']);
check(needsConfirm() === false, '改回去之后又不用确认了（判据是内容，不是「有没有动过」）');
state.plan = null;
check(needsConfirm() === false, '这次没有用例的运行不设这道闸（老用法一个字节都没变）');
state.frozen = null;

console.log('用例清单那一块画成什么样：');
const casesHtml = casesPanelHtml(caseSample,
    { cases: [{ index: 1, passed: true }, { index: 2, passed: false }], failures: [failure],
      directory: 'tools/20260930-120000',
      sources: { 'tools/20260930-120000/run.cmd': '@echo off\necho FAIL\n' } },
    { detail: 2, confirm: true, frozen: false, stale: false });
check((casesHtml.match(/class="case-chip"/g) || []).length === 4, '每条用例一个 chip');
check(casesHtml.includes('必须过 1/1') && casesHtml.includes('未标 0/1'),
    '每档通过率都在（四档一个不落）');
check(casesHtml.includes('data-act="confirm"') && casesHtml.includes('确认这批用例（冻结）'),
    '还没确认时那一枚按钮在');
check(casesHtml.includes('data-detail="2"') && (casesHtml.match(/case-detail/g) || []).length === 1,
    'detail 只展开指定的那一条');
check(casesHtml.includes('tools/20260930-120000/run.cmd') && casesHtml.includes('echo FAIL'),
    '展开的那一条里能看见测试代码和它的路径');
check(casesHtml.indexOf('case-code') > casesHtml.indexOf('data-detail="2"'),
    '测试代码在那条用例的细节里，不是另起一块');
check(casesPanelHtml(caseSample, null, { detail: null, confirm: true }).includes('还没有')
    || casesPanelHtml(caseSample, null, { detail: null, confirm: true }).includes('还没跑过测试'),
    '还没跑过测试时说清这一点（不假装每条都过）');
check(casesPanelHtml(caseSample, null, { detail: null, confirm: false }).includes('data-act="confirm"')
    === false, '历史面板里没有「确认」这回事');
check(casesFootHtml(caseSample, { confirm: true, frozen: true, stale: false })
    .includes('已确认并冻结'), '冻过之后那行改成「已确认并冻结」');
check(casesFootHtml(caseSample, { confirm: true, frozen: true, stale: true }).includes('data-act="confirm"'),
    '冻过但内容变了：再给一次「确认」');
check(casesPanelHtml([], report, {}) === '', '一条用例都没有时整块不画');

console.log('用例细节里的测试代码：');
const caseDetailText = caseDetailHtml(caseSample[1], testsReport(report));
check(caseDetailText.includes('要测什么') && caseDetailText.includes('怎么测') && caseDetailText.includes('对应验收'),
    '四栏都在（要测什么 / 怎么测 / 期望 / 对应哪条验收）');
check(caseDetailText.includes('没过'), '上次的下场也写着');
check(caseDetailText.includes('断言失败'), '失败原因跟着走');
check(caseDetailHtml(caseSample[1], null).includes('还没有'),
    '还没生成测试代码时明说它什么时候才有');

// ---------- 跑测试期间那句话 ----------
// 「测试进行中（最长 N 分钟）」必须由引擎说出来：那一段跑多久只有引擎知道，
// 界面自己写一个数，两边迟早对不上。
console.log('跑测试期间的状态那句话：');
const { stageText } = load('index.html', ['stageText'],
    'function stageText', 'function updateStopButton');
check(stageText({ type: 'log', round: 0, text: '测试进行中（最长 5 分钟）：正在生成测试产物并跑 3 条用例' })
    === '测试进行中（最长 5 分钟）', '测试那一段照引擎那句话说话（数也是它的）');
check(stageText({ type: 'log', round: 0, text: '测试进行中（最长 9 分钟）：x' }) === '测试进行中（最长 9 分钟）',
    '引擎改了时限，界面跟着改（不是写死的 5 分钟）');
check(stageText({ type: 'log', round: 2, text: '第 2 轮：调用模型…' }) === '第 2 轮', '轮级还是老样子');
check(stageText({ type: 'log', round: 1, text: '编译校验：通过' }) === '编译校验中…', '编译那一段照旧');
check(stageText({ type: 'result' }) === '' && stageText(null) === '',
    '收场和没有事件时那句话清掉（不留一句过期的「正在…」）');
check(stageText({ type: 'log', round: 0, text: '第 1 步：加接口' }) === '正在开工…', '没有轮次时说「正在开工」');

console.log(failed ? '\n失败 ' + failed + ' 项' : '\n全部通过');
process.exitCode = failed ? 1 : 0;