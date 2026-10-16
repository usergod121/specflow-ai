package com.specflow;

import com.specflow.core.Project;
import com.specflow.env.EnvCli;
import com.specflow.web.App;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 入口。三个子命令，够用就行（不引命令行框架：这一层不值得多一个依赖）。
 *
 * <pre>
 *   specflow web  [--port 8780] [--no-open] [-p &lt;项目&gt;]   起界面
 *   specflow env  init|reset|status|down [-p &lt;项目&gt;]      不开界面，直接伺候环境
 *   specflow version
 * </pre>
 */
public final class Main {

    public static void main(String[] args) {
        List<String> rest = new ArrayList<>(List.of(args));
        String command = rest.isEmpty() ? "web" : rest.remove(0).toLowerCase(Locale.ROOT);
        switch (command) {
            case "web" -> App.run(port(rest), project(rest), !rest.contains("--no-open"));
            case "env" -> System.exit(EnvCli.run(rest));
            case "version", "-v", "--version" -> System.out.println("specflow 2.0.0");
            case "-h", "--help", "help" -> System.out.println("""
                    specflow 2.0.0

                      specflow web  [--port 8780] [--no-open] [-p <项目>]   起界面
                      specflow env  init|reset|status|down [-p <项目>]     伺候环境
                      specflow version
                    """);
            default -> {
                System.err.println("不认识的命令：" + command + "（试试 specflow --help）");
                System.exit(2);
            }
        }
    }

    private static int port(List<String> args) {
        for (int at = 0; at < args.size(); at++) {
            if ("--port".equals(args.get(at)) && at + 1 < args.size()) {
                return Integer.parseInt(args.get(at + 1));
            }
        }
        return 8780;
    }

    private static Path project(List<String> args) {
        for (int at = 0; at < args.size(); at++) {
            if ("-p".equals(args.get(at)) && at + 1 < args.size()) {
                return Path.of(args.get(at + 1));
            }
        }
        return null;
    }


}
