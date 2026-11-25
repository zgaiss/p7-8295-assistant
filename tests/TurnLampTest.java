package com.xiaopeng.clusterassist;

/** Host-only checks for the pure turn-lamp phase window. No Android, no HAL. */
public final class TurnLampTest {
    private static int checks;

    private static void check(boolean ok, String message) {
        checks++;
        if (!ok) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        // 正常闪烁：亮 1000，灭 1480，下一个亮 1960。灭相期间必须保持激活。
        TurnLamp blink = new TurnLamp();
        blink.event(TurnLamp.LEFT, false, 900);   // 注册快照里的「灭」不算相变
        check(!blink.active(TurnLamp.LEFT, 900), "snapshot off must not activate a side");
        blink.event(TurnLamp.LEFT, true, 1000);
        check(blink.active(TurnLamp.LEFT, 1000), "on event activates immediately");
        blink.event(TurnLamp.LEFT, false, 1480);
        check(blink.active(TurnLamp.LEFT, 1480), "off phase keeps the side active");
        check(blink.active(TurnLamp.LEFT, 1480 + TurnLamp.ACTIVE_MS - 1),
                "active until the window edge");
        check(!blink.active(TurnLamp.LEFT, 1480 + TurnLamp.ACTIVE_MS),
                "inactive at the window edge");
        blink.event(TurnLamp.LEFT, true, 1960);
        check(blink.active(TurnLamp.LEFT, 1960 + TurnLamp.ACTIVE_MS - 1),
                "next on keeps blinking alive");
        check(!blink.active(TurnLamp.LEFT, 1960 + TurnLamp.ACTIVE_MS),
                "a full window without events ends the blink");

        // 拨杆回位：最后一次灭事件立刻成为基准，只等一个窗口，不再等亮事件超时。
        TurnLamp cancel = new TurnLamp();
        cancel.event(TurnLamp.LEFT, true, 1000);
        cancel.event(TurnLamp.LEFT, false, 1480);
        cancel.event(TurnLamp.LEFT, true, 1960);
        cancel.event(TurnLamp.LEFT, false, 2440);
        check(cancel.active(TurnLamp.LEFT, 2440), "cancel keeps the view for one window");
        check(cancel.active(TurnLamp.LEFT, 2440 + TurnLamp.ACTIVE_MS - 1),
                "cancel still active just before the window edge");
        check(!cancel.active(TurnLamp.LEFT, 2440 + TurnLamp.ACTIVE_MS),
                "cancel clears within one window (was 1.6s from the last on)");

        // 重复的「灭」不能把窗口一直往后推，否则熄灯后画面会赖着不走。
        cancel.event(TurnLamp.LEFT, false, 2800);
        check(!cancel.active(TurnLamp.LEFT, 2440 + TurnLamp.ACTIVE_MS),
                "duplicate off cannot extend the hold");

        // 两侧独立；双闪时两侧都活，交给门控判成 STOP。
        TurnLamp both = new TurnLamp();
        both.event(TurnLamp.LEFT, true, 10);
        both.event(TurnLamp.RIGHT, true, 20);
        check(both.active(TurnLamp.LEFT, 30) && both.active(TurnLamp.RIGHT, 30),
                "hazards keep both sides active");
        both.event(TurnLamp.LEFT, false, 500);
        check(both.active(TurnLamp.LEFT, 500) && both.active(TurnLamp.RIGHT, 500),
                "left off phase does not disturb the right side");

        // reset 用于订阅重建，残留状态不能复活旧方向。
        both.reset();
        check(!both.active(TurnLamp.LEFT, 500) && !both.active(TurnLamp.RIGHT, 500),
                "reset clears both sides");

        // 未知属性号不进状态。
        TurnLamp unknown = new TurnLamp();
        unknown.event(0, true, 100);
        unknown.event(9, true, 100);
        check(!unknown.active(TurnLamp.LEFT, 100) && !unknown.active(TurnLamp.RIGHT, 100),
                "unknown property ids are ignored");

        System.out.println("PASS: " + checks + " turn-lamp window checks");
    }
}
