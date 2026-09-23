package com.specflow;

import com.specflow.spec.ContextItem;
import com.specflow.spec.PatchStrategyType;
import com.specflow.spec.Spec;
import com.specflow.spec.TraceSpec;
import com.specflow.spec.VerifySpec;

import java.util.List;
import java.util.Map;

/**
 * 测试用 {@link Spec} 构造器。
 *
 * <p>{@code Spec} 有十个字段，每个测试都手写一遍会让断言本身淹没在参数里。
 * 这里给一个只有测试才会用的 builder，未设置的字段取固定默认值。
 *
 * <p>注意没有「新建还是修改」的开关：那由目标文件在不在决定，
 * 所以 {@code spec(List.of("New.java"))} 只要那个文件不存在，走的就是新建。
 */
public final class TestSpecs {

    private TestSpecs() {
    }

    public static Spec spec(List<String> targets) {
        return builder().targets(targets).build();
    }

    public static Spec spec(List<String> targets, VerifySpec verify) {
        return builder().targets(targets).verify(verify).build();
    }

    public static Builder builder() {
        return new Builder();
    }

    /** 测试用 builder：字段公开可变，不追求生产代码的不可变性。 */
    public static final class Builder {

        private int version = Spec.CURRENT_VERSION;
        private PatchStrategyType strategy = PatchStrategyType.SEARCH_REPLACE;
        private String template;
        private Map<String, String> variables = Map.of();
        private String prompt = "改点东西";
        private List<String> acceptance = List.of();
        private List<String> targets = List.of("a.txt");
        private List<String> constraints = List.of();
        private List<ContextItem> context = List.of();
        private VerifySpec verify = VerifySpec.DEFAULT;
        private TraceSpec trace = TraceSpec.EMPTY;

        public Builder version(int value) {
            this.version = value;
            return this;
        }

        public Builder strategy(PatchStrategyType value) {
            this.strategy = value;
            return this;
        }

        public Builder template(String value) {
            this.template = value;
            return this;
        }

        public Builder variables(Map<String, String> value) {
            this.variables = value;
            return this;
        }

        public Builder prompt(String value) {
            this.prompt = value;
            return this;
        }

        public Builder acceptance(List<String> value) {
            this.acceptance = value;
            return this;
        }

        public Builder targets(List<String> value) {
            this.targets = value;
            return this;
        }

        public Builder constraints(List<String> value) {
            this.constraints = value;
            return this;
        }

        public Builder context(List<ContextItem> value) {
            this.context = value;
            return this;
        }

        public Builder verify(VerifySpec value) {
            this.verify = value;
            return this;
        }

        public Builder trace(TraceSpec value) {
            this.trace = value;
            return this;
        }

        public Spec build() {
            return new Spec(version, strategy, template, variables, prompt, acceptance, targets,
                    constraints, context, verify, trace);
        }
    }
}
