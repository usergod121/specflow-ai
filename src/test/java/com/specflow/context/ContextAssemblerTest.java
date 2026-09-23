package com.specflow.context;

import com.specflow.TestSpecs;
import com.specflow.exception.SpecflowException;
import com.specflow.spec.ContextItem;
import com.specflow.spec.Spec;
import com.specflow.spec.TraceSpec;
import com.specflow.template.TemplateRegistry;
import com.specflow.util.SafePathResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

@DisplayName("上下文组装")
class ContextAssemblerTest {

    @TempDir
    Path root;

    private ContextAssembler assembler;

    @BeforeEach
    void setUp() {
        assembler = new ContextAssembler(new SafePathResolver(root));
    }

    @Test
    @DisplayName("模板的标签被拼成一段话，并带上「设计细节以需求为准」的优先级")
    void rendersConventionsFromTags() throws IOException {
        TemplateRegistry templates = writeTemplate("""
                name: t
                tags: [java, spring-boot, mybatis]
                system: 你是后端工程师
                """);

        String system = assembler.systemMessage(TestSpecs.builder().template("t").build(), templates);

        assertThat(system).contains("## 模板约定").contains("java、spring-boot、mybatis");
        assertThat(system).contains("可以直接使用其中的注解");
        assertThat(system).contains("以本次需求说明为准");
    }

    @Test
    @DisplayName("产物形态（class / script）和技术栈走同一条路，引擎不区分")
    void rendersProductTypeTagsLikeAnyOther() throws IOException {
        TemplateRegistry templates = writeTemplate("""
                name: t
                tags: [script, python]
                system: 你是工程师
                """);

        String system = assembler.systemMessage(TestSpecs.builder().template("t").build(), templates);

        assertThat(system).contains("## 模板约定").contains("script、python");
    }

    @Test
    @DisplayName("没有标签的模板不会多出约定那一段")
    void omitsConventionsWhenNoTags() throws IOException {
        TemplateRegistry templates = writeTemplate("""
                name: t
                system: 你是工程师
                """);

        String system = assembler.systemMessage(TestSpecs.builder().template("t").build(), templates);

        assertThat(system).doesNotContain("## 模板约定");
    }

    @Test
    @DisplayName("系统提示词始终包含补丁协议，模板无法覆盖它")
    void systemMessageAlwaysCarriesProtocol() throws IOException {
        TemplateRegistry templates = writeTemplate("""
                name: t
                system: 你是一个只会说「你好」的助手
                """);

        String system = assembler.systemMessage(TestSpecs.builder().template("t").build(), templates);

        assertThat(system).contains(PatchProtocol.SEARCH_MARKER);
        assertThat(system).contains("你是一个只会说「你好」的助手");
    }

    @Test
    @DisplayName("验收标准拼进用户消息，排在需求之后、约束之前")
    void rendersAcceptance() {
        Spec spec = TestSpecs.builder()
                .prompt("给 OrderService 加一个按订单号查询的方法")
                .acceptance(List.of("传入不存在的订单号时返回空，不抛异常", "不改动已有方法"))
                .constraints(List.of("不引入新依赖"))
                .build();

        String user = assembler.userMessage(spec, TemplateRegistry.empty());

        assertThat(user).contains("## 验收标准")
                .contains("- 传入不存在的订单号时返回空，不抛异常")
                .contains("- 不改动已有方法");
        // 顺序：需求 → 验收标准 → 约束。验收标准更靠近需求本身
        assertThat(user.indexOf("## 需求")).isLessThan(user.indexOf("## 验收标准"));
        assertThat(user.indexOf("## 验收标准")).isLessThan(user.indexOf("## 约束"));
    }

    @Test
    @DisplayName("没写验收标准时不出那一段，也不报错")
    void omitsAcceptanceWhenEmpty() {
        Spec spec = TestSpecs.builder().prompt("随便改点东西").build();

        assertThat(assembler.userMessage(spec, TemplateRegistry.empty()))
                .doesNotContain("## 验收标准");
    }

    @Test
    @DisplayName("需求来自 spec 自己，模板不参与——模板只提供角色与标签")
    void requirementComesFromSpecNotTemplate() throws IOException {
        TemplateRegistry templates = writeTemplate("""
                name: t
                tags: [java, mybatis]
                system: 你是后端工程师
                """);
        Spec spec = TestSpecs.builder().template("t")
                .prompt("给缓存加一个过期时间").build();

        // 系统消息里是模板的角色与标签
        String system = assembler.systemMessage(spec, templates);
        assertThat(system).contains("你是后端工程师").contains("## 模板约定").contains("mybatis");
        // 用户消息里的需求是 spec 自己写的那句话，模板提供不了它
        assertThat(assembler.userMessage(spec, templates)).contains("给缓存加一个过期时间");
    }

    @Test
    @DisplayName("模板的角色提示词里写了占位符时进 broken 名单——界面上没有填变量的地方")
    void rejectsPlaceholderInTemplateSystem() {
        // 不抛异常、只是不参与组装：一份坏模板不该让整个列表和界面一起失败
        TemplateRegistry templates = assertDoesNotThrow(() -> writeTemplate("""
                name: t
                system: 你是 {{role}} 工程师
                """));

        assertThat(templates.names()).isEmpty();
        assertThat(templates.broken()).singleElement()
                .satisfies(item -> assertThat(item.reason())
                        .contains("占位符").contains("role"));
    }

