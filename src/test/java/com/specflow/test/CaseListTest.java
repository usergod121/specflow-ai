package com.specflow.test;

import com.specflow.core.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 用例清单的闸与校验。钉的是三件事：
 * <ol>
 *   <li>{@code problems()} 的五条判据一条都不许少，而且话是给人看的；</li>
 *   <li>{@code save} 不合法就整份不落盘，正文逐条列出；</li>
 *   <li>{@code settle} 只动那一条，其余（含顺序）一个字节都不动。</li>
 * </ol>
 */
class CaseListTest {

    @TempDir
    Path tmp;

    private CaseList list(String name) {
        return new CaseList(tmp.resolve(name));
    }

    private static CaseList.Case one(String id, CaseList.Status status, String evidence, String note) {
        return new CaseList.Case(id, "验 " + id, List.of("功能"), "该报错就报错", status, evidence, note);
    }

    /** 一条合格的终态：PASS 带证据 或 非 PASS 带原因。 */
    private static CaseList.Case pass(String id) {
        return one(id, CaseList.Status.PASS, "容器里跑出来：退出码 0，12 条断言全绿", "");
    }

    // ---------- 文件不在 = 空表（不是异常） ----------

    @Test
    void emptyWhenFileIsMissing() {
        CaseList list = list("cases.json");

        assertThat(list.cases()).isEmpty();
        assertThat(list.count()).isZero();
        assertThat(list.pending()).isZero();
        assertThat(list.missingTags()).isZero();
    }

    // ---------- 五条判据 ----------

    @Test
    void problemsAcceptsAFullySettledList() {
        CaseList list = list("ok.json");
        list.save(List.of(pass("C1"),
                one("C2", CaseList.Status.FAIL, "", "重复入库同一件货没有报错，返回了 true")));

        assertThat(list.problems()).isEmpty();
        assertThat(list.count()).isEqualTo(2);
        assertThat(list.pending()).isZero();
        assertThat(list.missingTags()).isZero();
    }

    @Test
    void problemsRejectsAnEmptyList() {
        CaseList list = list("empty.json");
        list.save(List.of());

        List<String> problems = list.problems();

        assertThat(problems).hasSize(1);
        assertThat(problems.get(0)).contains("空的").contains("什么都没验");
    }

    @Test
    void problemsRejectsPending() {
        CaseList list = list("pending.json");
        list.save(List.of(pass("C1"), one("C2", CaseList.Status.PENDING, "", "")));

        List<String> problems = list.problems();

        assertThat(problems).hasSize(1);
        assertThat(problems.get(0)).contains("第 2 条用例").contains("C2").contains("还没有结论");
    }

    @Test
    void problemsRejectsPassWithoutEvidence() {
        CaseList list = list("no-evidence.json");
        list.save(List.of(one("C1", CaseList.Status.PASS, "", ""),
                one("C2", CaseList.Status.FAIL, "", "算出来是 3，期望 4")));

        List<String> problems = list.problems();

        assertThat(problems).hasSize(1);
        assertThat(problems.get(0)).contains("C1").contains("PASS").contains("没给证据");
    }

    @Test
    void problemsRejectsFailWithoutNote() {
        CaseList list = list("no-note.json");
        list.save(List.of(pass("C1"), one("C2", CaseList.Status.FAIL, "输出在这儿", "")));

        List<String> problems = list.problems();

        assertThat(problems).hasSize(1);
        assertThat(problems.get(0)).contains("C2").contains("FAIL").contains("没写原因");
    }

    @Test
    void problemsRejectsErrorWithoutNote() {
        CaseList list = list("error-no-note.json");
        list.save(List.of(pass("C1"), one("C2", CaseList.Status.ERROR, "", "")));

        List<String> problems = list.problems();

        assertThat(problems).hasSize(1);
        assertThat(problems.get(0)).contains("C2").contains("ERROR").contains("没写原因");
    }

    @Test
    void problemsRejectsAListWithoutAnyRealConclusion() {
        CaseList list = list("all-error.json");
        list.save(List.of(one("C1", CaseList.Status.ERROR, "", "镜像拉不下来"),
                one("C2", CaseList.Status.ERROR, "", "容器起不来")));

        List<String> problems = list.problems();

        // 两条都有原因（第 4 条不犯），但整份一条真结论都没有：全是「没测成」
        assertThat(problems).hasSize(1);
        assertThat(problems.get(0)).contains("没有一条真跑出结论");
    }

    // ---------- save 的四类非法输入 ----------

