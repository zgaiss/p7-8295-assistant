package com.xiaopeng.clusterassist;

import android.content.ComponentName;
import android.content.Context;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 歌词来源：车机音乐 App 把歌词塞进 MediaSession 的自定义元数据里。
 *
 * 参考实现（v1.1.0 LyricListener）的取法：
 *   MediaSessionManager.getActiveSessions(通知监听组件)
 *   → MediaController.getMetadata().getString("lyricInfo")   // 一个 JSON 字符串
 *   → 解析 JSON 里的 "lyricContent"                            // LRC 文本
 *   → 按播放进度挑出「当前行 / 下一行」
 *
 * 这条链只需通知使用权（我们已经有了），不用 adb、也不用碰原厂数据。
 */
public final class LyricSource {

    private static final String TAG = "P7Lyric";

    private static final Pattern P_LRC = Pattern.compile("\\[(\\d{1,2}):(\\d{1,2})(?:[.:](\\d{1,3}))?\\]");

    /** 一行歌词 */
    private static final class Line {
        final long ms;
        final String text;

        Line(long ms, String text) {
            this.ms = ms;
            this.text = text;
        }
    }

    // 解析缓存：歌词文本没变就不重复解析
    private static String cacheRaw;
    private static List<Line> cacheLines = new ArrayList<>();

    // 对外结果
    public static volatile String title = "";
    public static volatile String artist = "";
    public static volatile String cur = "";
    public static volatile String next = "";
    public static volatile double progress = -1;   // 0..1，解析不到为 -1
    public static volatile long at = 0;            // 最近一次成功取值的时间
    public static volatile boolean playing = false;
    private static boolean loggedRaw = false;
    private static long lastDiag = 0;

    private LyricSource() {
    }

    public static boolean fresh() {
        long a = at;
        return a > 0 && System.currentTimeMillis() - a < 15000 && !cur.isEmpty();
    }

    public static void clear() {
        cur = "";
        next = "";
        progress = -1;
        at = 0;
        playing = false;
    }

    /** 读一次当前播放状态与歌词 */
    public static void poll(Context ctx) {
        try {
            MediaSessionManager msm = (MediaSessionManager)
                    ctx.getSystemService(Context.MEDIA_SESSION_SERVICE);
            if (msm == null) {
                Log.w(TAG, "MediaSessionManager 拿不到");
                return;
            }
            ComponentName listener = new ComponentName(ctx, NavWatcher.class);
            List<MediaController> list = msm.getActiveSessions(listener);
            if (list == null || list.isEmpty()) {
                if (System.currentTimeMillis() - lastDiag > 10000) {
                    lastDiag = System.currentTimeMillis();
                    Log.i(TAG, "activeSessions 为空");
                }
                return;
            }

            MediaController pick = null;
            for (MediaController c : list) {
                PlaybackState st = c.getPlaybackState();
                if (st != null && st.getState() == PlaybackState.STATE_PLAYING) {
                    pick = c;
                    break;
                }
            }
            if (pick == null) {
                for (MediaController c : list) {
                    if (c.getPlaybackState() != null) {
                        pick = c;
                        break;
                    }
                }
            }
            if (pick == null) {
                Log.i(TAG, "没有可用的 MediaController");
                return;
            }
            if (System.currentTimeMillis() - lastDiag > 10000) {
                lastDiag = System.currentTimeMillis();
                Log.i(TAG, "sessions=" + list.size() + " pkg=" + pick.getPackageName()
                        + " metaSize=" + (pick.getMetadata() == null ? -1 : pick.getMetadata().size()));
            }

            PlaybackState st = pick.getPlaybackState();
            MediaMetadata md = pick.getMetadata();
            if (md == null) return;

            String t = md.getString(MediaMetadata.METADATA_KEY_TITLE);
            String a = md.getString(MediaMetadata.METADATA_KEY_ARTIST);
            if (t != null) title = t;
            if (a != null) artist = a;

            playing = st != null && st.getState() == PlaybackState.STATE_PLAYING;
            long dur = md.getLong(MediaMetadata.METADATA_KEY_DURATION);

            // 播放位置：PlaybackState 里是"上次更新时的位置"，要按速度外推
            long posMs = 0;
            if (st != null) {
                posMs = st.getPosition();
                if (playing && st.getLastPositionUpdateTime() > 0) {
                    long delta = SystemClock.elapsedRealtime() - st.getLastPositionUpdateTime();
                    posMs += (long) (delta * st.getPlaybackSpeed());
                }
            }
            if (posMs < 0) posMs = 0;
            if (dur > 0) {
                progress = Math.min(1.0, (double) posMs / dur);
            }

            String lrc = extractLrc(md);
            if (lrc == null || lrc.isEmpty()) {
                at = System.currentTimeMillis();
                return;
            }
            List<Line> lines = parse(lrc);
            if (lines.isEmpty()) {
                at = System.currentTimeMillis();
                return;
            }
            pickLine(lines, posMs);
            at = System.currentTimeMillis();
        } catch (Throwable e) {
            Log.w(TAG, "取歌词失败: " + e);
        }
    }

