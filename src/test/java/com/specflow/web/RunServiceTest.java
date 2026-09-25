package com.specflow.web;

import com.specflow.project.ProjectConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「停止」这条链路上，只有服务这一环没有别的地方管：界面把请求发到
 * {@code /api/cancel}，路由调 {@link RunService#cancel()}，引擎每轮开头问
 * {@link RunService#cancelled()}。
 *
 * <p>引擎那一侧（问到真就回滚停下）由 {@code DevelopmentAgentTest} 覆盖，
 * 路由那一侧（没有任务时 409、方法不对 405）由 {@code WebServerTest} 覆盖。
 * 这里只钉住中间这一环——「请求过停止之后，引擎问到的就是真」，
 * 否则界面上的按钮点了等于没点，而两边各自的测试都还是绿的。
 */
@DisplayName("停止运行")
class RunServiceTest {

    @TempDir
    Path root;

    @Test
    @DisplayName("没点过停止时，引擎问到的是假")
    void notCancelledByDefault() {
        RunService service = service();

        assertThat(service.cancelled()).isFalse();

        service.shutdown();
    }

    @Test
    @DisplayName("点过停止之后，引擎问到的就是真")
    void cancelAsksTheAgentToStop() {
        RunService service = service();

        service.cancel();

        assertThat(service.cancelled()).isTrue();

        service.shutdown();
    }

    private RunService service() {
        return new RunService(root, ProjectConfig.DEFAULT, root.resolve(".specflow/templates"));
    }
}
