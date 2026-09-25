package com.specflow.web;

import com.specflow.exception.SpecflowException;
import com.specflow.project.ContextLibrary;
import com.specflow.task.TaskStore;
import com.specflow.template.PromptTemplate;
import com.specflow.template.TemplateStore;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模板与任务草稿的增删改查。
 *
 * <p>单独成类而不是塞进 {@link WebServer}：路由该只负责「哪个路径交给谁」，
 * 一旦把业务塞进去，路由表就会变成一坨谁也读不完的 switch。
 *
 * <p>两条约定贯穿全部接口：
 * <ul>
 *   <li><b>写之前先校验</b>——由 {@link TemplateStore} 与 {@link TaskStore} 保证，
 *       不允许出现「存得进去、跑不起来」的东西</li>
 *   <li><b>名字即文件名</b>——路径穿越、以及「存成哪个文件」和「内容里的 name」是同一个值，
 *       都由 store 保证。接口层只在源码视图那一处额外拦一下：那个请求确实同时带着两个名字</li>
 * </ul>
 */
final class WorkspaceApi {

    private final TemplateStore templates;
    private final TaskStore tasks;
    private final ContextLibrary context;

    /**
     * 「这个项目还是当前那个吗」——写之前问一句，见 {@link OpenProject#requireOpen()}。
     *
     * <p>由项目自己传进来而不是各写一遍：写接口一共四个，谁都可能撞上
     * 「请求体慢慢发着、用户已经换了项目」那一瞬间。
     */
    private final Runnable requireOpen;

    WorkspaceApi(TemplateStore templates, TaskStore tasks, ContextLibrary context,
                 Runnable requireOpen) {
        this.templates = templates;
        this.tasks = tasks;
        this.context = context;
        this.requireOpen = requireOpen;
    }

    // ---------- 模板 ----------

    void templates(HttpExchange exchange) throws IOException {
        switch (exchange.getRequestMethod().toUpperCase()) {
            case "GET" -> {
                TemplateStore.Loaded loaded = templates.loadAll();
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("templates", loaded.templates());
                body.put("broken", loaded.broken());
                Http.sendJson(exchange, 200, body);
            }
            case "POST" -> saveTemplate(exchange);
            case "DELETE" -> deleteTemplate(exchange);
            default -> Http.sendJson(exchange, 405, Map.of("error", "该接口只接受 GET / POST / DELETE"));
        }
    }

    /** 表单视图提交：整个模板以 JSON 形式过来，名字取自它自己的 name 字段。 */
    private void saveTemplate(HttpExchange exchange) throws IOException {
        PromptTemplate template = Http.readJson(exchange, PromptTemplate.class);
        if (template == null) {
            return;
        }
        requireOpen.run();
        templates.save(template);
        Http.sendJson(exchange, 200, Map.of("name", template.name()));
    }

    private void deleteTemplate(HttpExchange exchange) throws IOException {
        if (!Http.requireDelete(exchange)) {
            return;
        }
        requireOpen.run();
        String name = Http.query(exchange, "name", "");
        templates.delete(name);
        Http.sendJson(exchange, 200, Map.of("deleted", name));
    }

    /**
     * 源码视图：读取 YAML 原文，或把改好的 YAML 校验后存回去。
     */
    void templateSource(HttpExchange exchange) throws IOException {
        switch (exchange.getRequestMethod().toUpperCase()) {
            case "GET" -> {
                String name = Http.query(exchange, "name", "");
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("name", name);
                body.put("source", templates.toYaml(templates.load(name)));
                Http.sendJson(exchange, 200, body);
            }
            case "POST" -> {
                Payloads.TemplateSource request =
                        Http.readJson(exchange, Payloads.TemplateSource.class);
                if (request == null) {
                    return;
                }
                // 先解析校验，通过了再落盘——源码视图里打错一个字段名，
                // 应当在点「应用」的那一刻就说清楚
                PromptTemplate parsed = templates.parse(request.source(), request.name());
                requireSameName(request.name(), parsed);
                requireOpen.run();
                templates.save(parsed);
                Http.sendJson(exchange, 200, Map.of("name", parsed.name()));
            }
            default -> Http.sendJson(exchange, 405, Map.of("error", "该接口只接受 GET / POST"));
        }
    }