    @Test
    void saveRejectsDuplicateId() {
        CaseList list = list("dup.json");

        assertThatThrownBy(() -> list.save(List.of(pass("C1"), pass("C1"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("第 2 条用例")
                .hasMessageContaining("编号和第 1 条重了");
        assertThat(Files.exists(tmp.resolve("dup.json"))).isFalse();
    }

    @Test
    void saveRejectsBlankWhat() {
        CaseList list = list("blank-what.json");
        CaseList.Case bad = new CaseList.Case("C1", "   ", List.of("功能"), "该报错就报错",
                CaseList.Status.PENDING, "", "");

        assertThatThrownBy(() -> list.save(List.of(bad)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("C1")
                .hasMessageContaining("没写这条验什么");
        assertThat(Files.exists(tmp.resolve("blank-what.json"))).isFalse();
    }

    @Test
    void saveRejectsMissingOrUnknownTags() {
        CaseList list = list("tags.json");
        CaseList.Case noTags = new CaseList.Case("C1", "验登录", List.of(), "该报错就报错",
                CaseList.Status.PENDING, "", "");
        CaseList.Case badTag = new CaseList.Case("C2", "验登录", List.of("功能", "好玩"), "该报错就报错",
                CaseList.Status.PENDING, "", "");

        assertThatThrownBy(() -> list.save(List.of(noTags, badTag)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("一个标签都没打")
                .hasMessageContaining("标签「好玩」不在固定集合里")
                .hasMessageContaining("功能 / 边界 / 异常 / 性能 / 安全");
    }

    @Test
    void saveRejectsBlankExpect() {
        CaseList list = list("blank-expect.json");
        CaseList.Case bad = new CaseList.Case("C1", "验登录", List.of("异常"), " ",
                CaseList.Status.PENDING, "", "");

        assertThatThrownBy(() -> list.save(List.of(bad)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("没写期望");
    }

    @Test
    void saveListsEveryProblemAtOnce() {
        CaseList list = list("many.json");
        CaseList.Case bad = new CaseList.Case("C1", "", List.of(), "", CaseList.Status.PENDING, "", "");

        assertThatThrownBy(() -> list.save(List.of(bad, pass("C1"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("第 1 条用例")
                .hasMessageContaining("第 2 条用例")
                // 逐条列出：每条问题占一行
                .hasMessageContaining("\n - ");
    }

    @Test
    void saveRejectsANullEntry() {
        CaseList list = list("null-entry.json");

        assertThatThrownBy(() -> list.save(java.util.Arrays.asList(pass("C1"), null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("第 2 条是空的");
    }

    @Test
    void saveThenReadKeepsEveryField() {
        CaseList list = list("round-trip.json");
        list.save(List.of(new CaseList.Case("C1", "重复入库", List.of("边界", "功能"), "应当报错",
                CaseList.Status.PASS, "退出码 0", "备注")));

        CaseList.Case back = list.cases().get(0);

        assertThat(back.id()).isEqualTo("C1");
        assertThat(back.what()).isEqualTo("重复入库");
        assertThat(back.tags()).containsExactly("边界", "功能");
        assertThat(back.expect()).isEqualTo("应当报错");
        assertThat(back.status()).isEqualTo(CaseList.Status.PASS);
        assertThat(back.evidence()).isEqualTo("退出码 0");
        assertThat(back.note()).isEqualTo("备注");
    }

    // ---------- settle ----------

    @Test
    void settleTouchesOnlyThatOneCase() throws IOException {
        Path file = tmp.resolve("settle.json");
        CaseList list = new CaseList(file);
        list.save(List.of(one("C1", CaseList.Status.PENDING, "", ""),
                one("C2", CaseList.Status.PENDING, "", ""),
                pass("C3")));
        List<CaseList.Case> before = list.cases();
        String textBefore = Files.readString(file, StandardCharsets.UTF_8);

        list.settle("C2", CaseList.Status.PASS, "在容器里跑了 InventoryApp，输出 12 条全绿", "");

        List<CaseList.Case> after = list.cases();
        assertThat(after).extracting(CaseList.Case::id).containsExactly("C1", "C2", "C3");
        // 其余逐字节不变（顺序也不许变）
        assertThat(Json.write(after.get(0))).isEqualTo(Json.write(before.get(0)));
        assertThat(Json.write(after.get(2))).isEqualTo(Json.write(before.get(2)));
        assertThat(after.get(1).status()).isEqualTo(CaseList.Status.PASS);
        assertThat(after.get(1).evidence()).isEqualTo("在容器里跑了 InventoryApp，输出 12 条全绿");
        // 真的写回了同一个文件
        assertThat(Files.readString(file, StandardCharsets.UTF_8)).isNotEqualTo(textBefore);
    }

    @Test
    void settleRefusesAnUnknownId() {
        CaseList list = list("unknown.json");
        list.save(List.of(pass("C1")));

        assertThatThrownBy(() -> list.settle("C9", CaseList.Status.PASS, "证据", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("没有编号 C9")
                .hasMessageContaining("现在有：C1");
        assertThat(list.cases()).hasSize(1);
        assertThat(list.cases().get(0).status()).isEqualTo(CaseList.Status.PASS);
    }

    @Test
    void settleRefusesAMissingStatus() {
        CaseList list = list("no-status.json");
        list.save(List.of(pass("C1")));

        assertThatThrownBy(() -> list.settle("C1", null, "证据", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PASS / FAIL / ERROR");
    }
}
