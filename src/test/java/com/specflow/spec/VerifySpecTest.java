package com.specflow.spec;

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 自检配置里那两道闸。
 *
 * <p>它们管的是完全不同的两件事：{@code max-retry} 管「每一步试几次」，
 * {@code max-rounds} 管「一整单最多烧几次调用」。混成一个的话，
 * 一份 7 步的施工单最坏能烧掉 7 × (6 + 冲突重试) 次，而用户是按次付费的。
 */
@DisplayName("自检配置")
class VerifySpecTest {

    @Test
    @DisplayName("没配总轮次时按「3 × 步数」自动算")
    void derivesRoundBudgetFromStepCount() {
        assertThat(VerifySpec.DEFAULT.roundBudget(3)).isEqualTo(9);
        assertThat(VerifySpec.DEFAULT.roundBudget(7)).isEqualTo(21);
    }

    @Test
    @DisplayName("没有施工单（单步执行）时不自动设闸——那条路本来就由两份老预算兜住")
    void doesNotCapSingleStepRuns() {
        // 单步时自动值给 3 会**比今天更紧**：今天它能用满「补丁冲突 3 次 + maxRetry 6 次」
        assertThat(VerifySpec.DEFAULT.roundBudget(1)).isZero();
    }

    @Test
    @DisplayName("手写的总轮次优先，单步执行也照样生效")
    void explicitBudgetWins() {
        VerifySpec explicit = new VerifySpec(true, null, 6, 2);

        assertThat(explicit.roundBudget(1)).isEqualTo(2);
        assertThat(explicit.roundBudget(7)).as("手写了就以它为准，不按步数放大").isEqualTo(2);
    }

    @Test
    @DisplayName("老记录里没有 max-rounds：读出来是自动，不是 0 轮")
    void readsLegacySpecWithoutMaxRounds() {
        VerifySpec legacy = VerifySpec.of(true, "mvn compile", 6, null);

        assertThat(legacy.maxRounds()).isZero();
        assertThat(legacy.roundBudget(3)).isEqualTo(9);
    }

    @Test
    @DisplayName("负数按「不设」处理，不能让一个手滑的值把运行卡死在 0 轮")
    void clampsNegativeBudget() {
        assertThat(VerifySpec.of(true, null, 6, -5).maxRounds()).isZero();
        assertThat(VerifySpec.of(true, null, 6, -5).roundBudget(3)).isEqualTo(9);
    }

    @Test
    @DisplayName("YAML 里认 max-rounds（也认下划线写法）")
    void parsesFromYaml() {
        Spec spec = new SpecLoader().parse("""
                version: 1
                prompt: 加一个接口
                targets:
                  - src/main/java/demo/Foo.java
                verify:
                  compile: true
                  compile-command: mvn -q -DskipTests compile
                  max-retry: 3
                  max-rounds: 12
                """, "max-rounds");

        assertThat(spec.verify().maxRounds()).isEqualTo(12);
        assertThat(spec.verify().maxRetry()).isEqualTo(3);

        Spec snake = new SpecLoader().parse("""
                version: 1
                prompt: 加一个接口
                targets:
                  - src/main/java/demo/Foo.java
                verify:
                  max_rounds: 5
                """, "max_rounds");

        assertThat(snake.verify().maxRounds()).isEqualTo(5);
    }

    @Test
    @DisplayName("写出去的字段名是人写的 kebab-case，自己读得回来")
    void writesRoundTripFieldName() throws IOException {
        YAMLMapper yaml = new YAMLMapper();

        String written = yaml.writeValueAsString(new VerifySpec(true, "mvn compile", 3, 9));

        assertThat(written).contains("max-rounds").doesNotContain("maxRounds");
        assertThat(yaml.readValue(written, VerifySpec.class)).isEqualTo(new VerifySpec(true, "mvn compile", 3, 9));
    }
}
