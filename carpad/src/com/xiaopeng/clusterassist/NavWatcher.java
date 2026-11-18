package com.xiaopeng.clusterassist;

import android.app.Notification;
import android.content.ComponentName;
import android.content.Context;
import android.os.Bundle;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 导航数据来源：监听通知栏（高德/百度/系统导航都会把"剩余 x 公里 / y 分钟"放进通知）。
 * 需要用户在系统设置里授予"通知使用权"；没授权就只是不显示导航卡片，不影响其他功能。
 */
public class NavWatcher extends NotificationListenerService {

    public static class Nav {
        public volatile double distM = -1;
        public volatile double timeS = -1;
        public volatile String arrive = "";
        public volatile long at = 0;
    }

    public static final Nav latest = new Nav();

    private static final Pattern P_DIST = Pattern.compile("([0-9]+(?:\\.[0-9])?)\\s*公里|([0-9]{1,4})\\s*米");
    private static final Pattern P_TIME = Pattern.compile("(?:(\\d{1,2})\\s*小时)?\\s*(\\d{1,3})\\s*分钟");
    private static final Pattern P_ARRIVE = Pattern.compile("(\\d{1,2}):(\\d{2})\\s*(?:到|到达)");

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null || sbn.getNotification() == null) return;
        Bundle ex = sbn.getNotification().extras;
        if (ex == null) return;
        StringBuilder sb = new StringBuilder();
        append(sb, ex.getCharSequence(Notification.EXTRA_TITLE));
        append(sb, ex.getCharSequence(Notification.EXTRA_TEXT));
        append(sb, ex.getCharSequence(Notification.EXTRA_BIG_TEXT));
        append(sb, ex.getCharSequence(Notification.EXTRA_SUB_TEXT));
        append(sb, ex.getCharSequence(Notification.EXTRA_INFO_TEXT));
        String text = sb.toString();
        if (text.isEmpty()) return;
        parse(text);
    }

    private static void append(StringBuilder sb, CharSequence cs) {
        if (!TextUtils.isEmpty(cs)) sb.append(cs).append(' ');
    }

    /** 从任意文本里抠距离/时间/到达时刻 */
    public static void parse(String text) {
        boolean hit = false;
        double distM = -1, timeS = -1;
        String arrive = "";
        Matcher md = P_DIST.matcher(text);
        if (md.find()) {
            if (md.group(1) != null) {
                distM = Double.parseDouble(md.group(1)) * 1000;
            } else if (md.group(2) != null) {
                distM = Double.parseDouble(md.group(2));
            }
            hit = true;
        }
        Matcher mt = P_TIME.matcher(text);
        if (mt.find()) {
            double h = mt.group(1) != null ? Double.parseDouble(mt.group(1)) : 0;
            double m = mt.group(2) != null ? Double.parseDouble(mt.group(2)) : 0;
            timeS = h * 3600 + m * 60;
            hit = true;
        }
        Matcher ma = P_ARRIVE.matcher(text);
        if (ma.find()) {
            arrive = ma.group(1) + ":" + ma.group(2);
            hit = true;
        }
        if (!hit) return;
        if (distM >= 0 && distM < 2000000) latest.distM = distM;
        if (timeS >= 0 && timeS < 86400) latest.timeS = timeS;
        if (!arrive.isEmpty()) latest.arrive = arrive;
        latest.at = System.currentTimeMillis();
    }

    public static boolean hasFresh() {
        return latest.at > 0 && System.currentTimeMillis() - latest.at < 10 * 60 * 1000
                && latest.distM >= 0 && latest.timeS >= 0;
    }

    public static boolean enabled(Context c) {
        try {
            String flat = Settings.Secure.getString(c.getContentResolver(), "enabled_notification_listeners");
            if (flat == null) return false;
            ComponentName me = new ComponentName(c, NavWatcher.class);
            return flat.contains(me.getPackageName());
        } catch (Throwable t) {
            return false;
        }
    }
}
