package com.specflow.project;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.specflow.exception.SpecflowException;
import com.specflow.spec.ContextItem;
import com.specflow.util.SafePathResolver;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 上下文的导出与读回：把「一套上下文」存成一个文件，换台机器、换个人也能接着用。
 *
 * <p>和任务草稿的区别在于它<b>不是</b>一次需求的组装——里面只有上下文条目，
 * 与需求、目标文件都无关。所以它才需要单独一份：同一套上下文（比如那份表结构 DDL）
 * 会被好多次需求反复用到，挂在某一个草稿里就复制了好几份，改一处漏三处。
 *
 * <p>条目类型直接复用 {@link ContextItem}，不另造一个平行模型：
 * 另造一个的结果必然是「导出的校验规则和运行时的不是一套」，
 * 于是导出时通过、导入回来却跑不起来。
 *
 * <p>落地形态是 YAML + {@link Bundle} 外壳。外壳带着 {@code project} 与
 * {@code exportedAt}，纯粹是为了让人在编辑器里打开它时知道<b>这是什么时候、
 * 从哪个项目里导出来的</b>——读回来时它们不参与任何判断。
 */
public final class ContextLibrary {

    public static final String DEFAULT_DIR = ".specflow/context";

    /** 名字会变成文件名，必须挡住分隔符和 {@code ..}。中文名是允许的。 */
    private static final Pattern NAME_PATTERN = Pattern.compile("[\\w\\u4e00-\\u9fa5-]{1,64}");

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final String EXTENSION = ".yaml";

    /** 导出的时间只精确到分钟：秒级精度对人没有意义，只会让两行导出看起来不一样。 */
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final Path projectRoot;
    private final Path directory;

    /**
     * @param projectRoot 项目根目录，用于校验条目引用的路径是否越界，以及记录这份上下文来自哪个项目
     */
    public ContextLibrary(Path projectRoot) {
        this.projectRoot = projectRoot.toAbsolutePath().normalize();
        this.directory = this.projectRoot.resolve(DEFAULT_DIR);
    }

    public Path directory() {
        return directory;
    }

    /**
     * 导出文件的内容。
     *
     * <p>未知字段一律忽略：这个文件可以被手工编辑，也可能来自更新版本的 specflow，
     * 里面多出来的字段不该让一整套上下文读不回来——那正是「导出」这个功能失效的场景。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Bundle(
            String name,
            String project,
            String exportedAt,
            List<ContextItem> items
    ) {
    }

    /**
     * 一次目录扫描的结果。
     *
     * <p>为什么把「读不回来的那几份」单独端出来，而不是让整次读取失败：
     * 这些文件是可以手工编辑的（那正是它存在的意义——换台机器、换个人接着用），
     * 手滑写坏一个字符就能让它读不出来。全量读取要是「全有或全无」，
     * 一份坏文件就会让<b>整份清单</b>都打不开：界面上一套上下文都不显示，
     * 用户连「把那份坏的删掉」的地方都没有。
     *
     * <p>但也不能悄悄跳过：那样用户只会看到自己导出过的那套不见了，然后对着空列表猜。
     * 所以坏的那几份照样报出来，只是不再拦着别人用——和 {@code TemplateStore}
     * 读模板时同一个分寸：{@link #load} 一份报错，{@link #loadAll} 跳过并记账。
     *
     * @param bundles 读得出来的，按名字排序
     * @param broken  读不出来的，附上原因，按名字排序
     */
    public record Loaded(List<Bundle> bundles, List<BrokenBundle> broken) {
    }

    /** 一份读不回来的上下文。{@code name} 就是文件名去掉扩展名，够用户在资源管理器里找到它。 */
    public record BrokenBundle(String name, String reason) {
    }

