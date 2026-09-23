package com.specflow.cli;

/**
 * 命令行输出。
 *
 * <p>只用 ASCII 前缀，不用颜色和图形符号：这套工具的输出会被贴进 issue、
 * 日志文件和其他人的终端里，颜色在那里只会变成乱码。
 */
public final class Console {

    private Console() {
    }

    public static void ok(String format, Object... args) {
        print("[OK] ", format, args);
    }

    public static void warn(String format, Object... args) {
        print("[WARN] ", format, args);
    }

    public static void fail(String format, Object... args) {
        print("[FAIL] ", format, args);
    }

    public static void info(String format, Object... args) {
        print("", format, args);
    }

    public static void detail(String format, Object... args) {
        print("       ", format, args);
    }

    private static void print(String prefix, String format, Object... args) {
        System.out.println(prefix + String.format(format, args));
    }
}
