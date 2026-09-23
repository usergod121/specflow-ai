package com.specflow.llm;

import java.util.List;

/**
 * 模型调用入口。
 *
 * <p>抽成接口有两个具体收益：
 * <ul>
 *   <li>单元测试可以注入假实现，不需要联网、不需要密钥</li>
 *   <li>将来换 SDK 或换厂商不影响 Agent 编排</li>
 * </ul>
 *
 * <p>契约：<b>要么返回非空文本，要么抛异常</b>。不返回 {@code null}，
 * 不返回空串——把「模型没说话」这种异常状态变成显式失败，避免下游拿到空补丁再报一个看不懂的错。
 */
@FunctionalInterface
public interface LlmClient {

    /**
     * 同步调用一次模型。
     *
     * @param messages 对话消息，至少包含一条 system 与一条 user
     * @return 模型输出的纯文本
     * @throws LlmException 网络失败、鉴权失败、超时、或响应中不含文本内容
     */
    String complete(List<ChatMessage> messages);
}
