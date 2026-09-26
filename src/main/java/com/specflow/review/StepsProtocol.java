package com.specflow.review;

/**
 * 「只产施工单」的轻协议——没跑过检查就直接开工时，先花一次调用把步子拆出来。
 *
 * <p>为什么不复用 {@link ReviewProtocol}：那份协议一次要三样东西（摘要、流程图、缺失项），
 * 而此刻我们已经决定动手了，只差一份顺序。<b>要得越多，越贵、越容易跑偏</b>——
 * 而且它会顺手把「缺失项」再列一遍，那些东西上一轮如果已经问过、用户已经答过，
 * 再问一次就是纯浪费。
 *
 * <p>它和完整检查协议共用同一段 {@link ReviewProtocol#STEPS_RULES} 与
 * {@link ReviewProtocol#STEPS_WHY}：施工单的写法只有一份，两个入口的模型看到的规矩
 * 必须逐字一样，否则同一份需求走检查和不走检查会拆出两种风格的步子，而引擎按同一套规则执行它们。
 */
public final class StepsProtocol {

    private StepsProtocol() {
    }

    public static final String INSTRUCTIONS = """
            现在只需要你做一件事：把这个需求拆成一份「施工单」——按执行顺序排好的一步一步。
            不要写代码，也不要给摘要、流程图或缺失项清单，只要 STEPS 块。

            %s
            %s
            %s STEPS

            %s
            硬性规则：
            1. 步数必须是 3 到 7 步。少于 3 步说明你把它当成一件事做了（那就不需要施工单）；
               多于 7 步请把相邻的合并——步数越多，每一步能分到的重试预算就越少。
            2. 第 3 栏的文件**必须逐个来自目标文件清单**，清单之外的文件你动不了（新建也不行）。
               如果某一步非动清单外的文件不可，就把它并进别的一步，或者改变做法。
            3. 最后一步不许标「中间态」：施工单跑完，项目必须能编译。
            4. 除了 STEPS 块，前后最多写一句话。
            """.formatted(ReviewProtocol.STEPS_MARKER, ReviewProtocol.STEPS_RULES,
            ReviewProtocol.END_SUFFIX, ReviewProtocol.STEPS_WHY);
}
