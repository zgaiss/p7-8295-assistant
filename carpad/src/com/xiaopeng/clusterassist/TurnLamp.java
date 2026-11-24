package com.xiaopeng.clusterassist;

/**
 * 转向灯「仍在闪」的纯状态判定，不依赖 Android，可在主机上直接单测。
 *
 * <p>XUI contextinfo 只上报转向灯的亮/灭（约 480ms 亮、480ms 灭），没有拨杆开关量：
 * 看起来「灭」不代表关灯，只是闪光的灭相。所以必须用时间窗口跨过灭相，不能把每次
 * 灭事件当成熄灯。</p>
 *
 * <p>原实现只记「最近一次亮」并保持 1600ms：拨杆回位时最后一次是灭事件，不被采纳，
 * 要再等一个 1.6s 才停相机（实车日志里正是回位后 1.44s 才还原 AVM 视角）。这里把
 * 亮、灭都算作相变，窗口只需大于一个灭相（480ms）即可：{@link #ACTIVE_MS}=800ms
 * 既能跨过灭相不至于每 480ms 闪断一次，又能在拨杆回位后 800ms 内撤区。</p>
 *
 * <p>注册监听时服务可能先推一份当前状态，其中的「灭」不是本会话的相变：没有先见到
 * 亮就不激活，避免启动瞬间误开影像。</p>
 */
final class TurnLamp {
    /** 与影像协议 / {@link MirrorTriggerGate} 的 side 编码一致：1=左 2=右。 */
    static final int LEFT = 1;
    static final int RIGHT = 2;

    /** 相变保持窗口：必须 &gt; 闪光灭相（实测 480ms），再留出事件抖动的余量。 */
    static final long ACTIVE_MS = 800;

    private long leftBeatAt, rightBeatAt;
    private boolean leftLit, rightLit;

    /** 记录一次亮/灭事件。now 用单调时钟（SystemClock.elapsedRealtime）。 */
    synchronized void event(int side, boolean on, long now) {
        if (side != LEFT && side != RIGHT) return;
        boolean lit = side == LEFT ? leftLit : rightLit;
        // 没亮过的「灭」是注册快照，不是相变；采纳它会让启动瞬间误判为正在打灯。
        if (!on && !lit) return;
        if (side == LEFT) {
            leftBeatAt = now;
            leftLit = on;
        } else {
            rightBeatAt = now;
            rightLit = on;
        }
    }

    /** 该侧最近一次相变是否还在窗口内（= 拨杆仍在这个方向，或仍在闪）。 */
    synchronized boolean active(int side, long now) {
        long beat = side == LEFT ? leftBeatAt : rightBeatAt;
        return beat != 0 && now - beat < ACTIVE_MS;
    }

    /** 订阅断开/重建时清空，避免残留「亮」被误判。 */
    synchronized void reset() {
        leftBeatAt = 0;
        rightBeatAt = 0;
        leftLit = false;
        rightLit = false;
    }
}
