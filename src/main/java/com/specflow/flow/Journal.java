package com.specflow.flow;

import com.specflow.core.Atomic;
import com.specflow.core.Event;
import com.specflow.core.Json;
import com.specflow.core.Project;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * <b>崩溃恢复那本账</b>：任何一次运行开工先写 {@code BEGIN}、收场写 {@code END}
 * （`docs/ARCHITECTURE.md` §4 第七条）。
 *
 * <p>落点是 {@code flow/<id>/runs/<runId>.jsonl}。判据只有一条，而且它必须简单到
 * 一眼能看懂：<b>有 BEGIN 而没有 END 的运行，就是被杀过的</b>。不查进程、不看时间戳、
 * 不问操作系统——「被谁杀的、怎么杀的」在事后是查不出来的，能查出来的只有
 * 「它开工了、但没来得及收场」。
 *
 * <p>启动时 {@link #sweep()} 扫一遍：把那些运行按「中断」补记 END，并如实报给用户
 * （哪一次、什么时候开的、那一次的改动还在不在）。补 END 之后同一份账不会再报第二次。
 *
 * <p><b>为什么补 END 而不是自动回滚</b>：自动把工作区退回去等于替用户决定
 * 「那一次的成果不要了」；而它可能只差最后一步。补记 + 告诉他「快照还在，要退就退」
 * 才是诚实的收场。
 */
public final class Journal {

    private static final String BEGIN = "BEGIN";
    private static final String END = "END";
    private static final String STEP = "STEP";
    private static final String INTERRUPTED = "INTERRUPTED";

    private final Path root;

    public Journal(Project project) {
        if (project == null) {
            throw new IllegalArgumentException("崩溃恢复那本账要有一个项目");
        }
        this.root = project.layout().runs(new Store(project).currentId());
    }

    /** 这一段运行的账落在哪（留档路径那一栏用它）。 */
    public Path root() {
        return root;
    }

    /** 这一段运行的那份账。 */
    public Path file(String runId) {
        return root.resolve(safe(runId) + ".jsonl");
    }

    /** 开工：写 BEGIN（这一段活的第一件事）。 */
    public void begin(String runId, String what) {
        Path file = file(runId);
        if (open(file)) {
            // 同一个 id 上一段没收场（重试、或者进程刚好死在这里）：先把它封口再开新的，
            // 否则同一份账里会有两条 BEGIN、一条 END，下次 sweep 认不出来
            append(file, line(END, runId, "", INTERRUPTED, "上一次用这个 id 开工的那一段没有收场"));
        }
        append(file, line(BEGIN, runId, what, "", ""));
    }

    /** 收场：写 END（有没有结果看事件流，那是另一件事的真相）。 */
    public void end(String runId) {
        end(runId, "", "");
    }

    /** 收场并带上结果与一句摘要（给人和下一次恢复读的）。 */
    public void end(String runId, String status, String note) {
        append(file(runId), line(END, runId, "", status, note));
    }

    /** 逐轮那几行（一次运行里干了什么；判据不看它，人和排查看它）。 */
    public void step(String runId, String note) {
        append(file(runId), line(STEP, runId, "", "", note));
    }

    /**
     * 启动时扫一遍：没有 END 的运行按「中断」收场。
     *
     * @return 一句一件事的人话（没有被杀过的运行就是空表）
     */
    public List<String> sweep() {
        List<String> notes = new ArrayList<>();
        for (Path file : files()) {
            Read read = read(file);
            String id = file.getFileName().toString().replaceFirst("\\.jsonl$", "");
            if (read.broken() > 0) {
                notes.add("运行 " + id + " 的留档有 " + read.broken() + " 行读不出来（其余照读）。");
            }
            Map<String, Object> started = null;
            for (Map<String, Object> one : read.lines()) {
                String kind = text(one.get("kind"));
                if (BEGIN.equals(kind)) {
                    started = one;
                } else if (END.equals(kind)) {
                    started = null;
                }
            }
            if (started == null) {
                continue;
            }
            String what = text(started.get("what"));
            String at = text(started.get("at"));
            append(file, line(END, id, "", INTERRUPTED,
                    "进程在这次运行中途没了（没有写 END），已在启动时按中断收场"));
            notes.add("运行 " + id + (what.isEmpty() ? "" : "（" + what + "）") + " 是被杀过的："
                    + (at.isEmpty() ? "开工后" : at + " 开工后")
                    + "没有收场，已补记 END 按「中断」处理——那一次的改动还在磁盘上，"
                    + "要退回原文就用快照回滚（POST /api/node/dev/rollback）。");
        }
        return List.copyOf(notes);
    }

    // ---------- 里面 ----------

    private List<Path> files() {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> children = Files.list(root)) {
            return children.filter(Files::isRegularFile)
                    .filter(one -> one.getFileName().toString().endsWith(".jsonl"))
                    .sorted()
                    .toList();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("运行留档目录读不出来：" + root + "（" + e.getMessage() + "）", e);
        }
    }

    /** 最近一条 BEGIN 之后没有 END。 */
    private boolean open(Path file) {
        Map<String, Object> started = null;
        for (Map<String, Object> one : read(file).lines()) {
            String kind = text(one.get("kind"));
            if (BEGIN.equals(kind)) {
                started = one;
            } else if (END.equals(kind)) {
                started = null;
            }
        }
        return started != null;
    }

    /**
     * 读一份账。
     *
     * <p>读不出来的行<b>不吞</b>：数出来交给 {@link #sweep()} 报出去
     * （「第几行读不出来」比「这次运行看起来没跑过」有用得多），后面的行照读。
     */
    private record Read(List<Map<String, Object>> lines, int broken) {
    }

    private Read read(Path file) {
        if (!Files.isRegularFile(file)) {
            return new Read(List.of(), 0);
        }
        List<Map<String, Object>> lines = new ArrayList<>();
        int broken = 0;
        for (String row : Atomic.read(file, "").split("\r?\n")) {
            String text = row.strip();
            if (text.isEmpty()) {
                continue;
            }
            try {
                lines.add(Json.map(text));
            } catch (RuntimeException e) {
                broken++;
            }
        }
        return new Read(List.copyOf(lines), broken);
    }

    private static void append(Path file, Map<String, Object> line) {
        Atomic.appendLine(file, Json.line(line));
    }

    private static Map<String, Object> line(String kind, String runId, String what,
                                            String status, String note) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("at", Event.now());
        out.put("kind", kind);
        out.put("runId", runId);
        if (what != null && !what.isBlank()) {
            out.put("what", what.strip());
        }
        if (status != null && !status.isBlank()) {
            out.put("status", status);
        }
        if (note != null && !note.isBlank()) {
            out.put("note", note.strip());
        }
        return out;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).strip();
    }

    /** runId 进文件名：只留认得出来的字符。 */
    private static String safe(String runId) {
        String clean = runId == null ? "" : runId.strip().replaceAll("[^A-Za-z0-9._-]", "-");
        if (clean.isBlank()) {
            throw new IllegalArgumentException("运行 id 是空的，没法记这一笔");
        }
        return clean;
    }

    @Override
    public String toString() {
        return "运行留档 @ " + root;
    }
}