    @Test
    @DisplayName("内联 prompt 同样支持占位符")
    void inlinePromptSupportsVariables() {
        Spec spec = TestSpecs.builder().prompt("给 {{clazz}} 加日志")
                .variables(Map.of("clazz", "FooService")).build();

        assertThat(assembler.userMessage(spec, TemplateRegistry.empty())).contains("给 FooService 加日志");
    }

    @Test
    @DisplayName("目标文件内容被完整放进用户消息，尚不存在的文件被显式标注")
    void includesTargetFileContents() throws IOException {
        Files.writeString(root.resolve("Exists.java"), "class Exists {}");
        Spec spec = TestSpecs.builder().targets(List.of("Exists.java", "New.java")).build();

        String user = assembler.userMessage(spec, TemplateRegistry.empty());

        assertThat(user).contains("class Exists {}");
        assertThat(user).contains("文件当前不存在");
    }

    @Test
    @DisplayName("目标文件总量超出预算时明确失败，而不是静默截断")
    void failsWhenContextTooLarge() throws IOException {
        Files.writeString(root.resolve("Big.java"), "x".repeat(200_000));
        Spec spec = TestSpecs.builder().targets(List.of("Big.java")).build();

        assertThatThrownBy(() -> assembler.userMessage(spec, TemplateRegistry.empty()))
                .isInstanceOf(SpecflowException.class)
                .hasMessageContaining("超出预算")
                .hasMessageContaining("Big.java");
    }

    @Test
    @DisplayName("约束与上下文依赖各自成段；没有追溯信息时不出现该段")
    void rendersOptionalSections() {
        Spec spec = TestSpecs.builder()
                .constraints(List.of("不引入新依赖"))
                .context(List.of(ContextItem.of("订单表结构", null, "CREATE TABLE orders (id BIGINT)", "库里的真实定义")))
                .build();

        String user = assembler.userMessage(spec, TemplateRegistry.empty());

        assertThat(user).contains("## 约束").contains("不引入新依赖");
        assertThat(user).contains("## 上下文依赖").contains("订单表结构").contains("CREATE TABLE orders");
        assertThat(user).contains("库里的真实定义");
        assertThat(user).doesNotContain("## 追溯信息");
    }

    @Test
    @DisplayName("上下文引用在组装时才读盘——文件改了下次运行就跟着变，不会像快照那样过期")
    void readsReferencedFileAtAssemblyTime() throws IOException {
        Path reference = root.resolve("UserController.java");
        Files.writeString(reference, "class UserController { /* 第一版 */ }");
        Spec spec = TestSpecs.builder()
                .context(List.of(ContextItem.of(null, "UserController.java", null, "照它的风格写")))
                .build();

        assertThat(assembler.userMessage(spec, TemplateRegistry.empty()))
                .contains("第一版")
                .contains("照它的风格写");

        Files.writeString(reference, "class UserController { /* 第二版 */ }");
        assertThat(assembler.userMessage(spec, TemplateRegistry.empty())).contains("第二版");
    }

    @Test
    @DisplayName("引用不存在的文件时报错，并指出是哪一条上下文")
    void failsWhenReferencedFileMissing() {
        Spec spec = TestSpecs.builder()
                .context(List.of(ContextItem.of("用户控制器", "NoSuchFile.java", null, "")))
                .build();

        assertThatThrownBy(() -> assembler.userMessage(spec, TemplateRegistry.empty()))
                .isInstanceOf(SpecflowException.class)
                .hasMessageContaining("用户控制器")
                .hasMessageContaining("NoSuchFile.java");
    }

    @Test
    @DisplayName("给出追溯信息时单独成段，供模型写 @requirement 注释")
    void rendersTraceSection() {
        Spec spec = TestSpecs.builder()
                .trace(new TraceSpec("R-001")).build();

        String user = assembler.userMessage(spec, TemplateRegistry.empty());

        assertThat(user).contains("## 追溯信息").contains("R-001").contains("@requirement R-001");
    }

    @Test
    @DisplayName("上下文依赖同样吃预算——只管住目标文件等于没管")
    void failsWhenContextItemTooLarge() {
        Spec spec = TestSpecs.builder()
                .context(List.of(ContextItem.of("超大表结构", null, "x".repeat(200_000), "")))
                .build();

        assertThatThrownBy(() -> assembler.userMessage(spec, TemplateRegistry.empty()))
                .isInstanceOf(SpecflowException.class)
                .hasMessageContaining("超出预算")
                .hasMessageContaining("超大表结构");
    }

    @Test
    @DisplayName("目标文件与上下文依赖共用同一份额度，加起来超了也要报错")
    void budgetIsSharedBetweenTargetsAndContext() throws IOException {
        Files.writeString(root.resolve("Big.java"), "x".repeat(120_000));
        Spec spec = TestSpecs.builder()
                .targets(List.of("Big.java"))
                .context(List.of(ContextItem.of("另一份大文本", null, "y".repeat(120_000), "")))
                .build();

        assertThatThrownBy(() -> assembler.userMessage(spec, TemplateRegistry.empty()))
                .isInstanceOf(SpecflowException.class)
                .hasMessageContaining("超出预算");
    }

    private TemplateRegistry writeTemplate(String yaml) throws IOException {
        Path templates = root.resolve(TemplateRegistry.DEFAULT_DIR);
        Files.createDirectories(templates);
        Files.writeString(templates.resolve("t.yaml"), yaml);
        return TemplateRegistry.load(templates);
    }}