    /** 从 metadata 里掏出歌词文本：lyricInfo 是 JSON，里面有 lyricContent */
    private static String extractLrc(MediaMetadata md) {
        String info = null;
        try {
            info = md.getString("lyricInfo");
        } catch (Throwable ignored) {
        }
        if (!loggedRaw) {
            loggedRaw = true;
            Log.i(TAG, "lyricInfo 长度=" + (info == null ? -1 : info.length())
                    + " 前120字符=" + (info == null ? "null" : info.substring(0, Math.min(120, info.length()))));
        }
        if (info != null && !info.isEmpty()) {
            try {
                JSONObject o = new JSONObject(info);
                String c = o.optString("lyricContent", "");
                if (!c.isEmpty()) return c;
            } catch (Throwable ignored) {
            }
            // 有些版本直接把 LRC 放在 lyricInfo 里
            if (info.indexOf('[') >= 0) return info;
        }
        // 兜底：也可能单独放在 lyricContent 键里
        try {
            String c = md.getString("lyricContent");
            if (c != null && !c.isEmpty()) return c;
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 解析 LRC：[mm:ss.xx]歌词，一行可能带多个时间戳 */
    private static List<Line> parse(String raw) {
        if (raw.equals(cacheRaw)) return cacheLines;
        List<Line> out = new ArrayList<>();
        for (String rawLine : raw.split("\\r?\\n")) {
            String s = rawLine.trim();
            if (s.isEmpty()) continue;
            Matcher m = P_LRC.matcher(s);
            List<Long> stamps = new ArrayList<>();
            int lastEnd = 0;
            while (m.find()) {
                int mm = Integer.parseInt(m.group(1));
                int ss = Integer.parseInt(m.group(2));
                String frac = m.group(3);
                int msPart = 0;
                if (frac != null) {
                    if (frac.length() == 1) msPart = Integer.parseInt(frac) * 100;
                    else if (frac.length() == 2) msPart = Integer.parseInt(frac) * 10;
                    else msPart = Integer.parseInt(frac);
                }
                stamps.add((mm * 60L + ss) * 1000 + msPart);
                lastEnd = m.end();
            }
            String text = s.substring(lastEnd).trim();
            if (stamps.isEmpty() || text.isEmpty()) continue;   // 跳过 [ti:] 之类
            for (Long ms : stamps) out.add(new Line(ms, text));
        }
        // 不用 lambda：编译目标是无 invoke 包的 android.jar，lambda 会找不到 LambdaMetafactory
        java.util.Collections.sort(out, new java.util.Comparator<Line>() {
            @Override public int compare(Line x, Line y) {
                return Long.compare(x.ms, y.ms);
            }
        });
        cacheRaw = raw;
        cacheLines = out;
        return out;
    }

    /** 按播放位置选当前行与下一行 */
    private static void pickLine(List<Line> lines, long posMs) {
        int idx = -1;
        // 提前 800ms 切换到下一行，补偿"车机推送→仪表读取→渲染"的链路延迟，
        // 让歌词行切换跟音乐实际唱到该句基本同步（纯靠位置判断会整体晚半拍）。
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).ms <= posMs + 800) idx = i;
            else break;
        }
        // 开头前奏阶段：显示第一行
        if (idx < 0) {
            cur = lines.get(0).text;
            next = lines.size() > 1 ? lines.get(1).text : "";
            return;
        }
        cur = lines.get(idx).text;
        next = idx + 1 < lines.size() ? lines.get(idx + 1).text : "";
    }
}
