package com.specflow.project;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * 认出一个目录「是什么项目」。
 *
 * <p>只做一件事：扫项目根，看有没有那几个<b>名字固定</b>的「清单文件」，
 * 从里面推断这个项目用什么语言、该拿哪条命令当编译校验。
 *
 * <p>为什么值得单独做：换一个你不熟的语言或项目时，你未必知道该把哪个文件带进上下文、
 * 该填什么编译命令。而这些名字是固定的，工具完全可以自己认，不该让人记。
 *
 * <p><b>分寸</b>：这里认的是「项目是什么」，不是「这次该改哪些代码」。前者名字固定，
 * 认错也无害——清单文件本来就是给模型看的；后者要判断，猜错会让模型照着错的代码写，
 * 所以那件事仍然由人点。
 */
public final class ProjectScanner {

    /**
     * 一条识别规则。
     *
     * @param file         清单文件名（必须精确匹配，不做模糊）
     * @param language     给界面看的一行说明
     * @param command      没有 wrapper 时建议的编译校验命令；{@code null} 表示这条规则不提供
     * @param wrapper      wrapper 的基础名（{@code mvnw} / {@code gradlew}）；没有就传 {@code null}
     * @param wrapperArgs  wrapper 后面的参数
     */
    private record Rule(String file, String language, String command,
                        String wrapper, String args) {
    }

    /**
     * 认得出的清单文件。
     *
     * <p>刻意只收<b>明确说明构建方式</b>的文件名：{@code settings.gradle} 之类
     * 单独出现时说明不了什么，放进来只会让界面多一行没用的东西。
     */
    private static final List<Rule> RULES = List.of(
            new Rule("pom.xml", "Java · Maven", "mvn -q -DskipTests compile", "mvnw",
                    "-q -DskipTests compile"),
            new Rule("build.gradle", "Java · Gradle", "gradle compileJava -q", "gradlew",
                    "compileJava -q"),
            new Rule("build.gradle.kts", "Kotlin · Gradle", "gradle compileKotlin -q", "gradlew",
                    "compileKotlin -q"),
            new Rule("package.json", "Node", "npm run build", null, null),
            new Rule("tsconfig.json", "TypeScript", "npx tsc --noEmit", null, null),
            new Rule("go.mod", "Go", "go build ./...", null, null),
            new Rule("Cargo.toml", "Rust", "cargo check", null, null),
            new Rule("pyproject.toml", "Python", "python -m compileall -q .", null, null),
            new Rule("requirements.txt", "Python", "python -m compileall -q .", null, null),
            new Rule("manage.py", "Python · Django", "python manage.py check", null, null),
            new Rule("CMakeLists.txt", "C++ · CMake", "cmake --build build", null, null),
            new Rule("Makefile", "C/C++ · Make", "make", null, null),
            new Rule("composer.json", "PHP", "composer validate --no-check-publish", null, null),
            new Rule("Gemfile", "Ruby", "bundle exec rake -T", null, null),
            new Rule("pubspec.yaml", "Dart / Flutter", "flutter analyze", null, null));

    /**
     * 一次扫描的结果。
     *
     * @param configured 目录里有没有 {@code .specflow/project.yaml}
     * @param manifests  认出来的清单文件；按下面 {@link #RULES} 的顺序
     * @param compileCommand 建议的编译校验命令；一条都认不出时为 {@code null}
     */
    public record Scan(boolean configured, List<ManifestFile> manifests, String compileCommand) {

        public boolean isEmpty() {
            return manifests.isEmpty();
        }
    }

    /** 一个清单文件：它叫什么、它说明这是个什么项目。 */
    public record ManifestFile(String file, String language) {
    }

    /** 扫一个目录，不写任何东西。 */
    public Scan scan(Path root) {
        Path dir = root.toAbsolutePath().normalize();
        List<Rule> matched = RULES.stream().filter(rule -> isFile(dir, rule.file())).toList();
        List<ManifestFile> manifests = matched.stream()
                .map(rule -> new ManifestFile(rule.file(), rule.language()))
                .toList();
        String command = matched.stream()
                .map(rule -> commandFor(dir, rule))
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
        return new Scan(isConfigured(dir), manifests, command);
    }

    /**
     * 这条规则该给出什么命令。
     *
     * <p>有 wrapper 就优先用 wrapper：wrapper 存在的<b>全部意义</b>就是
     * 「不问环境，直接用项目自己指定的那个版本」，所以它比 PATH 上的 mvn/gradle 可靠。
     * 这也是唯一一处能顺手解决「版本对不对」的地方。
     */
    private static String commandFor(Path dir, Rule rule) {
        if (rule.wrapper() != null) {
            String wrapper = wrapperCommand(dir, rule.wrapper());
            if (wrapper != null) {
                return wrapper + " " + rule.args();
            }
        }
        return rule.command();
    }

    /** wrapper 在哪、该怎么调；没有就返回 {@code null}。 */
    private static String wrapperCommand(Path dir, String base) {
        if (isFile(dir, base)) {
            // Windows 上 cmd 会自动补 .cmd，所以直接写 base 也能跑
            return onWindows() ? base : "./" + base;
        }
        return isFile(dir, base + ".cmd") ? base + ".cmd" : null;
    }

    /**
     * 这个目录算不算「已经配过 specflow」。
     *
     * <p>判据要和 {@link ProjectConfigLoader} 完全一致：<b>文件在、而且里面有内容</b>。
     * 只判「文件在」会出现一个死角——一份空的 {@code project.yaml} 让界面以为配过了、
     * 于是不给「初始化」按钮，而模板和编译命令一个都没有，用户没有任何出口。
     */
    private static boolean isConfigured(Path dir) {
        Path config = dir.resolve(ProjectConfigLoader.CONFIG_DIR)
                .resolve(ProjectConfigLoader.CONFIG_FILE);
        if (!Files.isRegularFile(config)) {
            return false;
        }
        try {
            return !ProjectConfigLoader.hasNoContent(Files.readString(config));
        } catch (IOException e) {
            // 读不出来就当它没配过：那正好是「点一下初始化」能修的情形
            return false;
        }
    }

    private static boolean isFile(Path dir, String name) {
        return Files.isRegularFile(dir.resolve(name));
    }

    private static boolean onWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
