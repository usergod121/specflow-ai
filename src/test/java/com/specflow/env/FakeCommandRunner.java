package com.specflow.env;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 一个假的命令执行器：<b>记下每一条命令，按规则回话</b>。
 *
 * <p>它存在的理由很直接：这台机器上没有 Docker，而环境这一摊的每一条分支
 * （起不来、拉不到、健康检查超时、init 失败、收残局）都必须被测到——
 * 只测「没装 Docker 时优雅降级」那一条，等于把这批里 90% 的代码放在测试之外。
 *
 * <p>它不管进程，只管「命令长什么样、拿到什么结果」，所以那些分支全都能在毫秒级跑完。
 * 真正碰进程的那一段由 {@link FakeDocker} 用真的 {@link ProcessCommandRunner} 验（灰盒）。
 */
public final class FakeCommandRunner implements CommandRunner {

    /** 一次调用：命令原文 + 给的时限（清理和起环境的时限不该是同一个数）。 */
    record Call(List<String> command, long timeoutSeconds) {

        /** 这一行是不是某段字面量。 */
        boolean contains(String fragment) {
            return line().contains(fragment);
        }

        String line() {
            return String.join(" ", command);
        }
    }

    /** 一条规则：命令里含某个字面量时，回什么。 */
    private record Rule(String fragment, Result result) {
    }

    /** 命中某段字面量时顺带做的事——用来模拟「东西真的被删掉了」。 */
    private record Effect(String fragment, Runnable action) {
    }

    private final List<Rule> rules = new ArrayList<>();
    private final List<Effect> effects = new ArrayList<>();
    private final List<Call> calls = new ArrayList<>();

    /** 一条规则都不匹配时回什么。默认是「这条命令不存在」——就是这台机器的现状。 */
    private final Result fallback = Result.notStarted("'docker' 不是内部或外部命令");

    public FakeCommandRunner ok(String fragment, String output) {
        return rule(fragment, Result.finished(0, output));
    }

    public FakeCommandRunner fail(String fragment, int exit, String output) {
        return rule(fragment, Result.finished(exit, output));
    }

    /** 命中这条规则时「命令不存在」——用来验三级探测。 */
    FakeCommandRunner notFound(String fragment) {
        return rule(fragment, Result.notStarted("command not found"));
    }

    FakeCommandRunner timeout(String fragment, String output) {
        return rule(fragment, new Result(true, Result.NOT_STARTED, true, output));
    }

    /**
     * 命中这段字面量时顺带做一件事。
     *
     * <p>用来模拟「这条命令真的改了点什么」：清理跑完之后，那个查询命令的答案必须跟着变空——
     * 不变的假 runner 会让「收干净了」这条唯一的成功路径永远测不到，
     * 而它正是「清理做成了没有」的判据。
     */
    FakeCommandRunner on(String fragment, Runnable action) {
        effects.add(new Effect(fragment, action));
        return this;
    }

    private FakeCommandRunner rule(String fragment, Result result) {
        rules.add(new Rule(fragment, result));
        return this;
    }

    @Override
    public Result run(List<String> command, Map<String, String> environment, Path workdir,
                      long timeoutSeconds) {
        calls.add(new Call(List.copyOf(command), timeoutSeconds));
        String line = String.join(" ", command);
        // 副作用先做：它是「这条命令真的改了点什么」，而查询命令的答案要立刻反映出来
        effects.stream().filter(effect -> line.contains(effect.fragment()))
                .forEach(effect -> effect.action().run());
        // 后加的规则先匹配：测试里「特例」总是写在「通例」之后
        for (int i = rules.size() - 1; i >= 0; i--) {
            if (line.contains(rules.get(i).fragment())) {
                return rules.get(i).result();
            }
        }
        return fallback;
    }

    // ---------- 给断言用的 ----------

    List<Call> calls() {
        return List.copyOf(calls);
    }

    /** 这次跑过的命令，一行一条。断言「跑了哪几条、什么顺序」直接比它。 */
    public List<String> lines() {
        return calls.stream().map(Call::line).toList();
    }

    /** 有没有一条命令包含这段字面量。 */
    public boolean ran(String fragment) {
        return calls.stream().anyMatch(call -> call.contains(fragment));
    }

    /** 第一条包含这段字面量的命令；没有返回 {@code null}。 */
    String first(String fragment) {
        return calls.stream().filter(call -> call.contains(fragment)).map(Call::line)
                .findFirst().orElse(null);
    }

    /** 所有包含这段字面量的命令。 */
    List<String> all(String fragment) {
        return calls.stream().filter(call -> call.contains(fragment)).map(Call::line).toList();
    }

    /** 这条命令在整串调用里排第几（从 0 数）；没有返回 -1。 */
    int indexOf(String fragment) {
        for (int i = 0; i < calls.size(); i++) {
            if (calls.get(i).contains(fragment)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 这几类资源从现在起都不存在了（{@code ps -a} / {@code volume ls} / {@code network ls} 返回空）。
     *
     * <p>给 {@link #on} 用：清理跑完之后，查询的答案必须跟着变空，
     * 否则「收干净了」这条唯一的成功路径永远测不到。
     */
    void clear() {
        rules.add(new Rule("ps -a", Result.finished(0, "")));
        rules.add(new Rule("volume ls", Result.finished(0, "")));
        rules.add(new Rule("network ls", Result.finished(0, "")));
    }
}
