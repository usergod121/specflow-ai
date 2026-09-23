package com.specflow.web;

import com.specflow.exception.SpecflowException;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 「正在让用户挑目录」这件事的状态。
 *
 * <p><b>为什么要有这么一份服务端状态，而不是让界面干等一个 HTTP 响应。</b>
 * 挑目录是人在操作，可能几十秒也可能几分钟。把一个请求挂那么久，中途刷新一下页面、
 * 换个标签页、或者连接被谁掐了，界面就再也说不清「那个窗口还开着吗」——
 * 用户看到的是一个可以再点的按钮，于是点下去就又多出一个窗口。实测就是这个症状。
 *
 * <p>状态放到服务端之后，「同时只可能有一个窗口」这件事就有了唯一的裁判：
 * 任何一次点击、任何一个标签页、刷新多少次，看到的都是同一份状态；
 * 已经在挑了就把当前状态原样还回去（{@link #start()}），谁也弹不出第二个窗口。
 */
final class FolderPicking {

    /** 弹一次窗口、等用户选。抽成接口是为了让状态机能独立测——它不该关心窗口是怎么弹出来的。 */
    @FunctionalInterface
    interface Picker {

        /**
         * @return 用户选中的目录；取消返回 {@code null}
         */
        String pick();

        /** 用户中途不要了：把已经弹出来的窗口收掉。默认什么都不做。 */
        default void abort() {
        }
    }

    enum Status {
        /** 现在没有窗口。 */
        IDLE,
        /** 窗口开着，等用户选。 */
        PICKING,
        /** 用户选好了。 */
        PICKED,
        /** 用户点了取消。 */
        CANCELLED,
        /** 弹不出来；原因在 {@link State#error()}。 */
        FAILED
    }

    /**
     * @param status 进行到哪一步
     * @param path   用户选中的目录；只有 {@link Status#PICKED} 时有值
     * @param error  弹不出来时的原因；只有 {@link Status#FAILED} 时有值
     */
    record State(Status status, String path, String error) {

        static final State IDLE = new State(Status.IDLE, null, null);
        static final State PICKING = new State(Status.PICKING, null, null);

        boolean isPicking() {
            return status == Status.PICKING;
        }
    }

    private final Picker picker;
    private final AtomicReference<State> state = new AtomicReference<>(State.IDLE);

    FolderPicking(Picker picker) {
        this.picker = picker;
    }

    State current() {
        return state.get();
    }

    /**
     * 把「用户已经处理过这个结果了」记下来，回到没有窗口的状态。
     *
     * <p>界面打开目录之后要认领一次：不然 {@link Status#PICKED} 会一直挂在那儿，
     * 用户下次切回欢迎页时页面又会拿它去打开那个项目——他刚关掉的东西自己又开了。
     */
    State consume() {
        state.set(State.IDLE);
        return State.IDLE;
    }

    /**
     * 用户中途把服务关了：把还开着的那个窗口收掉，别让它留在桌面上。
     */
    void abort() {
        picker.abort();
        state.set(State.IDLE);
    }

    /**
     * 开始挑一个目录。
     *
     * @return 已经在挑了的话返回<b>当前</b>状态，不会再弹一个窗口；
     *         否则从原地开始新的一次（上一个结果已经作废了）
     */
    State start() {
        while (true) {
            State now = state.get();
            if (now.isPicking()) {
                return now;
            }
            if (state.compareAndSet(now, State.PICKING)) {
                break;
            }
        }
        try {
            Thread thread = new Thread(this::pickOnce, "specflow-picker");
            thread.setDaemon(true);
            thread.start();
        } catch (Throwable t) {
            // 连线程都起不来的话（比如线程耗尽），绝不能把状态留在 PICKING——
            // 那样界面会永远停在「正在等你选…」，而根本没有任何窗口开着
            State failed = new State(Status.FAILED, null, "起不了挑目录的线程：" + t);
            state.set(failed);
            return failed;
        }
        return State.PICKING;
    }

    /**
     * 弹窗口、等用户选。跑在自己的线程上，因为它可能等很久——
     * 那个 HTTP 请求早就返回了，用户选中没有靠 {@link #current()} 来问。
     *
     * <p>这里接的是 {@link Throwable} 而不是 {@code RuntimeException}：
     * 没有 {@code java.desktop} 的精简运行时会抛 {@code NoClassDefFoundError}（一个 Error）。
     * 放它穿过去，状态就永远停在 PICKING，界面永久卡死且没有任何错误可看。
     */
    private void pickOnce() {
        try {
            String picked = picker.pick();
            state.set(picked == null
                    ? new State(Status.CANCELLED, null, null)
                    : new State(Status.PICKED, picked, null));
        } catch (Throwable t) {
            state.set(new State(Status.FAILED, null, describe(t)));
        }
    }

    private static String describe(Throwable t) {
        String message = t.getMessage();
        if (message == null || message.isBlank()) {
            // 一点信息都没有时，类名是唯一能说的东西
            return t.getClass().getName();
        }
        // 我们自己抛的异常，消息本来就是写给人看的，别再套一层类名上去
        return t instanceof SpecflowException
                ? message
                : t.getClass().getSimpleName() + "：" + message;
    }
}
