package com.specflow.tests;

import com.specflow.exception.BlockedCommandException;
import com.specflow.exception.SpecflowException;
import com.specflow.patch.PatchBlock;
import com.specflow.util.ProjectFiles;
import com.specflow.util.SafePathResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 测试产物的落位：{@code tools/<时间戳>/} 这一个目录，别的哪儿都不去。
 *
 * <p><b>为什么不落在目标文件清单里。</b>清单是开发阶段唯一能改的东西，而测试代码必须
 * <b>被验的那一方改不动</b>——否则「把断言改成实际值」是最省事的一条路，测试就永远绿了。
 * 这不是洁癖：这是这套校验唯一的结构性防作弊手段（十五.6）。所以这个目录有自己的一份白名单，
 * 由本类守着，和 {@code spec.targets()} 互不相干。
 *
 * <p>它还守着第二件事：生成出来的东西<b>能不能跑</b>。它自己只拦得住高危命令的字面匹配
 * （{@link #forbidden}）——那挡不住变体，真正的兜底是容器隔离（十五.9）。
 * 而「这次到底有没有容器兜底」由 {@link ExecutionLocation} 一处判断，会写进结果与留档。
 * 所以这里的态度是：宁可多拦一条让人来问，也不放过一条真会删盘的。
 *
 * <p>不实现 {@code AutoCloseable}：产物在失败时要<b>留着</b>给人看（那是失败清单的现场），
 * 什么时候删是一次显式的 {@link #delete()}，不能交给 try-with-resources 顺手做掉。
 */
public final class TestArtifacts {

    private static final Logger log = LoggerFactory.getLogger(TestArtifacts.class);

    /** 产物目录挂在项目根下的哪一层。 */
    public static final String ROOT = "tools";

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /**
     * 集成入口脚本（十五.4）。
     *
     * <p>为什么要两个入口：单元测试不需要任何外部服务，而集成测试要连库、要连中间件——
     * 那些服务只在<b>容器网络里</b>有名有姓（十五.5：默认不暴露宿主端口，容器间用服务名互连）。
     * 所以「跑单元」和「跑集成」是两条真的不一样的路，而引擎只会执行<b>一个</b>文件：
     * 让一个脚本按环境变量自己分叉，等于把「这次跑的是哪条路」藏进脚本内部，
     * 而留档里必须一眼看得出跑的是哪一条。
     *
     * <p><b>两个名字都从 {@link ExecutionLocation} 来</b>，不在这里按平台取：
     * 脚本的名字取决于它在哪儿跑——进了容器就是 Linux，宿主上那套 {@code .cmd} 在那儿
     * 一个字都跑不了。名字与命令分在两处判，得到的就是「给了脚本却说没给」。
     */

    /** 本机是不是 Windows。它决定路径比对要不要把大小写放平。 */
    private static final boolean WINDOWS = System.getProperty("os.name", "")
            .toLowerCase(Locale.ROOT).contains("win");

    /** 摘给用户看的证据长度上限：够看清是什么，又不至于把整段糊上去。 */
    private static final int MAX_EVIDENCE = 200;

    /**
     * 高危写法：命中就整批拒绝落盘、也不执行。
     *
     * <p>判据按「造成了什么」写，不按「叫什么」写，所以有几条比 §15.8 的字面更窄一点：
     * {@code dd} 只在带 {@code if=}/{@code of=} 时才算（裸 {@code dd} 在 Java 里是变量名的概率
     * 比是命令大得多）。反过来 {@code sudo}、{@code mkfs}、{@code --privileged}、
     * 挂宿主根、{@code $HOME}、{@code docker.sock} 一律按字面拦——它们没有无辜的用法。
     *
     * <p><b>每条都带 {@code CASE_INSENSITIVE}，这不是可选项。</b>Windows 的命令行不认大小写：
     * {@code SUDO}、{@code Rm -Rf}、{@code --PRIVILEGED} 和小写是同一个东西。
     * 只拦小写等于留了一条「把字母改大写就绕过去」的路——而模型不一定要故意，
     * 它照抄一段别人写的脚本就会带上大写。
     */
    private static final List<Pattern> FORBIDDEN = List.of(
            Pattern.compile("\\bsudo\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bmkfs(\\.\\w+)?\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("--privileged\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bdd\\b[^\\n]*(if|of|bs|count)=", Pattern.CASE_INSENSITIVE),
            Pattern.compile("docker\\.sock", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\$\\{?home\\}?|%userprofile%", Pattern.CASE_INSENSITIVE),
            // 把宿主根/盘根/家目录挂进容器：-v /:/host、-v /c/:/host、--volume=/、-v C:\:/x
            Pattern.compile("(-v|--volume)[= ]\\s*[\"']?(/|[a-z]:[\\\\/]?|/[a-z]/)(?=[:\\s\"']|$)",
                    Pattern.CASE_INSENSITIVE),
            // --mount type=bind,source=/,target=/host 这一种写法
            Pattern.compile("--mount[^,]*(,|\\s)source\\s*=\\s*[\"']?(/|[a-z]:[\\\\/]?)(?=[,\\s\"']|$)",
                    Pattern.CASE_INSENSITIVE),
            // compose 长语法里的挂载源头：source: "/" / source: "C:/"
            Pattern.compile("source\\s*[:=]\\s*[\"']?(/|[a-z]:[\\\\/]?)[\"']?(\\s|$)",
                    Pattern.CASE_INSENSITIVE),
            // 容器直接借用宿主的网络栈：--network host / --net=host / network_mode: "host"。
            // 借了宿主网络，容器隔离就只剩个壳——里面的东西能直接连宿主的一切，
            // 而「真正的结构性兜底是容器隔离」正是这套设计的前提（十五.9）
            Pattern.compile("(--net|--network)[= ]+host\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("network_mode\\s*[:=]\\s*[\"']?host\\b", Pattern.CASE_INSENSITIVE),
            // 往块设备上写：dd 那一路之外，重定向也一样能把盘写烂
            Pattern.compile(">>?\\s*/dev/(sd|hd|nvme|vd|disk|mapper)", Pattern.CASE_INSENSITIVE),
            // 拉外网脚本直接交给 shell 执行
            Pattern.compile("\\b(curl|wget|iwr|invoke-webrequest)\\b[^|]*\\|\\s*(sudo\\s+)?(ba|z|da|k)?sh\\b",
                    Pattern.CASE_INSENSITIVE));

    /** 破坏类命令：删文件、抹内容。命中之后还要看它动的是什么（见 {@link #deletesProjectFiles}）。 */
    private static final Pattern DESTRUCTIVE = Pattern.compile(
            "\\b(rm|unlink|shred|rmdir|rd|deltree|del|erase|remove-item|clear-content|truncate)\\b",
            Pattern.CASE_INSENSITIVE);

    /**
     * 批量旗标：带上它就不是删一个文件，而是成片地删。
     *
     * <p>三个系统各有一套写法，都要收：POSIX 的 {@code -rf}/{@code -fr}/{@code -f -r}、
     * PowerShell 的 {@code -Recurse}/{@code -Force}、cmd 的 {@code /s}/{@code /q}/{@code /f}。
     * 这些旗标一出现，删的是什么就已经算不清了，不看目标也拒。
     */
    private static final Pattern BULK_FLAG = Pattern.compile(
            "(^|\\s)(-[a-z]*[rf][a-z]*|--?[a-z]*(recursive|recurse|force)|/[sqf])(\\s|$)",
            Pattern.CASE_INSENSITIVE);

    /**
     * 删的目标沾上这些，就是动了不该动的。
     *
     * <p>怎么算「不该动」：产品代码（{@code src/}）、编译产物（{@code target/}）、
     * 通配符（删一片）、盘符或绝对路径、上级目录、环境变量展开的路径——
     * 后面这几种的共同点是<b>范围说不清</b>。产物目录里的那点清理
     * （{@code rm build/out.txt}）不在其列：测试脚本删自己的临时文件是正常的动作。
     */
    private static final List<Pattern> PROJECT_MARKERS = List.of(
            Pattern.compile("src[/\\\\]", Pattern.CASE_INSENSITIVE),
            Pattern.compile("target[/\\\\]", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\*"),
            Pattern.compile("[a-z]:[/\\\\]", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(^|[\\s\"'=])\\.\\.?([/\\\\]|\\s|$)"),
            Pattern.compile("%[a-z_]+%|\\$\\{?[a-z_]+\\}?", Pattern.CASE_INSENSITIVE));

    private final SafePathResolver pathResolver;
    private final Path directory;
    private final String relative;
    private final ExecutionLocation location;

    private TestArtifacts(SafePathResolver pathResolver, Path directory, String relative,
                          ExecutionLocation location) {
        this.pathResolver = pathResolver;
        this.directory = directory;
        this.relative = relative;
        this.location = location;
    }

    /**
     * 开一个本次运行的产物目录。
     *
     * <p>时间戳撞车（同一秒里跑了两次）时不覆盖别人的目录，往后找一个空位：
     * 覆盖掉的可能正是上一次那条失败清单指向的那份测试代码。
     */
    public static TestArtifacts create(Path projectRoot) {
        return create(projectRoot, ExecutionLocation.host());
    }

    /**
     * 开一个本次运行的产物目录，这一次带上<b>执行位置</b>。
     *
     * <p>位置要在这里就定下来：入口脚本的<b>名字</b>由它决定，而名字在生成阶段
     * 就要写进协议交给模型（写错名字的表现是「产物里没有入口脚本」，与事实不符）。
     *
     * @param location 这次脚本在哪儿跑，见 {@link ExecutionLocation}
     */
    public static TestArtifacts create(Path projectRoot, ExecutionLocation location) {
        SafePathResolver resolver = new SafePathResolver(projectRoot);
        String name = LocalDateTime.now().format(STAMP);
        Path directory = resolver.resolve(ROOT + "/" + name);
        for (int suffix = 2; Files.exists(directory); suffix++) {
            directory = resolver.resolve(ROOT + "/" + name + "-" + suffix);
        }
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            throw new SpecflowException("建不了测试产物目录 " + directory + "：" + e.getMessage(), e);
        }
        return new TestArtifacts(resolver, directory, resolver.relativize(directory),
                location == null ? ExecutionLocation.host() : location);
    }

    /** 产物目录相对项目根的路径（POSIX 风格），落档与给人看都用它。 */
    public String relative() {
        return relative;
    }

    /** 这次脚本在哪儿跑。入口名字与引擎要执行的命令都由它来。 */
    public ExecutionLocation location() {
        return location;
    }

    /** 入口脚本相对项目根的路径——引擎要执行的就是它。 */
    public String entry() {
        return relative + "/" + location.unitEntryName();
    }

    /**
     * 集成入口脚本相对项目根的路径（十五.4 的 {@code run-it}）。
     * 只有勾了集成测试才会去跑它。
     */
    public String integrationEntry() {
        return relative + "/" + location.integrationEntryName();
    }

    /** 产物里有没有集成入口脚本。勾了集成测试却没给这个文件，就是「没东西可跑」。 */
    public boolean hasIntegrationEntry(List<String> written) {
        return written.stream().anyMatch(this::isIntegrationEntry);
    }

    /**
     * 把模型给的补丁块落盘，返回写了哪些文件（相对项目根的路径，按块序）。
     *
     * <p>逐个校验、逐个写：补丁块的路径是模型给的，而这一次<b>没有任何别的白名单兜底</b>——
     * 目标文件那套约束在这里不适用。所以越界、非新建、空文件这三件事必须在这里当场拦住，
     * 一个字节都不能先写出去。
     *
     * @throws SpecflowException 路径越界、给了锚点（不是新建）、内容为空、或命中高危命令
     */
    public List<String> write(List<PatchBlock> blocks) {
        List<String> written = new ArrayList<>(blocks.size());
        for (PatchBlock block : blocks) {
            int ordinal = block.index() + 1;
            if (!block.isFullWrite()) {
                throw new SpecflowException("第 " + ordinal + " 个补丁块给了 SEARCH 锚点："
                        + "测试产物都是新建文件，SEARCH 段落要留空，REPLACE 段落放完整文件内容");
            }
            if (block.replace().isBlank()) {
                throw new SpecflowException("第 " + ordinal + " 个补丁块是空的（" + block.path() + "）");
            }
            String shown = shown(block, ordinal);
            refuseForbidden(block, shown);
            ProjectFiles.writeAtomic(pathResolver.resolve(shown), block.replace(), shown);
            written.add(shown);
        }
        return List.copyOf(written);
    }

    /**
     * 整批删掉（一个字节都没写成功、或者环境问题回滚之后，这里不该留东西）。
     *
     * <p>删不掉只记一句警告：残留的产物是脏，不是错——为它把一次已经跑出结论的运行
     * 掀成异常，比留一个目录糟得多。目录在项目里是看得见的，下次也能手工处理。
     */
    public void delete() {
        if (!Files.isDirectory(directory)) {
            return;
        }
        try (var paths = Files.walk(directory)) {
            // 先深后浅：目录要在它的内容之后删
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException e) {
            log.warn("清理测试产物失败 {}：{}", relative, e.getMessage());
        }
    }

    /**
     * 删掉某一个产物目录（十五.8：接受/中断时要删测试产物）。
     *
     * <p>它和 {@link #delete()} 的差别是「按路径删」而不是「按这个实例删」：
     * 接受/中断发生在运行<b>结束之后</b>，那时候手里只有留档里记的那个路径。
     *
     * <p>路径照样要先过白名单：它来自留档（可能被手工改过），而删东西这件事
     * 只允许发生在 {@code tools/} 底下——一个被改坏的记录不该能删掉别的目录。
     *
     * @param directory 产物目录（相对项目根）；空串表示这次没有产物
     * @return 那个目录现在还<b>在不在</b>。{@code true} = 已经不在了（删掉了，或者本来就没有），
     *         {@code false} = 没删成（路径不在 {@code tools/} 下、或者删不动）。
     *         收场要据此如实报出来——「以为删了、其实还在」正是清理漏掉一整轮的那种方式
     */
    public static boolean delete(Path projectRoot, String directory) {
        if (directory == null || directory.isBlank()) {
            return true;
        }
        SafePathResolver resolver = new SafePathResolver(projectRoot);
        Path target;
        try {
            target = resolver.resolve(directory);
        } catch (IllegalArgumentException e) {
            log.warn("留档里的产物路径不合法，不删：{}", directory);
            return false;
        }
        String shown = resolver.relativize(target);
        if (!shown.equals(ROOT) && !shown.startsWith(ROOT + "/")) {
            // 只删产物目录，一个字符都不能越界：这条路径是留档里的字符串，
            // 而留档是磁盘上的文件（用户可能手工改过，工具也可能被改坏）
            log.warn("留档里的产物路径不在 {}/ 下，不删：{}", ROOT, shown);
            return false;
        }
        try (var paths = Files.walk(target)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
            log.info("已清掉测试产物 {}", shown);
            return true;
        } catch (IOException e) {
            log.warn("清理测试产物失败 {}：{}", shown, e.getMessage());
            return false;
        }
    }

    // ---------- 白名单与高危 ----------

    /**
     * 这个块要写的文件在不在产物目录里。
     *
     * @throws SpecflowException 路径非法（绝对路径、越出项目根）或不在 {@code tools/<时间戳>/} 下
     */
    private String shown(PatchBlock block, int ordinal) {
        Path file;
        try {
            file = pathResolver.resolve(block.path());
        } catch (IllegalArgumentException e) {
            throw new SpecflowException("第 " + ordinal + " 个补丁块的路径不能用（"
                    + block.path() + "）：" + e.getMessage());
        }
        String path = pathResolver.relativize(file);
        if (!inArtifacts(path)) {
            throw new SpecflowException("第 " + ordinal + " 个补丁块想写 " + block.path()
                    + "：测试产物只能写在 " + relative + "/ 里。产品代码由开发阶段那一套改，"
                    + "测试代码改产品代码就等于自己给自己判卷");
        }
        // 返回按产物目录的**真实拼写**拼出来的路径，不返回模型写的那一份：
        // Windows 上它把 tools 写成 TOOLS，文件落在同一个目录里，而 entry() 用的是真实拼写，
        // 两个字符串对不上就会被上游判成「没给入口脚本」，整批产物被拒，
        // 报出来的原因还和真实情况无关（实测过）
        return relative + path.substring(relative.length());
    }

    /**
     * 这个相对项目根的路径在不在产物目录里。
     *
     * <p>Windows 的文件系统不认大小写、Java 的路径比对认：模型把 {@code tools} 写成
     * {@code TOOLS} 时，文件明明在同一个目录里，这里却会说不认识。所以只在 Windows 上
     * 把大小写放平——别的系统上 {@code TOOLS/} 真的就是另一个目录，那时该拒还是要拒。
     */
    private boolean inArtifacts(String path) {
        int length = relative.length();
        if (path.length() <= length || path.charAt(length) != '/') {
            return false;
        }
        return same(path.substring(0, length), relative);
    }

    /**
     * 这个相对路径是不是引擎要执行的那个<b>单元</b>入口脚本。
     *
     * <p>比 {@link #entry()} 时要把大小写放平：Windows 上模型写的 {@code RUN.CMD}
     * 和 {@code run.cmd} 是<b>同一个文件</b>，字符串比不过就会又谎称一次「没给入口脚本」——
     * 和目录那段拼错时是同一种错，都在拿字符串比文件系统才懂的事。
     *
     * <p>它<b>不</b>认集成入口：这两个名字代表两条不同的路，而「有没有单元入口」和
     * 「有没有集成入口」是两个各自独立的检查（见 {@link TestAgent}）。
     * 合成一个「两个里有一个就行」，缺哪个都会看不出来。
     */
    public boolean isEntry(String path) {
        return same(entry(), path);
    }

    /** 这个相对路径是不是集成入口脚本。 */
    public boolean isIntegrationEntry(String path) {
        return same(integrationEntry(), path);
    }

    /** Windows 上按文件系统的规矩比（不认大小写），别的系统上按字节比。 */
    private static boolean same(String expected, String actual) {
        if (actual == null) {
            return false;
        }
        return WINDOWS ? expected.equalsIgnoreCase(actual) : expected.equals(actual);
    }

    /**
     * 整块内容过一遍高危判据。
     *
     * <p>为什么按<b>内容</b>而不是按行：命令可能被换行切开（见 {@link #forbidden}），
     * 那种写法单看哪一行都不像，逐行判就会漏。
     */
    private void refuseForbidden(PatchBlock block, String shown) {
        String evidence = forbidden(block.replace());
        if (evidence != null) {
            // 用专门的类型抛（见 BlockedCommandException 的注释）：这一档要单独记成一条
            // 「被安全拦截」的失败项、并且值得把拒绝原因喂回去再生成一版——
            // 而协议不符、路径越界那两种不重试。光靠文字认这两种，改一个字就认不出来了
            throw new BlockedCommandException("生成的测试产物里有高危命令，已经拒绝落盘、也不会执行："
                    + shown + " 里的「" + evidence + "」。"
                    + "测试脚本只该跑测试——要动系统、要挂宿主目录、要删产品代码的写法一律不接受");
        }
    }

    /**
     * 内容里有没有高危写法。
     *
     * <p>先把<b>被换行切开的命令接回去</b>（cmd 用 {@code ^}、sh 用 {@code \}、
     * PowerShell 用反引号续行），再逐条逻辑行判。为什么这一条不能省：拆开之后每一行都人畜无害，
     * {@code rm -r} 一行、{@code -f /} 一行，只有接回去才是那条真要命的命令。
     *
     * <p>判据一律在<b>大小写无关 + 空白折叠</b>之后匹配：{@code SUDO}、{@code Rm -Rf}、
     * {@code --PRIVILEGED} 和小写是同一个写法，多几个空格也不该改变结论。
     *
     * <p><b>公开给 {@code com.specflow.env}</b>：测试环境的 {@code init/reset} 命令与生成的
     * compose 内容走的是同一道闸。两处各写一份「高危表」，迟早有一处少一条——
     * 而少的那一条正好是能删库的那条。
     *
     * <p>它判的是<b>宿主上执行</b>的东西（AI 生成的测试脚本、生成的 compose 内容）：
     * 一个字都不放宽。容器内执行的那一类走 {@link #forbiddenInContainer}。
     *
     * @param content 一个补丁块的完整内容（也可以只给一行）
     * @return 命中的那段原文（给用户看凭什么拦），没有就返回 {@code null}
     */
    public static String forbidden(String content) {
        return forbidden(content, null);
    }

    /**
     * 在<b>容器里</b>执行的一条命令有没有高危写法（十五.5 的 {@code init} / {@code reset}）。
     *
     * <p>它和 {@link #forbidden} 的差别只有一处：<b>删东西的目标</b>这一条按容器内的
     * 命名空间来判。理由是「清库」这件正常事被误拒了——{@code rm -rf /data/*}（MySQL/Redis
     * 容器的数据目录）在宿主上确实是「删根目录下的东西」，在容器里只是删它自己的数据；
     * 拿宿主那把尺子量它，用户会得到一句「测试环境只该跑测试」，而他写的是最正当不过的一行。
     *
     * <p><b>为什么这个例外是安全的：</b>
     * <ol>
     *   <li>这些命令由引擎包成 {@code docker compose exec -T app sh -c "<命令>"} 执行，
     *       跑在<b>容器的挂载命名空间里</b>——{@code /data} 是容器自己的文件系统
     *       （镜像层或它自己的卷），不是这台机器上的 {@code /data}；</li>
     *   <li>容器里唯一看得见的宿主路径是项目目录（生成的 compose 只挂它一个，见
     *       {@code ComposeFile}），而例外<b>不覆盖它</b>：目标等于挂载点、在挂载点里面、
     *       或者是个相对路径（相对路径就是挂载点底下的东西）一律照旧拒绝；</li>
     *   <li>{@link #FORBIDDEN} 那张表<b>一个字都没放宽</b>——提权、写块设备、挂宿主根、
     *       {@code docker.sock}、借宿主网络、{@code $HOME}、{@code curl | sh} 要么是容器逃逸、
     *       要么本来就冲着宿主去，它们和「清自己容器里的数据」不是一回事；</li>
     *   <li>例外只给 {@code env.yaml} 里的 {@code init/reset}，也就是<b>用户自己写的</b>那几行；
     *       AI 生成的测试脚本走 {@link #forbidden}，一个字都不放宽——那是模型写的、
     *       而且是在宿主上真跑的。</li>
     * </ol>
     *
     * @param content          一条命令（或一整块内容）
     * @param containerWorkdir 项目目录挂进容器里的位置（如 {@code /work}）。
     *                         为空表示「不是在容器里执行」——那就按 {@link #forbidden} 判
     */
    public static String forbiddenInContainer(String content, String containerWorkdir) {
        return containerWorkdir == null || containerWorkdir.isBlank()
                ? forbidden(content)
                : forbidden(content, normalizeWorkdir(containerWorkdir));
    }

    private static String forbidden(String content, String containerWorkdir) {
        if (content == null || content.isBlank()) {
            return null;
        }
        for (String line : logicalLines(content)) {
            String hit = lineHit(line, containerWorkdir);
            if (hit != null) {
                return hit;
            }
        }
        return null;
    }

    /** 一条逻辑行里有没有高危写法。 */
    private static String lineHit(String line, String containerWorkdir) {
        String text = collapse(line);
        for (Pattern pattern : FORBIDDEN) {
            if (pattern.matcher(text).find()) {
                return clip(text);
            }
        }
        return deletesProjectFiles(text, containerWorkdir) ? clip(text) : null;
    }

    /**
     * 「删东西」的命令是不是冲着项目里的文件去的。
     *
     * <p>为什么要看目标，而不是见到 {@code del}／{@code rm} 就拒：这两个词在正常的测试代码里
     * 到处都是（Python 的 {@code del} 语句、{@code os.rmdir(路径)}），只看词会把整批产物拒掉。
     * 而只看词不看目标的另一半代价更大——{@code del /s /q "src\main\java\com\demo\*.java"}
     * 正好是「把产品源码删了还报通过」那一例（实测跑出来过）。
     *
     * <p>所以判据是<b>动词 + 目标</b>，而且目标这一头刻意粗（通配符、盘符、上级目录、
     * 变量、批量旗标都算）。两边不对称：多拦一条只是让人来看一眼，
     * 放过一条就是产品代码没了、而这次运行还写着「通过」。
     *
     * @param workdir 在容器里执行时，项目目录挂载点的位置；{@code null} = 宿主执行（不放宽）
     */
    private static boolean deletesProjectFiles(String text, String workdir) {
        var matcher = DESTRUCTIVE.matcher(text);
        while (matcher.find()) {
            String rest = text.substring(matcher.end());
            if (!BULK_FLAG.matcher(rest).find()
                    && PROJECT_MARKERS.stream().noneMatch(marker -> marker.matcher(rest).find())) {
                continue;
            }
            // 容器内的受控例外：这一次删的目标全在容器自己的文件系统里，且碰不到挂载进来的
            // 项目目录（怎么判、为什么安全见 forbiddenInContainer）。**只看这一处动词的目标**——
            // 这一行里若还有第二处删东西的命令，下一轮循环会单独判它
            if (workdir != null && onlyContainerTargets(rest, workdir)) {
                continue;
            }
            return true;
        }
        return false;
    }

    /**
     * 这一段里的删除目标是不是「只碰容器自己的盘」。
     *
     * <p>判到下一个 shell 分隔符为止（{@code ; && || | &}）：那之后是另一条命令，
     * 而它要是也删东西，{@link #deletesProjectFiles} 的循环会在下一轮单独判它——
     * 于是 {@code rm -rf /data/* ; rm -rf /work} 这种「一条干净的 + 一条要命的」照样被拦。
     *
     * <p>四条都成立才算通过，任何一条说不清就拒：
     * <ol>
     *   <li>是个绝对路径（相对路径的落点是容器的 cwd，也就是挂载进来的项目目录）；</li>
     *   <li>没有 {@code ..}（能爬出自己那个目录）；</li>
     *   <li>没有变量展开（{@code $DIR}、{@code %DIR%} 指向哪儿这里看不出来）；</li>
     *   <li>它（去掉尾部的 {@code *} 与 {@code /} 之后）不是 {@code /}，也不等于挂载点、
     *       不在挂载点底下。<b>这一条是例外的边界</b>：容器里唯一能伤到用户代码的地方就是那儿。</li>
     * </ol>
     *
     * <p>解析不出任何目标（{@code rm -rf} 后面什么都没有）时判「不通过」：
     * 说不清目标在哪的批量删除，不放行。
     */
    private static boolean onlyContainerTargets(String rest, String workdir) {
        List<String> targets = deletionTargets(rest);
        if (targets.isEmpty()) {
            return false;
        }
        for (String target : targets) {
            if (!target.startsWith("/") || target.contains("..")
                    || target.indexOf('$') >= 0 || target.indexOf('%') >= 0) {
                return false;
            }
            String base = stripWildcards(target);
            if (base.isEmpty() || base.equals(workdir) || base.startsWith(workdir + "/")) {
                return false;
            }
        }
        return true;
    }

    /** 一段文本里被删的那些目标：到 shell 分隔符为止，去掉旗标与引号。 */
    private static List<String> deletionTargets(String rest) {
        String head = rest.split("[;&|]", 2)[0];
        List<String> targets = new ArrayList<>();
        for (String raw : head.strip().split("\\s+")) {
            String token = unquote(raw.strip());
            // 旗标（-rf、--force）不是目标；分隔符已经切掉了，剩下的空串也不要
            if (token.isEmpty() || token.startsWith("-")) {
                continue;
            }
            targets.add(token);
        }
        return targets;
    }

    private static String unquote(String token) {
        String text = token;
        while (!text.isEmpty() && (text.charAt(0) == '"' || text.charAt(0) == '\'')) {
            text = text.substring(1);
        }
        while (!text.isEmpty() && (text.endsWith("\"") || text.endsWith("'"))) {
            text = text.substring(0, text.length() - 1);
        }
        return text;
    }

    /** 去掉末尾的通配符与斜杠，得到「这次删的是哪个目录」——{@code /data/*} 与 {@code /data} 是同一个。 */
    private static String stripWildcards(String target) {
        String text = target;
        while (text.endsWith("*") || text.endsWith("/")) {
            text = text.substring(0, text.length() - 1);
        }
        return text;
    }

    /** 挂载点的写法归一：去掉末尾的斜杠，免得 {@code /work/} 与 {@code /work} 被当成两个地方。 */
    private static String normalizeWorkdir(String workdir) {
        String text = workdir.strip();
        while (text.length() > 1 && text.endsWith("/")) {
            text = text.substring(0, text.length() - 1);
        }
        return text;
    }

    /**
     * 按逻辑行切开：行尾的续行符把下一行接上来，续行符本身不参与匹配。
     *
     * <p>这不是「顺手也看一下下一行」，而是<b>那本来就是一条命令</b>：
     * shell 会在执行前把续行符和换行一起吃掉，引擎看到的却是两行。
     */
    private static List<String> logicalLines(String content) {
        List<String> lines = new ArrayList<>();
        StringBuilder current = null;
        for (String raw : content.split("\\R")) {
            String line = raw.strip();
            if (current == null) {
                current = new StringBuilder();
            } else {
                current.append(' ');
            }
            if (continues(line)) {
                current.append(line, 0, line.length() - 1);
                continue;
            }
            current.append(line);
            lines.add(current.toString());
            current = null;
        }
        if (current != null) {
            lines.add(current.toString());
        }
        return lines;
    }

    private static boolean continues(String line) {
        return line.endsWith("^") || line.endsWith("\\") || line.endsWith("`");
    }

    /** 空白折叠：换行、制表、连续空格都算一个空格——命令的写法不该被排版左右。 */
    private static String collapse(String line) {
        return line.strip().replaceAll("\\s+", " ");
    }

    private static String clip(String text) {
        return text.length() <= MAX_EVIDENCE ? text : text.substring(0, MAX_EVIDENCE) + "…";
    }
}
