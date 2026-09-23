package com.specflow.project;

import com.specflow.exception.SpecflowException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * 密钥从哪来。
 *
 * <p>只认两个地方，按优先级：
 * <ol>
 *   <li><b>环境变量</b>——临时换一个 key 的时候不用去动文件</li>
 *   <li><b>项目本地的 {@code .specflow/local.env}</b>——写一次，往后每次启动都不用再输</li>
 * </ol>
 *
 * <p>刻意不放进 {@code spec.yaml} 之类的配置：那些是要进版本库的，密钥进去就泄了。
 * {@code local.env} 在 {@code .gitignore} 里，只在本机读。
 *
 * <p>文件格式就是 {@code NAME=value} 一行一个，{@code #} 开头是注释。
 * 值外面包引号也认——有人写 {@code .env} 习惯带引号，不认的话引号会被当成密钥的一部分。
 */
public final class Secrets {

    /** 本地密钥文件，相对项目根。 */
    public static final String LOCAL_ENV = ".specflow/local.env";

    private Secrets() {
    }

    /**
     * 找一个密钥。
     *
     * <p>环境变量优先：它更临时，也更容易在某一次运行里单独覆盖掉文件里的值。
     *
     * @return 没找到时为空——要不要报错、怎么报，由调用方决定，
     *         因为「谁来告诉用户怎么配」在命令行和界面上是不一样的
     */
    public static Optional<String> lookup(Path projectRoot, String name) {
        return lookup(projectRoot, name, System::getenv);
    }

    /**
     * 同 {@link #lookup(Path, String)}，只是环境变量从一个参数取。
     *
     * <p>只为了能测「环境变量优先」这条规则：测试没法给进程设环境变量，
     * 而优先级一旦反了，表现是「文件里改了半天不生效」，很难查。
     */
    static Optional<String> lookup(Path projectRoot, String name, Function<String, String> environment) {
        String fromEnv = environment.apply(name);
        if (fromEnv != null && !fromEnv.isBlank()) {
            return Optional.of(fromEnv.strip());
        }
        return fromFile(projectRoot.resolve(LOCAL_ENV), name);
    }

    private static Optional<String> fromFile(Path file, String name) {
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new SpecflowException("读取 " + file + " 失败：" + e.getMessage(), e);
        }
        for (String line : lines) {
            String text = line.strip();
            if (text.isEmpty() || text.startsWith("#")) {
                continue;
            }
            int equals = text.indexOf('=');
            if (equals > 0 && text.substring(0, equals).strip().equals(name)) {
                String value = unquote(text.substring(equals + 1).strip());
                if (!value.isBlank()) {
                    return Optional.of(value);
                }
            }
        }
        return Optional.empty();
    }

    private static String unquote(String value) {
        boolean quoted = value.length() >= 2
                && ((value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'")));
        return quoted ? value.substring(1, value.length() - 1) : value;
    }
}
