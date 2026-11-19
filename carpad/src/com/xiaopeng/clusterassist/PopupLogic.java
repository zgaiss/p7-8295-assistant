package com.xiaopeng.clusterassist;

/**
 * 仪表左右弹窗检测：把 HAL 日志行喂进来，推出「仪表左/右当前是否有原厂弹窗」。
 *
 * <p>这层是**纯逻辑**，不引用任何 Android API，也不自己开线程/进程——好处是可以在 PC 上
 * 用 javac + 历史 logcat 文本离线回放，把参数改完先回归再上车（见 tools/popup_replay 的用法
 * 注释）。真正起 logcat 流的胶水层是 {@link PopupSource}。
 *
 * <p>核心判断（参考 P7ClusterAssist 820A 实车标定）：<b>不追「弹窗自身信号」，追「按键」</b>。
 * 车机屏上直接调空调/音量，仪表不会弹窗；只有方向盘按键才弹，而且按键时序在弹窗之前。
 * 仪表侧也确实没有统一的「弹窗可见」状态量：DBus 349 个属性里没有聚合量，
 * OEM 的 {@code CardManager::showOverlay()} 只在 Kanzi 进程内部，不对外发布。
 *
 * <p>三类信号：
 * <ul>
 *   <li><b>事件窗</b>：方向盘左 4 键 → 左侧（ACC 开=车速/车距 3s，ACC 关=空调 2s）；
 *       右音量键 / {@code ICM_OSDSHOW} → 右侧</li>
 *   <li><b>状态锁存</b>：车门、雷达场景、N 档确认——锁存 + 撤销后 2s 收尾</li>
 *   <li><b>明确不用</b>：{@code XPU_HEAD_BEAT}（ACC 全程阶跃，会误静音整程）、
 *       {@code AMP_BODY_CTRL}（与音量零相关）、{@code SCU_RADAR_DIS_ACTIVE}（R 档倒车视图专用且会闪）</li>
 * </ul>
 *
 * <p>线程模型：{@link #feedLineAt} 与 {@link #tickAt} 都是 synchronized，日志线程和 200ms
 * 重算线程可以并发调用，查询方法同样加锁，调用方不需要额外同步。
 */
public final class PopupLogic {

    /* ===================== 可调参数（实车标定值；改前先用回放验证） ===================== */

    /** 空调温度/风量弹窗实测 2s → 窗 = 2s + 0.2s 余量 */
    public static final long WIN_SHORT_MS = 2200L;
    /** ACC 车速/车距调节弹窗实测 3s → 窗 = 3s + 0.2s */
    public static final long WIN_ACC_MS = 3200L;
    /** 同一「集」合并：窗结束后 0.9s 内再次触发视为同一集，卡片不会中途露头再消失 */
    public static final long EPISODE_GAP_MS = 900L;
    /** 雷达场景锁存静默失效：ENTER 后 60s 无任何场景行 → 强制解除（防 EXIT 丢失永久静音） */
    public static final long SCENE_STALE_MS = 60000L;
    /** 状态型弹窗（车门/雷达/N 档确认）撤销后的收尾余量 */
    public static final long GRACE_MS = 2000L;

    /**
     * 档位枚举里哪个值代表 P。
     *
     * <p>⚠ 这里用的是车机 HAL 的 {@code VCU_GEARLEV} 口径（820A 实车 1=P）；仪表侧
     * car.json 的 {@code getVcuGearState} 是 4=P，两套口径不能混用。
     * 8295 固件未标定，实车用 {@link #diag} 里的 {@code gear=} 值核对后再改。
     */
    private static final String PARK_GEAR = "1";

    /* ===================== 内部状态 ===================== */

    private long evLeftUntil;                 // 左侧事件窗到期时刻
    private long evRightUntil;                // 右侧事件窗到期时刻
    private long leftHoldUntil, rightHoldUntil; // episode 保持窗
    private boolean popL, popR;               // 对外结果（事件窗 ∪ 保持窗）
    private String whyL = "", whyR = "";      // 来源溯源：key/vol/door/radar/gear/hold

    private boolean radarActive;              // 雷达视图激活
    private long radarOffAt;                  // 雷达撤销时刻（+GRACE）
    private long sceneEventAt;                // 最近一次场景行时刻（SCENE_STALE_MS 兜底）

    private boolean doorLatch;                // 任一车门开
    private long doorClosedAt;                // 车门全关时刻（+GRACE）

    private String lastCspd;                  // SCU_CRUISESPD_SET_DISP 末值（≠0 = ACC 开启）
    private String lastGear;                  // VCU_GEARLEV 末值
    private String lastGearWarn;              // VCU_GEAR_WARNING 末值（≠0 = N 档确认可见）
    private boolean gearWarnLatch;
    private long gearWarnOffAt;

    /* ===================== 输入 ===================== */

    /** 喂一行日志（时刻取当前本地时钟）。 */
    public void feedLine(String line) {
        feedLineAt(line, System.currentTimeMillis());
    }

