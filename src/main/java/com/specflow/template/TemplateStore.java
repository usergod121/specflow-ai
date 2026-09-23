package com.specflow.template;

import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.specflow.exception.SpecflowException;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.specflow.exception.SpecflowException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 模板文件的读写。
 *
 * <p>模板在磁盘上就是 {@code .specflow/templates/<名字>.yaml} 一个文件一份，
 * 所以这个类同时承担了「读」和「增删改」两件事——它们本来就是对同一份格式的操作，
 * 拆成两个类反而会让<b>校验规则</b>分家：存的时候不校验、读的时候才发现不对，
 * 用户拿到的是一份存得进去但用不了的模板。
 *
 * <p>加载时校验两件事，都在存的时候也校验一遍：
 * <ul>
 *   <li>必填字段齐全、字段 key 合法</li>
 *   <li>字段声明与提示词里的 {@code {{占位符}}} 严格对应</li>
 * </ul>
 *
 * <p>文件名与内部的 {@code name} 字段不一致时<b>以文件名为准</b>：
 * 文件名是用户在资源管理器里看得见的东西，让 {@code get("add-endpoint")}
 * 对应 {@code add-endpoint.yaml}，比对应 yaml 里某个手写字符串更不容易出错。
 */
public final class TemplateStore {

    public static final String DEFAULT_DIR = ".specflow/templates";

    /** 模板名会变成文件名，必须挡住分隔符和 {@code ..}。中文名是允许的。 */
    private static final Pattern NAME_PATTERN = Pattern.compile("[\\w\\u4e00-\\u9fa5-]{1,64}");

    /**
     * 界面内部保留的选项值前缀，见 {@code web/index.html} 里的 {@code FREE} 与 {@code NEW_TEMPLATE}。
     *
     * <p>下拉框把「自由输入」「新建模板」和真实模板放在同一个列表里，靠这几个值区分。
     * 一旦有模板叫 {@code __new__}，它在界面上就永远选不中（一选就弹新建），
     * 所以干脆不让创建——这比在界面里绕开它干净。
     */
    private static final String RESERVED_PREFIX = "__";

    /** 已废弃的产物类型字段。留着只为给出迁移提示，不再参与任何逻辑。 */
    private static final Set<String> RETIRED_TYPE_KEYS = Set.of("type-name", "type_name", "typeName");

    private static final YAMLMapper YAML = new YAMLMapper();
    private static final String EXTENSION = ".yaml";

    private final Path directory;

    public TemplateStore(Path directory) {
        this.directory = directory.toAbsolutePath().normalize();
    }

    public Path directory() {
        return directory;
    }

    /**
     * 一次目录扫描的结果。
     *
     * <p>为什么把「读不回来的那几份」单独端出来，而不是直接抛异常：
     * 模板是用户手写的文件，一个手滑就能写坏。全量加载要是「全有或全无」，
     * 一个坏文件就能让整个模板列表和下次 {@code specflow web} 启动一起失败——
     * 而那时候用户连界面都进不去，也就没有任何地方能把那个文件删掉。
     *
     * <p>但也不能悄悄跳过：那样用户只会看到模板少了一个，然后对着「找不到模板」猜。
     * 所以坏的那几份照样报出来，只是不再拦着别人用。
     *
     * @param templates 读得出来的，按文件名排序
     * @param broken    读不出来的，附上原因，按文件名排序
     */
    public record Loaded(Map<String, PromptTemplate> templates, List<BrokenTemplate> broken) {
    }

    /** 一份读不回来的模板。{@code name} 是文件名去掉扩展名，够用户在资源管理器里找到它。 */
    public record BrokenTemplate(String name, String reason) {
    }

    /**
     * 扫描目录并加载全部模板。目录不存在时返回空集合。
     *
     * <p>顺序有意义：界面默认选中的是第一个，顺序每次都变会让人「第一个就是我常用的那个」
     * 的肌肉记忆失效。所以下面特意用 {@link LinkedHashMap} 并按文件名排过，
     * 返回值也必须是保序的不可变视图——{@code Map.copyOf} 会把顺序丢掉。
     */
    public Loaded loadAll() {
        if (!Files.isDirectory(directory)) {
            return new Loaded(Map.of(), List.of());
        }
        Map<String, PromptTemplate> loaded = new LinkedHashMap<>();
        List<BrokenTemplate> broken = new ArrayList<>();
        try (Stream<Path> files = Files.list(directory)) {
            for (Path file : files.filter(Files::isRegularFile).filter(TemplateStore::isYaml)
                    .sorted().toList()) {
                String name = stripExtension(file.getFileName().toString());
                try {
                    loaded.put(name, readFile(file));
                } catch (SpecflowException e) {
                    broken.add(new BrokenTemplate(name, e.getMessage()));
                }
            }
        } catch (IOException e) {
            throw new SpecflowException("读取模板目录失败 " + directory + "：" + e.getMessage(), e);
        }
        return new Loaded(Collections.unmodifiableMap(loaded), List.copyOf(broken));
    }

    public boolean exists(String name) {
        return Files.isRegularFile(fileOf(name));
    }

