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
}