    /** 按名字排序的上下文名，不带扩展名。目录不存在时返回空列表。 */
    public List<String> names() {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(directory)) {
            return files
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(EXTENSION))
                    .map(path -> stripExtension(path.getFileName().toString()))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new SpecflowException("读取上下文目录失败：" + e.getMessage(), e);
        }
    }

    /**
     * 把整个目录读一遍：读得出来的收进 {@code bundles}，读不回来的记进 {@code broken}。
     *
     * <p>界面要走的是这一条，不是「先 {@link #names()} 再逐个 {@link #load}」——
     * 那条路上任何一份文件读不出来，整次请求都会失败。
     */
    public Loaded loadAll() {
        List<Bundle> bundles = new ArrayList<>();
        List<BrokenBundle> broken = new ArrayList<>();
        for (String name : names()) {
            try {
                bundles.add(load(name));
            } catch (SpecflowException e) {
                broken.add(new BrokenBundle(name, e.getMessage()));
            }
        }
        return new Loaded(List.copyOf(bundles), List.copyOf(broken));
    }

    /**
     * @throws SpecflowException 名字非法、文件不存在、或文件读不出来
     */
    public Bundle load(String name) {
        Path file = fileOf(name);
        if (!Files.isRegularFile(file)) {
            throw new SpecflowException("找不到这套上下文 '" + name + "'");
        }
        try {
            Bundle bundle = YAML.readValue(file.toFile(), Bundle.class);
            if (bundle == null) {
                throw new SpecflowException("这套上下文是空的 '" + name + "'"
                        + "（写到一半断电、编辑器另存都会留下这种文件）");
            }
            return bundle;
        } catch (IOException e) {
            throw new SpecflowException("读取上下文失败 " + name + "：" + reason(e), e);
        }
    }

    /**
     * 从 Jackson 的异常里挑出一句能直接摆到界面上的话。
     *
     * <p>Jackson 的原话是「Cannot construct instance of {@code com.specflow.spec.ContextItem},
     * problem: … at [Source: (File); line: 3, column: 1] (through reference chain: …)」，
     * 而这句话会原样出现在界面上（「哪一份读不出来」那条提示）。三种东西对用户没用：
     * 内部类名、源码位置、Jackson 自己的引用链。真正的原因常常在 cause 里
     * ——{@link ContextItem#of} 拦条目形态时抛的那两句就是人话，直接用它的。
     * 取不到才退回去把 Jackson 那句裁一裁（和 {@code TemplateStore} 那边同一个分寸）。
     */
    private static String reason(IOException e) {
        for (Throwable cause = e.getCause(); cause != null; cause = cause.getCause()) {
            if (cause instanceof IllegalArgumentException && cause.getMessage() != null) {
                return cause.getMessage();
            }
        }
        String message = e.getCause() == null || e.getCause().getMessage() == null
                ? e.getMessage()
                : e.getCause().getMessage();
        int cut = message.indexOf("\n at [Source");
        return cut > 0 ? message.substring(0, cut) : message;
    }

    /**
     * 导出一套上下文。
     *
     * <p>写之前校验，和 {@link com.specflow.task.TaskStore} 同一个理由：
     * 存得进去就该跑得起来。这里的条目引用走的是 {@link SafePathResolver}——
     * 与 spec 里那些 {@code context} 条目同一套规则，不是「导出时另算一套」。
     *
     * @return 写出的文件路径
     * @throws SpecflowException 名字非法、条目为空、条目没有名字、或引用越出项目根
     */
    public Path save(String name, List<ContextItem> items) {
        Path file = fileOf(name);
        if (items == null || items.isEmpty()) {
            throw new SpecflowException("这套上下文里一条内容都没有——至少要有一条文件引用或一段粘贴的文本");
        }
        SafePathResolver paths = new SafePathResolver(projectRoot);
        for (int i = 0; i < items.size(); i++) {
            ContextItem item = items.get(i);
            if (item == null || item.name() == null || item.name().isBlank()) {
                throw new SpecflowException("第 " + (i + 1) + " 条上下文没有名字："
                        + "界面和这里的历史都要靠名字分辨它是什么");
            }
            if (!item.hasRef()) {
                continue;
            }
            try {
                paths.resolve(item.ref());
            } catch (IllegalArgumentException e) {
                throw new SpecflowException("第 " + (i + 1) + " 条上下文「" + item.name()
                        + "」引用非法：" + e.getMessage());
            }
        }
        Bundle bundle = new Bundle(name, projectName(), STAMP.format(LocalDateTime.now()), List.copyOf(items));
        try {
            Files.createDirectories(directory);
            YAML.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), bundle);
        } catch (IOException e) {
            throw new SpecflowException("导出上下文失败 " + name + "：" + e.getMessage(), e);
        }
        return file;
    }

    // ---------- 内部 ----------

    /**
     * 名字即文件名，所以校验必须发生在拼路径之前。
     *
     * <p>{@code normalize()} 之后再确认一次仍在自己的目录下：正则已经挡住了分隔符，
     * 这一句是「万一正则以后被放松」的那道兜底——代价是一次字符串比较。
     */
    private Path fileOf(String name) {
        if (name == null || !NAME_PATTERN.matcher(name).matches()) {
            throw new SpecflowException("上下文名非法: '" + name
                    + "'（只能用中英文、数字、下划线、连字符，且不能超过 64 个字符）");
        }
        Path file = directory.resolve(name + EXTENSION).normalize();
        if (!file.startsWith(directory)) {
            throw new SpecflowException("上下文名非法: '" + name + "'");
        }
        return file;
    }

    private String projectName() {
        Path leaf = projectRoot.getFileName();
        return leaf == null ? projectRoot.toString() : leaf.toString();
    }

    private static String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? fileName : fileName.substring(0, dot);
    }
}
