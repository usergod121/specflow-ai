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
  const source = fs.readFileSync(path.join(WEB, file), 'utf8');
  const from = fromMarker ? source.indexOf(fromMarker) : 0;
  const to = toMarker ? source.indexOf(toMarker) : source.length;
  if (from < 0 || to < 0 || to <= from) {
    console.error('找不到 ' + file + ' 里的目标片段，结构可能变了');
    process.exit(1);
  }
  return eval(source.slice(from, to) + '\n({' + names.join(',') + '})');
}

/** 页面里的 state 是全局的，被测函数会读它；这里放一份假的，纯逻辑测试就不用开浏览器。 */
globalThis.state = { files: [], selected: new Set() };

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
const { parseFlow, layerize, labelWidth, place, groupBox } =
    load('flowchart.js', ['parseFlow', 'layerize', 'labelWidth', 'place', 'groupBox']);

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

console.log(failed ? '\n失败 ' + failed + ' 项' : '\n全部通过');
process.exitCode = failed ? 1 : 0;