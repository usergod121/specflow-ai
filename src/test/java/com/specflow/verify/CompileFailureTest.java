package com.specflow.verify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「这次编译失败该不该继续让模型修」的判断。
 *
 * <p>两条边界都要钉住：
 * <ul>
 *   <li>缺依赖必须认出来——认不出来就是烧完六轮换一个假绿灯（编译过了、需求没实现）；</li>
 *   <li>模型自己写错的东西<b>不能</b>认成环境问题——认错就白停一轮。</li>
 * </ul>
 */
@DisplayName("编译失败的归因")
class CompileFailureTest {

    @Test
    @DisplayName("javac 说程序包不存在：归环境")
    void detectsMissingPackage() {
        String output = """
                [ERROR] /E:/demo/src/main/java/demo/A.java:[5,20] 程序包 com.google.gson 不存在
                [ERROR] 1 个错误
                """;

        assertThat(CompileFailure.classify(output)).isEqualTo(VerificationResult.Kind.ENVIRONMENT);
        assertThat(CompileFailure.evidence(output)).contains("程序包 com.google.gson 不存在");
    }

    @Test
    @DisplayName("英文的 package ... does not exist 同样归环境")
    void detectsMissingPackageInEnglish() {
        assertThat(CompileFailure.classify("error: package com.google.gson does not exist"))
                .isEqualTo(VerificationResult.Kind.ENVIRONMENT);
    }

    @Test
    @DisplayName("Maven 解析不了依赖：归环境")
    void detectsUnresolvableDependency() {
        String output = """
                [ERROR] Failed to execute goal on project demo: Could not resolve dependencies for project demo:demo:jar:1.0
                [ERROR] Could not find artifact com.alibaba:fastjson:jar:1.2.83
                """;

        assertThat(CompileFailure.classify(output)).isEqualTo(VerificationResult.Kind.ENVIRONMENT);
    }

    @Test
    @DisplayName("JDK 级别不符、工具找不到：归环境")
    void detectsToolchainProblems() {
        assertThat(CompileFailure.classify("错误: 无效的目标发行版: 21"))
                .isEqualTo(VerificationResult.Kind.ENVIRONMENT);
        assertThat(CompileFailure.classify("Cannot run program \"javac\": CreateProcess error=2"))
                .isEqualTo(VerificationResult.Kind.ENVIRONMENT);
    }

    @Test
    @DisplayName("模型自己写错的：仍然归代码，继续让它改")
    void keepsCodeErrorsForTheModel() {
        assertThat(CompileFailure.classify("""
                [ERROR] /E:/demo/src/main/java/demo/A.java:[9,16] 找不到符号
                [ERROR]   符号:   方法 hello()
                """)).isEqualTo(VerificationResult.Kind.CODE);
        assertThat(CompileFailure.classify("error: ';' expected"))
                .isEqualTo(VerificationResult.Kind.CODE);
    }

    @Test
    @DisplayName("没有输出时归代码——宁可多试一轮，也不凭空甩给用户")
    void defaultsToCodeWhenOutputIsEmpty() {
        assertThat(CompileFailure.classify("")).isEqualTo(VerificationResult.Kind.CODE);
        assertThat(CompileFailure.classify(null)).isEqualTo(VerificationResult.Kind.CODE);
        assertThat(CompileFailure.evidence(null)).isNull();
    }

    @Test
    @DisplayName("给用户的话里带上编译命令的原话，否则他还要去翻日志")
    void explainQuotesTheOriginalLine() {
        String text = CompileFailure.explain("""
                [INFO] Scanning for projects...
                [ERROR] /E:/demo/A.java:[1,1] 程序包 com.demo.util 不存在
                """);

        assertThat(text).contains("这不是改代码能解决的").contains("程序包 com.demo.util 不存在");
    }

    @Test
    @DisplayName("日志保下来了就把路径一起写进去——只说「环境问题」等于让人自己去翻")
    void explainPointsAtTheKeptLog() {
        String text = CompileFailure.explain("""
                [ERROR] 程序包 com.demo.util 不存在
                （完整日志已保留：.specflow/logs/compile-20260923-141500.log）
                """);

        assertThat(text).contains(".specflow/logs/compile-20260923-141500.log");
    }

    @Test
    @DisplayName("没有日志可指时不多写一句空话")
    void explainOmitsTheLogLineWhenAbsent() {
        assertThat(CompileFailure.explain("error: cannot find symbol"))
                .doesNotContain("完整日志已保留");
    }
}
