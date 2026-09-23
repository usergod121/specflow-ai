package com.specflow.task;

import com.specflow.TestSpecs;
import com.specflow.exception.SpecflowException;
import com.specflow.spec.ContextItem;
import com.specflow.spec.Spec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("任务草稿")
class TaskStoreTest {

    @TempDir
    Path root;

    private TaskStore store() {
        return new TaskStore(root);
    }

    private Spec sample() {
        return TestSpecs.builder()
                .template("接口1")
                .variables(Map.of("api", "查询订单列表"))
                .targets(List.of("src/main/java/demo/OrderController.java"))
                .constraints(List.of("不引入新依赖"))
                .context(List.of(ContextItem.of("订单表结构", null, "CREATE TABLE orders (id BIGINT)", "库里的定义")))
                .build();
    }

    @Test
    @DisplayName("存下来再载入，一次需求的完整组装都还在")
    void savesAndLoads() {
        store().save("订单查询", sample());

        Spec loaded = store().load("订单查询");
        assertThat(loaded.template()).isEqualTo("接口1");
        assertThat(loaded.variables()).containsEntry("api", "查询订单列表");
        assertThat(loaded.targets()).containsExactly("src/main/java/demo/OrderController.java");
        assertThat(loaded.constraints()).containsExactly("不引入新依赖");
        assertThat(loaded.context()).singleElement().satisfies(item -> {
            assertThat(item.name()).isEqualTo("订单表结构");
            assertThat(item.text()).contains("CREATE TABLE orders");
            assertThat(item.note()).isEqualTo("库里的定义");
        });
    }

    @Test
    @DisplayName("载入走的校验和命令行完全一致：越界的 targets 存不进去")
    void refusesInvalidSpec() {
        Spec bad = TestSpecs.builder().targets(List.of("../outside.java")).build();

        assertThatThrownBy(() -> store().save("坏的", bad))
                .isInstanceOf(SpecflowException.class)
                .hasMessageContaining("越出项目根目录");

        assertThat(store().names()).isEmpty();
    }

    @Test
    @DisplayName("草稿名会变成文件名，路径穿越必须被挡住")
    void rejectsUnsafeName() {
        assertThatThrownBy(() -> store().save("../../evil", sample()))
                .isInstanceOf(SpecflowException.class)
                .hasMessageContaining("任务名非法");
        assertThatThrownBy(() -> store().load("a\\b"))
                .isInstanceOf(SpecflowException.class)
                .hasMessageContaining("任务名非法");
    }

    @Test
    @DisplayName("列出时按名字排序")
    void listsSorted() {
        store().save("乙", sample());
        store().save("甲", sample());
        store().save("a", sample());

        assertThat(store().names()).containsExactly("a", "乙", "甲");
    }

    @Test
    @DisplayName("目录不存在时列表为空，不是报错")
    void emptyWhenDirectoryMissing() {
        assertThat(store().names()).isEmpty();
    }

    @Test
    @DisplayName("删除之后载入会明确报错")
    void deletes() {
        store().save("订单查询", sample());
        store().delete("订单查询");

        assertThat(store().names()).isEmpty();
        assertThatThrownBy(() -> store().load("订单查询"))
                .isInstanceOf(SpecflowException.class)
                .hasMessageContaining("找不到任务草稿");
        assertThatThrownBy(() -> store().delete("订单查询"))
                .isInstanceOf(SpecflowException.class)
                .hasMessageContaining("找不到要删除的任务草稿");
    }

    @Test
    @DisplayName("覆盖保存时不残留旧内容")
    void overwriteReplacesContent() {
        store().save("t", TestSpecs.builder().variables(Map.of("a", "第一次")).build());
        store().save("t", TestSpecs.builder().variables(Map.of("a", "第二次")).build());

        assertThat(store().load("t").variables()).containsEntry("a", "第二次");
        assertThat(store().names()).containsExactly("t");
    }
}
