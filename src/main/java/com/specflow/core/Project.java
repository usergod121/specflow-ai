package com.specflow.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * <b>一个项目</b>：它的根、它的配置（{@code .specflow/project.yaml}）、它的密钥。
 *
 * <p>配置只有两段，都是给人和引擎看的：
 * <pre>
 * build:
 *   compile: "mvn -q -DskipTests compile"     # ① 的闸要它；④ 每次交差跑它
 * llm:
 *   base-url: "https://api.deepseek.com"
 *   model: "deepseek-chat"
 *   api-key-env: "SPECFLOW_API_KEY"           # 密钥只从环境变量或 local.env 取
 *   temperature: 0.2
 *   timeout-seconds: 120
 * </pre>
 *
 * <p>密钥的取法只有一处：环境变量优先，其次 {@code .specflow/local.env} 里那一行。
 * <b>不读、不回显、不写进任何日志</b>——这是这一版对用户的一条约定的底线。
 */
public final class Project {

    /** 一段模型的配置。字段名与 YAML 里的 kebab-case 对应（见 {@link Raw}）。 */
    public record LlmConfig(String baseUrl, String model, String apiKeyEnv, double temperature,
                            int timeoutSeconds) {

        public static final String DEFAULT_BASE = "https://api.deepseek.com";
        public static final String DEFAULT_MODEL = "deepseek-chat";
        public static final String DEFAULT_KEY_ENV = "SPECFLOW_API_KEY";

        public LlmConfig {
            baseUrl = baseUrl == null || baseUrl.isBlank() ? DEFAULT_BASE : baseUrl.strip();
            model = model == null || model.isBlank() ? DEFAULT_MODEL : model.strip();
            apiKeyEnv = apiKeyEnv == null || apiKeyEnv.isBlank() ? DEFAULT_KEY_ENV : apiKeyEnv.strip();
            temperature = temperature <= 0 ? 0.2 : temperature;
            timeoutSeconds = timeoutSeconds <= 0 ? 180 : timeoutSeconds;
        }

        public static LlmConfig defaults() {
            return new LlmConfig(null, null, null, 0, 0);
        }
    }

    /** project.yaml 的形状（Jackson 直接映射；不认识的字段忽略）。 */
    public record Raw(Build build, Llm llm) {

        public record Build(String compile) {
        }

        public record Llm(String baseUrl, String model, String apiKeyEnv, Double temperature,
                          Integer timeoutSeconds) {
        }
    }

    private final Layout layout;
    private final Raw raw;

    private Project(Layout layout, Raw raw) {
        this.layout = layout;
        this.raw = raw == null ? new Raw(null, null) : raw;
    }

    public static Project open(Path root) {
        Layout layout = new Layout(root);
        return new Project(layout, Json.readYaml(layout.projectYaml(), Raw.class));
    }

    public Layout layout() {
        return layout;
    }

    public Path root() {
        return layout.root();
    }

    /** ① 与 ④ 用的那条编译命令；没配时是空串（闸会因此拦住 ①）。 */
    public String compile() {
        String command = raw.build() == null ? null : raw.build().compile();
        return command == null ? "" : command.strip();
    }

    /** 模型配置（没写就用默认值：默认 base-url 与 model 是这一版认可的可用组合）。 */
    public LlmConfig llm() {
        Raw.Llm llm = raw.llm();
        if (llm == null) {
            return LlmConfig.defaults();
        }
        return new LlmConfig(llm.baseUrl(), llm.model(), llm.apiKeyEnv(),
                llm.temperature() == null ? 0 : llm.temperature(),
                llm.timeoutSeconds() == null ? 0 : llm.timeoutSeconds());
    }

    /** 密钥：环境变量优先，其次 {@code .specflow/local.env}。取不到返回 {@code null}。 */
    public String apiKey() {
        String name = llm().apiKeyEnv();
        String fromEnv = System.getenv(name);
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv.strip();
        }
        for (String row : Atomic.read(layout.localEnv(), "").split("\r?\n")) {
            String line = row.strip();
            if (line.startsWith(name + "=")) {
                return line.substring(name.length() + 1).strip();
            }
        }
        return null;
    }

    /** 这个目录像不像一个能干活的项目（有没有 {@code .specflow}）。 */
    public boolean prepared() {
        return Files.isDirectory(layout.specflow());
    }

    /** 铺一份最小配置（用户在一个陌生目录上点「初始化」时用）。 */
    public void bootstrap() {
        if (!Files.isRegularFile(layout.projectYaml())) {
            Atomic.write(layout.projectYaml(), """
                    # specflow 项目配置
                    build:
                      compile: ""

                    llm:
                      base-url: "https://api.deepseek.com"
                      model: "deepseek-chat"
                      api-key-env: "SPECFLOW_API_KEY"
                      temperature: 0.2
                    """);
        }
        if (!Files.isRegularFile(layout.localEnv())) {
            Atomic.write(layout.localEnv(), """
                    # 密钥只放在这里（这个文件不进版本库、不回显、不进副本）
                    # SPECFLOW_API_KEY=sk-...
                    """);
        }
    }

    /** 项目根下的一层条目（给探针与「AI 探测环境」当开场情报用；读不出来就是空）。 */
    public List<String> topLevel() {
        try (var stream = Files.list(root())) {
            return stream.map(one -> {
                        String name = one.getFileName().toString();
                        return Files.isDirectory(one) ? name + "/" : name;
                    })
                    .filter(name -> !name.equals(Layout.DIR + "/"))
                    .sorted()
                    .toList();
        } catch (java.io.IOException e) {
            return List.of();
        }
    }
}
