package com.xiaopeng.clusterassist;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 开机自启数据桥（仪表端守护进程由仪表自己的 rc.local 拉起）。 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        try {
            Prefs p = Prefs.load(context);
            if (p.bridge && p.master) PushService.start(context);
        } catch (Throwable ignored) {
        }
    }
}
