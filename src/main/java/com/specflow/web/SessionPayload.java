package com.specflow.web;

import com.specflow.session.Session;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 会话视图（§19）的载荷：界面顶部那条状态、历史轮次、以及「撤回本轮」那一枚的可用性。
 *
 * <p><b>为什么单独成类。</b>它是<b>算给界面看的</b>，不是留档：留档那边是逐轮的记录，
 * 而这里要把 N 轮折成一句话（第几轮、两个通过率、成本、能不能撤）。把它写在
 * {@link RunService} 里，那个类就不只是「把请求变成一次运行」，还得管「这句话怎么说」。
 *
 * <p><b>两个通过率为什么都要给。</b>用户的原话是要同时看到「本轮通过率」和「会话累计通过率」
 * ——一个说这一轮怎么样，一个说这件事到现在怎么样。只给一个，另一个就得靠界面自己乘除，
 * 而两个数的口径（停用的用例不进分母、被撤回的轮次不进累计）必须只有一处说了算。
 *
 * <p><b>为什么连「为什么不能撤」也要给。</b>那一枚撤销按钮会有点不动的时候（那一轮自己回滚了、
 * 已经撤过了）。给一个灰按钮而不说为什么，用户只会以为界面坏了。
 */
final class SessionPayload {

    private SessionPayload() {
    }

    /**
     * 会话视图的整份载荷。
     *
     * @param session 现在开着的会话；{@code null} = 没有会话
     * @param busy    此刻有任务在跑（界面据此禁用动作，别让人在跑的时候点接受）
     */
    static Map<String, Object> of(Session session, boolean busy) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("present", session != null);
        payload.put("busy", busy);
        if (session == null) {
            // 没有会话时也要把话说全：界面直接画「还没有会话」这一档，
            // 而不是靠一堆缺键去猜（缺键和「值是 0」在 JS 里几乎一样，猜错的后果是显示成「第 0 轮」）
            payload.put("id", "");
            payload.put("round", 0);
            payload.put("rounds", 0);
            payload.put("liveRounds", 0);
            payload.put("consecutiveFailing", 0);
            payload.put("softHint", null);
            payload.put("canUndoRound", false);
            payload.put("undoRoundWhy", "还没有会话：先点「运行」跑第一轮");
            payload.put("roundPassed", 0);
            payload.put("roundTotal", 0);
            payload.put("sessionPassed", 0);
            payload.put("sessionTotal", 0);
            payload.put("roundCalls", 0);
            payload.put("roundCost", null);
            payload.put("roundMillis", 0L);
            payload.put("roundDuration", "");
            payload.put("current", null);
            payload.put("history", List.of());
            return payload;
        }
        Session.Round current = session.current().orElse(null);
        payload.put("id", session.id());
        payload.put("round", session.round());
        payload.put("rounds", session.rounds().size());
        payload.put("liveRounds", session.live().size());
        payload.put("consecutiveFailing", session.consecutiveFailing());
        // 没到那一档时给 null 而不是空串：界面按「有没有」判就行，
        // 空串在 JS 里也是假值，两种写法混着用迟早有一处忘了判
        payload.put("softHint", session.softHint().isEmpty() ? null : session.softHint());
        payload.put("canUndoRound", session.canUndoRound());
        payload.put("undoRoundWhy", session.undoRoundWhy());
        payload.put("roundPassed", current == null ? 0 : current.passed());
        payload.put("roundTotal", current == null ? 0 : current.total());
        payload.put("sessionPassed", session.passed());
        payload.put("sessionTotal", session.total());
        payload.put("roundCalls", current == null ? 0 : current.calls());
        // 那个数是怎么来的：界面把它摊在成本那句话上（悬停可见）。老记录没有这一栏时给 null，
        // 界面于是不画那句来历——「算不出来」和「四项都是 0」是两件事
        payload.put("roundCost", current == null ? null : current.cost());
        payload.put("roundMillis", current == null ? 0L : current.millis());
        payload.put("roundDuration", current == null ? "" : duration(current.millis()));
        payload.put("current", current == null ? null : round(current));
        payload.put("history", session.newestFirst().stream().map(SessionPayload::round).toList());
        return payload;
    }

    /**
     * 一轮的载荷。
     *
     * <p>失败清单、用例清单、覆盖核对<b>原样带上</b>（它们是留档里那几个类型本身）：
     * 界面画失败清单要的就是这几样，在这里另造一套「界面上够用的精简形状」，
     * 就是第二个数据源——两边迟早对不上，而对不上的表现是「清单里少了一条，谁也不知道为什么」。
     */
    private static Map<String, Object> round(Session.Round round) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("round", round.round());
        item.put("recordId", round.recordId());
        item.put("status", round.status());
        item.put("detail", round.detail());
        item.put("startedAt", round.startedAt());
        item.put("finishedAt", round.finishedAt());
        item.put("calls", round.calls());
        item.put("millis", round.millis());
        item.put("duration", duration(round.millis()));
        item.put("undone", round.undone());
        item.put("removed", round.removed());
        item.put("settlement", round.settlement());
        item.put("settlementSummary", round.settlementSummary());
        item.put("snapshot", round.snapshot());
        item.put("passed", round.passed());
        item.put("total", round.total());
        item.put("failing", round.failing());
        item.put("testCases", round.testCases());
        item.put("tests", round.tests());
        item.put("changes", round.changes());
        item.put("verdicts", round.verdicts());
        item.put("disabled", round.disabled());
        // 不是回喂时给 null：空表和「没有回喂过」是两件事，界面要能分开说
        item.put("refeed", round.refeed().isEmpty() ? null : round.refeed());
        item.put("coverage", round.coverage());
        return item;
    }

    /**
     * 毫秒 → 人话（「7 分 1 秒」）。
     *
     * <p>为什么在服务端算：界面那一侧要显示它的地方不止一处（状态条、历史轮次），
     * 而「多久算多久」这种换算是每个调用方都会写歪一遍的东西。
     * 算不出来（老记录没有跑完时刻）时给空串：界面那一栏整个不显示，而不是显示「0 秒」。
     */
    static String duration(long millis) {
        if (millis <= 0) {
            return "";
        }
        long seconds = Math.round(millis / 1000.0);
        if (seconds < 60) {
            return seconds + " 秒";
        }
        long minutes = seconds / 60;
        long rest = seconds % 60;
        if (minutes < 60) {
            return rest == 0 ? minutes + " 分" : minutes + " 分 " + rest + " 秒";
        }
        long hours = minutes / 60;
        long restMinutes = minutes % 60;
        return restMinutes == 0 ? hours + " 小时" : hours + " 小时 " + restMinutes + " 分";
    }
}
