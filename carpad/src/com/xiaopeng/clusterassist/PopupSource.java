package com.xiaopeng.clusterassist;

import android.content.Context;
import android.util.Log;

/**
 * 车机侧弹窗检测的胶水层：起一条专用 adb 连接跑 logcat，把行喂给 {@link PopupLogic}，
 * 200ms 重算一次左右状态，供 {@link PushService} 写进 ext.json 的 {@code popup} 字段。
 *
 * <p>为什么必须是 logcat：仪表弹窗的信号只在车机 HAL 日志里，普通 App 进程读不到
 * （小鹏的 CarPermissionManager 会拒），只能借本地 adb 的 shell 身份——和盲区探针同一套机制。
 *
 * <p>几个必须踩住的点（都是实测踩出来的）：
 * <ul>
 *   <li>{@code -T 1}：不带这句，logcat 启动时会把 8MB 历史缓冲一次性倒进管道，首启卡几十秒；</li>
 *   <li>行龄看门狗：{@code readLine} 在某些情况下不会返回 EOF，90s 没有任何行就重建整条流；</li>
 *   <li>失败退避：这台车的 adbd 对新连接的认证不稳，失败别每 10 秒重连一次，5s→60s 退避；</li>
 *   <li>只影响自己：独立连接 + 独立线程，坏掉时只是「不隐藏卡片」，不会拖累盲区/电压。</li>
 * </ul>
 */
public final class PopupSource {
    private static final String TAG = "P7Popup";

    /**
     * 只取两个 tag，其它静音。
     * {@code -T 1} 见类注释；{@code '*:S'} 会先静音全部，再让上面两个 tag 生效。
     */
    private static final String LOGCAT_CMD =
            "/system/bin/logcat -v epoch -T 1 XpengVehicleHal:I CarDrivingScene:I '*:S' 2>/dev/null";

    /** 单次 readLine 阻塞上限（只为让线程有机会检查退出标志，不影响实时性）。 */
    private static final int READ_TIMEOUT_MS = 20000;
    /** 90s 无任何行 → 判定流已死，重建（对齐参考实现） */
    private static final long STREAM_STALE_MS = 90000L;
    /** 重算节拍：与参考实现一致 */
    private static final long TICK_MS = 200L;

    /** 检测状态机。重新 start 时整体换新，避免上一轮的锁存/窗口带进来 */
    private static volatile PopupLogic logic = new PopupLogic();

    private static volatile boolean running;
    /**
     * 启动代次：每次 start 自增，两条线程都记着自己属于哪一代。
     * 只靠 running 布尔量不够——stop 后马上 start，旧线程从 sleep 里醒来会看到
     * running 又变成 true，于是和新线程一起跑：双倍 tick、双倍版本号、双倍 doPush。
     */
    private static volatile int generation;
    private static volatile LocalAdb.ShellLink link;
    private static volatile long lastLineAt;
    private static volatile String lastErr = "";
    /** 状态版本号：L/R 每翻转一次 +1，PushService 靠它判断有没有新状态要推。 */
    private static volatile int version;
    private static volatile boolean lastL, lastR;

    private PopupSource() { }