    /**
     * 只解析、不落盘：把源码视图里的 YAML 翻成界面表单要的对象。
     *
     * <p>存在的理由是那条静默丢改动的路：切到「源码」改了内容、再点回「表单」，
     * 表单是拿旧的 {@code tplDraft} 重新渲染的，源码里写的那些就这么没了。
     * 要让两个视图真正是同一份内容，就得有人把 YAML 翻回来——
     * 而 YAML 只有服务端那个解析器认得全，前端自己写一个必然是两个真相。
     *
     * <p>这里刻意不落盘：用户只是切了个视图，没点保存。
     */
    void templateParse(HttpExchange exchange) throws IOException {
        Payloads.TemplateSource request = Http.readJson(exchange, Payloads.TemplateSource.class);
        if (request == null) {
            return;
        }
        Http.sendJson(exchange, 200, templates.parse(request.source(), request.name()));
    }

    /**
     * 源码视图里的 {@code name:} 是能直接改的，而模板名同时是文件名——
     * 放任两者分叉，磁盘上就会多出一个名字对不上的模板：界面按内容里的名字列出来、
     * 删除却按文件名去找，于是它既删不掉也读不出来。所以这里当场拦住。
     *
     * <p>不选「按源码里的名字另存一份」是因为那是<b>静默</b>多出一个模板，
     * 而用户只是想改个备注却打错了一行。
     *
     * <p>提示里刻意不说「请去表单视图改名」——照做的话得先切走，
     * 而切走会把源码里还没保存的改动一起丢掉。先让人待在原地把 name 改回来。
     */
    private static void requireSameName(String editing, PromptTemplate parsed) {
        if (editing.equals(parsed.name())) {
            return;
        }
        throw new SpecflowException("源码里的 name（" + parsed.name() + "）和正在编辑的模板（"
                + editing + "）对不上。模板名同时是文件名，改不动它——"
                + "把 name 改回 " + editing + " 再保存；真要改名，改完这个"
                + "、保存好，再用表单视图的「名称」另存一份，旧的自己删。");
    }

    // ---------- 任务草稿 ----------

    void tasks(HttpExchange exchange) throws IOException {
        switch (exchange.getRequestMethod().toUpperCase()) {
            case "GET" -> Http.sendJson(exchange, 200, Map.of("tasks", tasks.names()));
            case "POST" -> {
                Payloads.TaskSave request = Http.readJson(exchange, Payloads.TaskSave.class);
                if (request == null) {
                    return;
                }
                requireOpen.run();
                tasks.save(request.name(), request.spec().toSpec());
                Http.sendJson(exchange, 200, Map.of("name", request.name()));
            }
            case "DELETE" -> {
                requireOpen.run();
                String name = Http.query(exchange, "name", "");
                tasks.delete(name);
                Http.sendJson(exchange, 200, Map.of("deleted", name));
            }
            default -> Http.sendJson(exchange, 405, Map.of("error", "该接口只接受 GET / POST / DELETE"));
        }
    }

    /** 载入一份草稿：返回表单形状，界面可以直接填回去。 */
    void task(HttpExchange exchange) throws IOException {
        String name = Http.query(exchange, "name", "");
        Http.sendJson(exchange, 200, RunRequest.from(tasks.load(name)));
    }

    // ---------- 上下文 ----------

    /**
     * 上下文的导出与读回。
     *
     * <p>{@code GET} 一次给出<b>每份的全部条目</b>，而不是只给名字：
     * 界面要拿它做导入预览——「这一套里有哪几条、引用的文件还在不在」。
     * 名字列表在预览里什么都说明不了，而条目本来也就在同一个 yaml 文件里，
     * 再为「按需取一份」开一个接口只是多一次往返。
     */
    void contextLibrary(HttpExchange exchange) throws IOException {
        switch (exchange.getRequestMethod().toUpperCase()) {
            case "GET" -> {
                List<ContextLibrary.Bundle> bundles = new ArrayList<>();
                for (String name : context.names()) {
                    bundles.add(context.load(name));
                }
                Http.sendJson(exchange, 200, Map.of("bundles", bundles));
            }
            case "POST" -> {
                Payloads.ContextSave request = Http.readJson(exchange, Payloads.ContextSave.class);
                if (request == null) {
                    return;
                }
                requireOpen.run();
                Path written = context.save(request.name(), request.items());
                Http.sendJson(exchange, 200, Map.of(
                        "name", request.name(),
                        // 相对项目根的 POSIX 路径，界面直接显示成「已导出到 …」。
                        // 文件名来自用户输入，但 save 已经挡掉了分隔符与 ..，
                        // 而写出的位置永远是 library 自己的目录
                        "path", ContextLibrary.DEFAULT_DIR + "/" + written.getFileName()));
            }
            default -> Http.sendJson(exchange, 405, Map.of("error", "该接口只接受 GET / POST"));
        }
    }

    /** 供配置接口复用的模板清单，连同读不回来的那几份。 */
    TemplateStore.Loaded loadedTemplates() {
        return templates.loadAll();
    }
}