    /** 喂一行日志并指定观测时刻——离线回放用虚拟时间，结果才可复现。 */
    public synchronized void feedLineAt(String line, long nowMs) {
        if (line == null) return;
        String v;

        // ACC 车速设定值变化：兼作 ACC 开启态来源。
        // 放在按键判断之前无所谓（两者独立），但要放在 VCU_GEARLEV 之前——
        // 属性名互为前缀的只有 GEARLEV / CURRENT_GEARLEV，已单独处理。
        if (line.indexOf("SCU_CRUISESPD_SET_DISP") >= 0) {
            v = extractVal(line);
            if (v != null && !v.equals(lastCspd)) {
                lastCspd = v;
                evLeftUntil = Math.max(evLeftUntil, nowMs + WIN_ACC_MS);
            }
            return;
        }
        // 方向盘左 4 键 → 左侧（ACC 开=3.2s，关=2.2s）
        if (line.indexOf("SWS_LU_BUTTON") >= 0 || line.indexOf("SWS_LD_BUTTON") >= 0
                || line.indexOf("SWS_LR_BUTTON") >= 0 || line.indexOf("SWS_LL_BUTTON") >= 0) {
            evLeftUntil = Math.max(evLeftUntil, nowMs + (accActive() ? WIN_ACC_MS : WIN_SHORT_MS));
            return;
        }
        // 方向盘右音量键 → 右侧
        if (line.indexOf("SWS_RU_BUTTON") >= 0 || line.indexOf("SWS_RD_BUTTON") >= 0) {
            evRightUntil = Math.max(evRightUntil, nowMs + WIN_SHORT_MS);
            return;
        }
        // OSD 命令本体（静音帧 OSDValue:255 靠它兜底：静音键没有独立 SWS 属性名）→ 右侧
        if (line.indexOf("ICM_OSDSHOW") >= 0) {
            evRightUntil = Math.max(evRightUntil, nowMs + WIN_SHORT_MS);
            return;
        }
        // 雷达接近场景：ENTER=车头雷达距离视图出现，EXIT=消失
        if (line.indexOf("dispatchEventChanged") >= 0 || line.indexOf("checkRadarChanged") >= 0) {
            sceneEventAt = nowMs;
            if (line.indexOf("Status:ENTER") >= 0) {
                radarActive = true;
                radarOffAt = 0L;
            } else if (line.indexOf("Status:EXIT") >= 0 && radarActive) {
                radarActive = false;
                radarOffAt = nowMs;
            }
            return;
        }
        // N 档确认对话：≠0 = 可见期（实车值域 0/3/4）
        if (line.indexOf("VCU_GEAR_WARNING") >= 0) {
            v = extractVal(line);
            if (v != null) {
                lastGearWarn = v;
                try {
                    if (Integer.parseInt(v.trim()) != 0) {
                        gearWarnLatch = true;
                        gearWarnOffAt = 0L;
                    } else if (gearWarnLatch) {
                        gearWarnLatch = false;
                        gearWarnOffAt = nowMs;
                    }
                } catch (NumberFormatException ignored) { }
            }
            return;
        }
        // 档位（仅记录；P 档开门不弹窗，见 tickAt 的门条件）。
        // 两个属性名并存：VCU_GEARLEV（820A 实车）与 VCU_CURRENT_GEARLEV（部分固件）。
        if (line.indexOf("VCU_GEARLEV") >= 0 || line.indexOf("VCU_CURRENT_GEARLEV") >= 0) {
            v = extractVal(line);
            if (v != null) lastGear = v;
            return;
        }
        // 车门：4 字节四门开闭，任一非 0 = 有门开。
        // 必须精确匹配 ID，否则 BCM_DOOR_UNLOCK_REQRES 这类同前缀属性会误配。
        if (line.indexOf("BCM_DOOR(21411C19)") >= 0) {
            int[] arr = extractBytes(line);
            if (arr != null) {
                boolean any = false;
                for (int i = 0; i < arr.length; ++i) any = any || arr[i] != 0;
                if (any) {
                    doorLatch = true;
                    doorClosedAt = 0L;
                } else if (doorLatch) {
                    doorLatch = false;
                    doorClosedAt = nowMs;
                }
            }
            return;
        }
        // 其余行不触发（XPU_HEAD_BEAT / AMP_BODY_CTRL / SCU_RADAR_DIS_ACTIVE 都是反证过的死路）
    }

    /* ===================== 重算 ===================== */

    /** 200ms 一次的重算（实时由 PopupSource 调，回放由 Replay 调）。 */
    public void tick() {
        tickAt(System.currentTimeMillis());
    }

