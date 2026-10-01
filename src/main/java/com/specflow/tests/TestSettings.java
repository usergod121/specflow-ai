package com.specflow.tests;

/**
 * 这一次测试怎么跑。
 *
 * <p>现在只有一项：跑不跑集成测试。它<b>不能</b>由引擎自己猜——「要不要连真库」
 * 是一次成本决策（起容器、等健康检查、几分钟），该由人在界面上勾，没勾就只跑单元。
 * 而且它必须<b>显式</b>传进来：默认值写在类里，而不是从「环境声明在不在」推出来，
 * 否则「有 env.yaml 的项目」会在没人点过任何东西的情况下自动跑起容器来。
 *
 * @param integration 勾了集成测试。勾上之后：模型要多给一个集成入口脚本，
 *                    引擎要先确认环境就绪、跑一遍 reset，再把两批用例都跑一遍
 */
public record TestSettings(boolean integration) {

    /**
     * 只跑单元测试——这批之前的行为，一个字节都没变。
     *
     * <p>它同时是「项目没声明测试环境」时的唯一取值（十五.5：跳过初始化就只能勾单元）。
     */
    public static final TestSettings UNIT_ONLY = new TestSettings(false);
}
