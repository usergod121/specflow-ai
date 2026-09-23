package com.specflow.verify;

import com.specflow.project.ProjectConfig;
import com.specflow.spec.Spec;

import java.nio.file.Path;

/**
 * 校验阶段需要知道的一切。
 *
 * <p>用上下文对象而不是一串参数，是为了让 {@link Verifier} 的实现
 * 在将来需要更多信息（比如上一次的校验输出、测试报告目录）时
 * 不必修改接口签名——签名一改，所有实现和调用点都要跟着动。
 *
 * @param projectRoot 项目根目录，也是执行构建命令的工作目录
 * @param spec        本次契约，提供 {@code verify} 段
 * @param project     项目级配置，提供构建命令与模型配置
 */
public record VerificationContext(
        Path projectRoot,
        Spec spec,
        ProjectConfig project
) {
}
