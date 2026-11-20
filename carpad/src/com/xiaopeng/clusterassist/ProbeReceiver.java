package com.xiaopeng.clusterassist;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * 相机探针触发口。
 *
 * <p>用广播而不是界面上加按钮：诊断一次就够，不值得为它动那块已经很挤的设置页；
 * 而且从 adb 一条命令就能触发，不用凑到车机前面点屏幕：</p>
 *
 * <pre>adb shell am broadcast -a com.xiaopeng.clusterassist.PROBE</pre>
 *
 * <p>只读行为：逐路打开相机拍一张 JPEG，不写任何系统设置、不改 AVM 视角。</p>
 */
public class ProbeReceiver extends BroadcastReceiver {

    public static final String ACTION = "com.xiaopeng.clusterassist.PROBE";

    @Override public void onReceive(final Context ctx, Intent intent) {
        if (intent == null || !ACTION.equals(intent.getAction())) return;
        // --ei id N：只探某一路。对比不同 AVM 视角时用得上——一次只改一个变量，
        // 拍一张就够，不用等 8 路全跑一遍。
        final int only = intent.getIntExtra("id", -1);
        Log.i("P7CamProbe", "收到探测指令 id=" + only);
        // 广播回调在主线程，探测每路要 1~3 秒，必须挪到后台
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    if (only >= 0) {
                        CameraProbe.runOne(ctx, String.valueOf(only));
                    } else {
                        CameraProbe.runAll(ctx);
                    }
                } catch (Throwable t) {
                    Log.w("P7CamProbe", "探测异常: " + t);
                }
            }
        }, "cam-probe").start();
    }
}
