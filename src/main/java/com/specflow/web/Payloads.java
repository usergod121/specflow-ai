package com.specflow.web;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.specflow.spec.ContextItem;
import com.specflow.template.PromptTemplate;

import java.util.List;

/**
 * CRUD 接口的请求体。
 *
 * <p>集中放在一个文件里，是因为它们都是「名字 + 一样东西」的形状，
 * 而且只服务于接口层——拆成四个文件只会让人在包列表里多翻四次。
 * 业务类型本身（{@link PromptTemplate}、{@link Spec}）各有自己的家，不在这里。
 */
public final class Payloads {

    private Payloads() {
    }

    /**
     * 模板源码视图提交的内容。
     *
     * @param name   模板名，同时也是文件名
     * @param source 界面里那段 YAML 原文
     */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record TemplateSource(
            String name,
            String source
    ) {
        @JsonCreator
        public static TemplateSource of(@JsonProperty("name") String name,
                                        @JsonProperty("source") String source) {
            return new TemplateSource(name, source);
        }
    }

    /**
     * 保存任务草稿。
     *
     * <p>{@code spec} 用的是<b>界面表单的形状</b>（{@link RunRequest}）而不是
     * 磁盘上的 {@code Spec}：界面提交的就是这份表单，让它在服务端转一次，
     * 比要求前端拼出 Spec 的嵌套结构（verify / trace 那些）可靠得多。
     *
     * @param name 草稿名，同时也是文件名
     * @param spec 这一次需求的完整组装
     */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record TaskSave(
            String name,
            RunRequest spec
    ) {
        @JsonCreator
        public static TaskSave of(@JsonProperty("name") String name,
                                  @JsonProperty("spec") RunRequest spec) {
            return new TaskSave(name, spec);
        }
    }

    /**
     * 导出一套上下文。
     *
     * <p>{@code items} 缺省成空列表而不是 {@code null}：空列表会被
     * {@link com.specflow.project.ContextLibrary#save} 拒绝并给出一句人话，
     * 而 {@code null} 只会在更后面的地方变成一次 NPE。
     *
     * @param name  上下文名，同时也是导出文件名
     * @param items 这套上下文里的条目，形态与 spec 里的 {@code context} 完全一致
     */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record ContextSave(
            String name,
            List<ContextItem> items
    ) {
        @JsonCreator
        public static ContextSave of(@JsonProperty("name") String name,
                                     @JsonProperty("items") List<ContextItem> items) {
            return new ContextSave(name, items == null ? List.of() : items);
        }
    }

    /**
     * 打开一个项目。
     *
     * @param path 项目根目录的绝对路径。相对路径也接受，会按当前工作目录解析——
     *             界面给的一律是绝对路径，这里宽松一点只是为了命令行调试时省事
     */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record OpenProject(
            String path
    ) {
        @JsonCreator
        public static OpenProject of(@JsonProperty("path") String path) {
            return new OpenProject(path);
        }
    }

    /**
     * 「这几条怎么判的」——把界面上勾中的那些用例写进留档（十五.6）。
     *
     * <p>{@code cases} 是<b>完整的一份集合</b>，不是「这一次新加的几条」：界面上的记号本来
     * 就是一个集合，发全量就不存在「两次点击乱序到达」这种要命的状态——而它的后果是
     * 人的一个判断被静默吞掉（见 {@code RunStore.judge}）。
     *
     * @param id    哪一次运行。空着表示「界面上正看着的那一次」，服务端按最新那条记录落；
     *              刷新过页面之后界面手里只有屏幕上那份失败清单，拿不到记录 id
     * @param cases 这样判定的用例编号
     * @param owner 谁错了：{@code CODE}（开发 AI 错了，回喂）/ {@code TEST}（测试代码错了，
     *              重新生成）/ {@code KNOWN}（不重要、误报）。见 {@code RunRecord.Verdict}
     */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record Judgement(
            String id,
            List<Integer> cases,
            String owner
    ) {
        @JsonCreator
        public static Judgement of(@JsonProperty("id") String id,
                                   @JsonProperty("cases") List<Integer> cases,
                                   @JsonProperty("owner") String owner) {
            return new Judgement(id, cases == null ? List.of() : cases, owner);
        }
    }

    /**
     * 「停用 / 恢复这几条用例」——用户的原话是「删掉它」（见 {@code RunStore.disable}）。
     *
     * <p>和 {@link Judgement} 同一套形状：{@code id} 空着表示「界面上正看着的那一次」
     * （刷新过页面之后界面手里没有记录 id，而屏幕上那份清单就是最新一次跑出来的）。
     *
     * <p>{@code disabled} 缺省当<b>停用</b>：这个接口的名字就是「停用」，而少写一栏
     * 不该变成「恢复」——那会让一次误发的请求把用户刚停掉的用例又放回分母里。
     *
     * @param id       哪一次运行；空串 = 按最新那条落
     * @param cases    这次动的用例编号
     * @param disabled {@code true} = 停用，{@code false} = 恢复；不写按停用
     */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record CaseSwitching(
            String id,
            List<Integer> cases,
            Boolean disabled
    ) {
        @JsonCreator
        public static CaseSwitching of(@JsonProperty("id") String id,
                                       @JsonProperty("cases") List<Integer> cases,
                                       @JsonProperty("disabled") Boolean disabled) {
            return new CaseSwitching(id, cases == null ? List.of() : cases, disabled);
        }

        /** 这一步是停用还是恢复。 */
        public boolean off() {
            return disabled == null || disabled;
        }
    }
}