    public synchronized void tickAt(long now) {
        boolean door = (doorLatch && !isParkGear(lastGear))
                || (doorClosedAt != 0L && now - doorClosedAt < GRACE_MS);
        boolean gearWarn = gearWarnLatch
                || (gearWarnOffAt != 0L && now - gearWarnOffAt < GRACE_MS);
        boolean radar = (radarActive && now - sceneEventAt < SCENE_STALE_MS)
                || (radarOffAt != 0L && now - radarOffAt < GRACE_MS);
        boolean evL = now < evLeftUntil;
        boolean evR = now < evRightUntil;

        boolean L = evL || door || gearWarn || radar;
        boolean R = evR;

        if (L) {
            leftHoldUntil = Math.max(leftHoldUntil, now + EPISODE_GAP_MS);
            whyL = evL ? "key" : (door ? "door" : (gearWarn ? "gear" : "radar"));
        }
        if (R) {
            rightHoldUntil = Math.max(rightHoldUntil, now + EPISODE_GAP_MS);
            whyR = "vol";
        }
        popL = L || now < leftHoldUntil;
        popR = R || now < rightHoldUntil;
        if (popL && !L) whyL = "hold";
        if (popR && !R) whyR = "hold";
        if (!popL) { leftHoldUntil = 0L; whyL = ""; }
        if (!popR) { rightHoldUntil = 0L; whyR = ""; }
    }

    /* ===================== 查询 ===================== */

    public synchronized boolean left() {
        return popL;
    }

    public synchronized boolean right() {
        return popR;
    }

    /** 左侧剩余毫秒（含 episode 保持窗；0 = 无弹窗）。 */
    public synchronized long msLeft() {
        return msLeftAt(System.currentTimeMillis());
    }

    /** 右侧剩余毫秒。 */
    public synchronized long msRight() {
        return msRightAt(System.currentTimeMillis());
    }

    public synchronized long msLeftAt(long now) {
        if (!popL) return 0L;
        return Math.max(0L, Math.max(evLeftUntil, leftHoldUntil) - now);
    }

    public synchronized long msRightAt(long now) {
        if (!popR) return 0L;
        return Math.max(0L, Math.max(evRightUntil, rightHoldUntil) - now);
    }

    /** 当前状态是否开启（供车机端判断要不要推送）。 */
    public synchronized boolean active() {
        return popL || popR;
    }

    /** 一次采样：直接拼成 ext.json 里的 popup 字段值。 */
    public synchronized String json() {
        long now = System.currentTimeMillis();
        StringBuilder b = new StringBuilder(72);
        b.append("{\"L\":").append(popL ? 1 : 0);
        b.append(",\"R\":").append(popR ? 1 : 0);
        b.append(",\"why\":\"").append(popL ? whyL : (popR ? whyR : "")).append('"');
        b.append(",\"msL\":").append(msLeftAt(now));
        b.append(",\"msR\":").append(msRightAt(now)).append('}');
        return b.toString();
    }

    /** 诊断行（实车标定用：一眼看出是哪条信号在撑着状态）。 */
    public synchronized String diag() {
        long now = System.currentTimeMillis();
        return "LPM=" + (popL ? 1 : 0) + (popR ? 1 : 0)
                + " why=" + (popL ? whyL : (popR ? whyR : "-"))
                + " rad=" + (radarActive ? 1 : 0)
                + " door=" + (doorLatch ? 1 : 0)
                + " gw=" + (lastGearWarn == null ? "-" : lastGearWarn)
                + " gear=" + (lastGear == null ? "-" : lastGear)
                + " winL=" + Math.max(0L, evLeftUntil - now) / 1000L + "s"
                + " winR=" + Math.max(0L, evRightUntil - now) / 1000L + "s";
    }

    /* ===================== 内部工具 ===================== */

    /** ACC 是否开启：车速设定回读末值 ≠ 0（流内 report-on-change）。 */
    public synchronized boolean accActive() {
        return lastCspd != null && !"0".equals(lastCspd.trim());
    }

    /**
     * 档位是否为 P。默认按车机 HAL {@code VCU_GEARLEV} 实车口径 1=P；换固件请先看 diag。
     */
    protected boolean isParkGear(String gear) {
        return PARK_GEAR.equals(gear == null ? null : gear.trim());
    }

    /** 取 `= <value>` 的值段（忽略行尾时间戳；解析不出返回 null——宁可不触发）。 */
    private static String extractVal(String line) {
        int i = line.indexOf("= ");
        if (i < 0) return null;
        int j = line.indexOf(' ', i + 2);
        String v = (j < 0 ? line.substring(i + 2) : line.substring(i + 2, j)).trim();
        return v.length() == 0 ? null : v;
    }

    /** 取 `= (4) 1 0 0 0` 形式的字节数组。 */
    private static int[] extractBytes(String line) {
        try {
            int i = line.indexOf("= (");
            if (i < 0) return null;
            int j = line.indexOf(')', i + 3);
            if (j < 0) return null;
            int n = Integer.parseInt(line.substring(i + 3, j).trim());
            if (n <= 0 || n > 16) return null;
            int[] arr = new int[n];
            int k = j + 1;
            for (int idx = 0; idx < n; ++idx) {
                while (k < line.length() && line.charAt(k) == ' ') ++k;
                int e = line.indexOf(' ', k);
                String tok = (e < 0 ? line.substring(k) : line.substring(k, e)).trim();
                if (tok.length() == 0) return null;
                arr[idx] = Integer.parseInt(tok);
                k = (e < 0) ? line.length() : e + 1;
            }
            return arr;
        } catch (Throwable ignored) {
            return null;
        }
    }
}