    /**
     * 读取单个模板。
     *
     * @throws SpecflowException 名字非法、文件不存在、或内容不合法
     */
    public PromptTemplate load(String name) {
        Path file = fileOf(name);
        if (!Files.isRegularFile(file)) {
            throw new SpecflowException("找不到模板 '" + name + "'（" + file + "）");
        }
        return readFile(file);
    }

    /**
     * 新建或覆盖一个模板。写之前先校验，绝不把一份用不了的模板落盘。
     *
     * <p>存成哪个文件，由模板自己的 {@link PromptTemplate#name()} 决定——
     * 不再另外收一个名字参数。两个名字都叫「模板名」的时候，
     * 调用方很容易传岔，然后磁盘上就会躺着一个名字对不上的模板：
     * 界面按内容里的名字列出来，删除却按文件名去找，于是它既删不掉也读不出来。
     *
     * @throws SpecflowException 名字非法或内容不合法
     */
    public void save(PromptTemplate template) {
        String name = template.name();
        validate(template, name + EXTENSION);
        Path target = fileOf(name);
        try {
            Files.createDirectories(directory);
            // 先写同目录下的临时文件，读回来确认能用，再改名盖上去。两个理由：
            //   1. snakeyaml 对单份文档有硬上限。一份大到读不回来的模板要是直接落盘，
            //      从此不但它自己读不出来，整个模板列表和下次启动都会被它带下去；
            //   2. 直接 truncate + 分块写的话，两个请求同时存同名模板会写出内容交错的
            //      半个文件——而交错本身就会破坏语法，于是又掉进第 1 条。
            // 改名在同一个目录内，是一次原子替换：读的人要么看到旧的，要么看到新的。
            Path temp = Files.createTempFile(directory, name + "-", ".yaml.tmp");
            try {
                YAML.writeValue(temp.toFile(), template);
                parse(Files.readString(temp, StandardCharsets.UTF_8), name + EXTENSION);
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(temp);
            }
        } catch (IOException e) {
            throw new SpecflowException("写入模板失败 " + name + "：" + e.getMessage(), e);
        }
    }

    /** 把一份 YAML 源码解析成模板并校验，供界面「源码视图」应用时检查。 */
    public PromptTemplate parse(String yamlSource, String sourceName) {
        PromptTemplate template = read(yamlSource, sourceName);
        validate(template, sourceName);
        return template;
    }

    public String toYaml(PromptTemplate template) {
        try {
            return YAML.writeValueAsString(template);
        } catch (IOException e) {
            throw new SpecflowException("生成模板源码失败：" + e.getMessage(), e);
        }
    }

    /**
     * @throws SpecflowException 文件不存在时也报错——删一个本来就没有的东西
     *                           通常意味着界面上看到的状态已经过期了
     */
    public void delete(String name) {
        Path file = fileOf(name);
        try {
            if (!Files.deleteIfExists(file)) {
                throw new SpecflowException("找不到要删除的模板 '" + name + "'");
            }
        } catch (IOException e) {
            throw new SpecflowException("删除模板失败 " + name + "：" + e.getMessage(), e);
        }
    }

    // ---------- 内部 ----------

    private Path fileOf(String name) {
        if (name == null || !NAME_PATTERN.matcher(name).matches()) {
            throw new SpecflowException("模板名非法: '" + name
                    + "'（只能用中英文、数字、下划线、连字符，且不能超过 64 个字符）");
        }
        if (name.startsWith(RESERVED_PREFIX)) {
            throw new SpecflowException("模板名不能以 " + RESERVED_PREFIX + " 开头: '" + name
                    + "'（那是界面内部用来表示「自由输入」「新建模板」的保留值）");
        }
        Path yaml = directory.resolve(name + EXTENSION).normalize();
        if (Files.isRegularFile(yaml)) {
            return yaml;
        }
        // 扫描时 .yml 也认（用户手写文件是这套工具明说的用法），所以这里也得认，
        // 否则手工建的 x.yml 会「列表里有、读却读不到、删也删不掉」。
        // 新建仍然一律用 .yaml——只有已经存在的那份才按它自己的扩展名走。
        Path yml = directory.resolve(name + ".yml").normalize();
        return Files.isRegularFile(yml) ? yml : yaml;
    }

    private PromptTemplate readFile(Path file) {
        String sourceName = file.getFileName().toString();
        try {
            return validate(YAML.readValue(file.toFile(), PromptTemplate.class), sourceName);
        } catch (IOException e) {
            throw translate(e, sourceName);
        }
    }

    private PromptTemplate read(String yamlSource, String sourceName) {
        try {
            return YAML.readValue(yamlSource, PromptTemplate.class);
        } catch (IOException e) {
            throw translate(e, sourceName);
        }
    }