    /** 起检测线程（可重复调用；已在跑时直接返回）。调用方不需要在后台线程。 */
    public static synchronized void start(Context ctx) {
        if (running) return;
        if (ctx == null) return;
        running = true;
        final int gen = ++generation;
        logic = new PopupLogic();
        lastL = false;
        lastR = false;
        final Context app = ctx.getApplicationContext();
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                streamLoop(app, gen);
            }
        }, "p7-popup");
        t.setDaemon(true);
        t.start();
        Thread ticker = new Thread(new Runnable() {
            @Override public void run() {
                tickLoop(gen);
            }
        }, "p7-popup-tick");
        ticker.setDaemon(true);
        ticker.start();
    }

    /** 停掉（关连接、日志流随之被杀）。 */
    public static synchronized void stop() {
        running = false;
        generation++;   // 让还在跑的两条线程立刻判定自己过期
        final LocalAdb.ShellLink l = link;
        link = null;
        logic.tickAt(System.currentTimeMillis()); // 让状态尽快归零，别把最后一条锁存留在外面
        syncVersion();
        // 关连接会往 socket 写 CLSE，理论上可能阻塞；调用方有主线程（Service.onDestroy、
        // 设置页轮询），不能在这里赌网络状态，扔到后台关。
        if (l != null) {
            Thread t = new Thread(new Runnable() {
                @Override public void run() { l.close(); }
            }, "p7-popup-close");
            t.setDaemon(true);
            t.start();
        }
    }

    public static boolean running() {
        return running;
    }

    /** L/R 翻转计数。PushService 每次比对，变了就立刻推一次 ext.json。 */
    public static int version() {
        return version;
    }

    /** ext.json 的 popup 字段值（只含相对剩余毫秒，不含绝对时刻——两边时钟差 8 小时）。 */
    public static String json() {
        return logic.json();
    }

    /** 诊断行（实车标定：状态、原因、档位、各窗口余量）。 */
    public static String diag() {
        return logic.diag() + " adb=" + LocalAdb.state() + " err=" + (lastErr.length() == 0 ? "-" : lastErr);
    }

    /* ===================== 内部 ===================== */

    private static void tickLoop(int gen) {
        while (running && gen == generation) {
            try { Thread.sleep(TICK_MS); } catch (InterruptedException e) { return; }
            if (gen != generation) return;
            logic.tickAt(System.currentTimeMillis());
            syncVersion();
        }
    }

    /** L/R 变化时 bump 版本并打一行诊断，方便实车 `logcat -s P7Popup` 核对。 */
    private static void syncVersion() {
        boolean l = logic.left();
        boolean r = logic.right();
        if (l == lastL && r == lastR) return;
        lastL = l;
        lastR = r;
        version++;
        Log.i(TAG, (l || r ? "弹窗出现 " : "弹窗消失 ") + diag() + " msL=" + logic.msLeft() + " msR=" + logic.msRight());
    }

    private static void streamLoop(Context ctx, int gen) {
        int fails = 0;
        while (running && gen == generation) {
            LocalAdb.ShellLink l = null;
            try {
                l = LocalAdb.openDedicatedShell(ctx, LOGCAT_CMD);
                link = l;
                fails = 0;
                lastErr = "";
                lastLineAt = System.currentTimeMillis();
                Log.i(TAG, "logcat 流已建立");
                AdbClient.ShellStream st = l.stream;
                while (running && gen == generation) {
                    String line = st.readLine(READ_TIMEOUT_MS);
                    if (line == null) {
                        // readLine 的超时与 EOF 都返回 null，只能靠行龄判断死活：
                        // 真的安静（停车）就继续等，超过 90s 没有任何行才重建。
                        if (System.currentTimeMillis() - lastLineAt > STREAM_STALE_MS) {
                            lastErr = "90s 无日志行，重建流";
                            Log.w(TAG, lastErr);
                            break;
                        }
                        try { Thread.sleep(200L); } catch (InterruptedException e) { return; }
                        continue;
                    }
                    lastLineAt = System.currentTimeMillis();
                    logic.feedLine(line);
                }
            } catch (Throwable t) {
                lastErr = String.valueOf(t.getMessage() != null ? t.getMessage() : t);
                Log.w(TAG, "logcat 流失败: " + lastErr);
            } finally {
                if (l != null) l.close();
                if (link == l) link = null;
            }
            if (!running || gen != generation) break;
            // 退避：前两次 5s（可能是 adbd 抖一下），连续失败后拉到 60s，别把 adbd 打烦
            fails++;
            long waitMs = fails <= 2 ? 5000L : 60000L;
            try { Thread.sleep(waitMs); } catch (InterruptedException e) { return; }
        }
    }
}
