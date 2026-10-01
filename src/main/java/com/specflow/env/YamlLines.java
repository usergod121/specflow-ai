package com.specflow.env;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * 「这一项在文件里的第几行」。
 *
 * <p>为什么非要它：{@code env.yaml} 是<b>用户手写的</b>，而写错的方式永远是「少一个冒号、
 * 缩进错了一格、把 healthcheck 写在 dependencies 底下」。只报一句「字段不认识」，
 * 用户还得自己在几十行里找；报「第 12 行」，他一眼就看完了。
 * 这是这一层唯一的存在理由，所以它只做一件事：给路径（{@code dependencies.db.image}）
 * 配一个行号。
 *
 * <p>怎么做到的：解析出来的那棵树（{@link com.fasterxml.jackson.databind.JsonNode}）
 * 和词法记号流是同一份文档的两种看法，顺序一致。这里沿着记号流走一遍、只记行号，
 * 结构校验仍然交给树——两边各走各的路，就不会出现「为了拿行号把解析也写坏了」。
 *
 * <p>数组元素按 {@code init[0]} 记：它是路径语法里唯一不会和键名撞车的写法
 * （键名里不可能有点号以外的分隔符，而点号本身在数组下标里不出现）。
 */
final class YamlLines {

    private final Map<String, Integer> lines;

    private YamlLines(Map<String, Integer> lines) {
        this.lines = lines;
    }

    /**
     * 走一遍文档，把「路径 → 行号」记下来。
     *
     * <p>解析不了（YAML 本身写坏了）时返回一个空表：那种情况下错误消息由调用方那条
     * 「解析失败」来报，行号能给就给，给不出来也不能因此把整个加载搞崩。
     */
    static YamlLines of(String source) {
        return new YamlLines(walk(source));
    }

    /** 这一项的起始行号（1 开始）；没记到返回 {@code -1}。 */
    int lineOf(String path) {
        Integer line = lines.get(path);
        return line == null ? -1 : line;
    }

    private static Map<String, Integer> walk(String source) {
        Map<String, Integer> found = new HashMap<>();
        try (JsonParser parser = new YAMLFactory().createParser(source)) {
            Deque<Frame> stack = new ArrayDeque<>();
            stack.push(new Frame(""));
            JsonToken token;
            while ((token = parser.nextToken()) != null) {
                int line = parser.getTokenLocation().getLineNr();
                switch (token) {
                    case START_OBJECT, START_ARRAY -> {
                        String path = childPath(stack.peek());
                        record(found, path, line);
                        stack.push(new Frame(path, token == JsonToken.START_ARRAY));
                    }
                    case END_OBJECT, END_ARRAY -> {
                        // 根的那一层永远留着：不能让一个多余的 END 之后没有容器可问
                        if (stack.size() > 1) {
                            stack.pop();
                        }
                    }
                    case FIELD_NAME -> {
                        Frame top = stack.peek();
                        top.field = parser.currentName();
                        // 字段名用覆盖而不是「先记的赢」：同一个键写两遍时，
                        // 解析出来的是**最后**一份，行号也必须指到最后那一行——
                        // 否则报出来的那行上写着一句完全合法的话，用户会对着它发懵
                        found.put(childPath(top), line);
                    }
                    default -> record(found, childPath(stack.peek()), line);
                }
            }
        } catch (IOException | RuntimeException e) {
            // 写坏了的 YAML：这里只丢行号，不改变「谁报错」这件事
            return Map.of();
        }
        return found;
    }

    /**
     * 记一个值那一侧的行号——<b>先记下来的赢</b>。
     *
     * <p>为什么值这一侧不能覆盖字段名那一行：一个字段会在记号流里出现两次，
     * 字段名自己一次（{@code FIELD_NAME}），它的值又一次（{@code START_OBJECT} 或标量）。
     * 覆盖的话，{@code dependencies:} 那一行会被它的内容（下一行）顶掉——
     * 报出来就是「第 3 行有个写错的字段」，而用户那一行上只有一个冒号。
     * 要的永远是<b>那个名字在第几行</b>，所以值这一侧让位。
     */
    private static void record(Map<String, Integer> found, String path, int line) {
        found.putIfAbsent(path, line);
    }

    /**
     * 当前这个记号该挂在哪个路径上。
     *
     * <p>对象里看「正处理哪个字段」，数组里看「第几个元素」——同一个方法管两种容器，
     * 是因为记号流里这两件事本来就交替出现，分成两个方法反而要各自判断对方的状态。
     *
     * <p>数组标记必须单独记：数组里的元素没有字段名，而「没有字段名」在对象上是
     * <b>合法的根</b>（整份文档本身）。不分开这两个，根那一层会被算成 {@code [0]}，
     * 于是所有根级字段的路径都会多一截，行号就查不到了。
     */
    private static String childPath(Frame frame) {
        if (frame.array) {
            return frame.path + "[" + frame.index++ + "]";
        }
        if (frame.field == null) {
            return frame.path;
        }
        return frame.path.isEmpty() ? frame.field : frame.path + "." + frame.field;
    }

    /** 一层容器：它自己的路径、是不是数组、正在处理的字段名、数组已经走到第几个元素。 */
    private static final class Frame {

        private final String path;
        /** 这一层是数组吗——决定元素路径用下标还是用字段名，见 {@link #childPath}。 */
        private final boolean array;
        private String field;
        private int index;

        Frame(String path) {
            this(path, false);
        }

        Frame(String path, boolean array) {
            this.path = path;
            this.array = array;
        }
    }
}