    /**
     * 把 Jackson 的异常翻成一句能指路的话。
     *
     * <p>老模板里写了 {@code type-name} 的会被单独认出来，给一句「改用 tags」的迁移提示，
     * 而不是让人对着「未知字段 type-name」自己猜 {@code tags} 是不是替代品。
     *
     * <p>这条提示放在 IO 层而不是 {@link PromptTemplate} 里，
     * 是为了不让一个已经废弃的字段继续留在数据模型上。
     */
    private SpecflowException translate(IOException e, String sourceName) {
        if (e instanceof UnrecognizedPropertyException unknown
                && RETIRED_TYPE_KEYS.contains(unknown.getPropertyName())) {
            return new SpecflowException("模板 " + sourceName + " 里的 '" + unknown.getPropertyName()
                    + "' 已废弃，请改用 tags，例如：tags: [class, java, spring-boot]");
        }
        if (rootMessage(e).contains("No content to map")) {
            // 空文档。写到一半断电、编辑器另存都留得下这种文件，
            // 而 Jackson 原话是「No content to map due to end-of-input」，没人看得懂。
            return new SpecflowException("模板 " + sourceName + " 是空的：里面一个字符都没有。"
                    + "（写到一半断电、编辑器另存都会留下这种文件）删掉它，或者补上内容。");
        }
        if (rootMessage(e).contains("exceeds the limit")) {
            // snakeyaml 对单份文档有硬上限。这句话会直接摆在界面上，得说清能怎么办。
            return new SpecflowException("模板 " + sourceName + " 超过 YAML 的解析上限（约 3MB）。"
                    + "大段的表结构、接口定义请放进「上下文依赖」，不要堆在角色提示词里。");
        }
        if (e instanceof UnrecognizedPropertyException unknown) {
            return new SpecflowException("模板 " + sourceName + " 里有个不认识的字段 '"
                    + unknown.getPropertyName() + "'。模板只有这几个字段："
                    + knownFields(unknown) + "。");
        }
        if (e instanceof MismatchedInputException mismatch) {
            return new SpecflowException("模板 " + sourceName + " 里的「" + lastField(mismatch)
                    + "」格式不对：它要的是" + expectedType(mismatch.getTargetType())
                    + "。对照一份能用的模板改一下。");
        }
        // 兜底也把 Jackson 那句里的源码位置和引用链切掉——那两段对用户没有任何意义
        return new SpecflowException("模板 " + sourceName + " 不合法：" + brief(e), e);
    }

    /**
     * 这些翻译的必要性在于：消息会原样出现在界面上。
     *
     * <p>Jackson 的原话是「Cannot deserialize value of type {@code java.lang.String}
     * from Array value (token {@code JsonToken.START_ARRAY}) at [Source: (File); line: 2,
     * column: 9] (through reference chain: …)」——有用的只有「哪个字段」和「该是什么」，
     * 其余全是给开发者看的。
     */
    private static String lastField(MismatchedInputException e) {
        List<JsonMappingException.Reference> path = e.getPath();
        if (path == null || path.isEmpty()) {
            return "内容";
        }
        String field = path.get(path.size() - 1).getFieldName();
        return field == null ? "内容" : field;
    }

    private static String expectedType(Class<?> type) {
        if (type == String.class) {
            return "一段文本";
        }
        if (type != null && List.class.isAssignableFrom(type)) {
            return "一列内容（每行以 - 开头逐条写）";
        }
        return "另一种类型";
    }

    private static String knownFields(UnrecognizedPropertyException e) {
        Collection<Object> ids = e.getKnownPropertyIds();
        if (ids == null || ids.isEmpty()) {
            return "name、tags、description、system、context";
        }
        return ids.stream().map(String::valueOf).sorted().collect(Collectors.joining("、"));
    }

    /** 切掉 Jackson 消息里的源码位置和引用链。 */
    private static String brief(IOException e) {
        String message = rootMessage(e);
        int cut = message.indexOf("\n at [Source");
        return cut > 0 ? message.substring(0, cut) : message;
    }

    /**
     * 结构校验。
     *
     * <p>解析阶段的失败（缺必填字段、名字非法）会被 Jackson 包成异常，
     * 由 {@link #translate} 统一翻成一句能指出是哪一份文件的话；
     * 这里管的是解析成功之后仍不成立的事。
     */
    private PromptTemplate validate(PromptTemplate template, String sourceName) {
        if (template == null) {
            throw new SpecflowException("模板文件为空: " + sourceName);
        }
        // 模板的 system 是配一次的静态文本。允许它写占位符，就等于在界面上留一个
        // 「存得进去、一运行就报未赋值、而且无从修复」的死胡同——界面上没有填变量的地方
        Set<String> placeholders = TemplateRenderer.placeholders(template.system());
        if (!placeholders.isEmpty()) {
            throw new SpecflowException("模板 " + sourceName + " 的角色提示词里不能有占位符："
                    + String.join("、", placeholders) + System.lineSeparator()
                    + "  模板是配一次、长期复用的静态文本；要随每次变化的内容，请写进需求。");
        }
        return template;
    }

    private static boolean isYaml(Path file) {
        String name = file.getFileName().toString().toLowerCase();
        return name.endsWith(".yaml") || name.endsWith(".yml");
    }

    private static String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? fileName : fileName.substring(0, dot);
    }

    private static String rootMessage(IOException e) {
        return e.getCause() == null ? e.getMessage() : e.getCause().getMessage();
    }
}
