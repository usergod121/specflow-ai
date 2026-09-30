package com.specflow.tests;

import java.util.Locale;

/**
 * 测试用的入口脚本。
 *
 * <p>抽出来是因为好几个测试类都要造一个「能跑、退出码受控」的脚本，而里面有两个坑，
 * 各写一遍就会各踩一遍：
 * <ul>
 *   <li><b>竖线要转义</b>——Windows 的 {@code echo} 里 {@code |} 是管道符。
 *       不转义的话「FAIL | 2 | 期望 | 实际」会被 shell 吃掉，测的就不是引擎的解析而是 cmd 的转义；</li>
 *   <li><b>换行用 CRLF</b>——{@code .cmd} 只认 Windows 换行，纯 {@code \n} 的行尾在个别命令上会翻车。</li>
 * </ul>
 */
public final class EntryScripts {

    public static final boolean WINDOWS = System.getProperty("os.name", "")
            .toLowerCase(Locale.ROOT).contains("win");

    private EntryScripts() {
    }

    /** 入口脚本的文件名——和引擎按平台取的那一个一致。 */
    public static String name() {
        return WINDOWS ? "run.cmd" : "run.sh";
    }

    /** 每行都是一个 {@code echo}，最后按给定退出码退出。 */
    public static String body(int exit, String... outputs) {
        String newline = WINDOWS ? "\r\n" : "\n";
        StringBuilder out = new StringBuilder(WINDOWS ? "@echo off" + newline : "#!/bin/sh" + newline);
        for (String output : outputs) {
            out.append("echo ").append(WINDOWS ? output.replace("|", "^|") : output).append(newline);
        }
        return out.append(WINDOWS ? "exit /b " : "exit ").append(exit).append(newline).toString();
    }
}
