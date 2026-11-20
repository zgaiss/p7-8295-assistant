package com.xiaopeng.clusterassist;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.hardware.camera2.CameraManager;
import android.util.Log;

/**
 * AVM 视角调试口。
 *
 * <p>背景：车机参数对象里有 display-mode / channel0-display-mode / channel1-display-mode /
 * work-status 四个键，但哪个才是「相机输出哪几路画面」的真正开关，只能一个个试。
 * 每试一个值就重新打包安装太慢，这里开一条广播口，adb 一条命令就能改一个变量并读回：</p>
 *
 * <pre>
 * adb shell am broadcast -a com.xiaopeng.clusterassist.AVM --ei mode 8
 * adb shell am broadcast -a com.xiaopeng.clusterassist.AVM --ei ch0 8 --ei ch1 8
 * adb shell am broadcast -a com.xiaopeng.clusterassist.AVM --ei ws 1
 * adb shell am broadcast -a com.xiaopeng.clusterassist.AVM --ez dump true
 * </pre>
 *
 * <p>写的是车机的 AVM 参数，属于会影响原厂环视视角的操作；调试完把视角还原
 * （重新打一次灯、松掉即可，PushService 会写回它记住的原值）。</p>
 */
public class AvmReceiver extends BroadcastReceiver {

    public static final String ACTION = "com.xiaopeng.clusterassist.AVM";

    @Override public void onReceive(Context ctx, Intent it) {
        if (it == null || !ACTION.equals(it.getAction())) return;
        CameraManager cm = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
        if (cm == null) {
            Log.w("P7Avm", "无 CameraManager");
            return;
        }
        if (it.getBooleanExtra("dump", false)) {
            Log.i("P7Avm", "参数: " + AvmMode.dump(cm));
        }
        // --ez methods true：列出 CameraManager 全部指令类方法，确认 early 版叫什么
        if (it.getBooleanExtra("methods", false)) {
            Log.i("P7Avm", AvmMode.dumpMethods(cm));
        }
        // --ei emode 8：相机打开前预置视角（打灯流程用的就是这条）
        int emode = it.getIntExtra("emode", Integer.MIN_VALUE);
        if (emode != Integer.MIN_VALUE) {
            boolean ok = AvmMode.earlySet(cm, emode);
            Log.i("P7Avm", "earlySet(" + emode + ") -> " + ok + " err=" + AvmMode.lastError());
        }
        int mode = it.getIntExtra("mode", Integer.MIN_VALUE);
        if (mode != Integer.MIN_VALUE) {
            AvmMode.set(cm, mode);
        }
        int ch0 = it.getIntExtra("ch0", Integer.MIN_VALUE);
        if (ch0 != Integer.MIN_VALUE) {
            AvmMode.setChannel(cm, 0, ch0);
        }
        int ch1 = it.getIntExtra("ch1", Integer.MIN_VALUE);
        if (ch1 != Integer.MIN_VALUE) {
            AvmMode.setChannel(cm, 1, ch1);
        }
        int ws = it.getIntExtra("ws", Integer.MIN_VALUE);
        if (ws != Integer.MIN_VALUE) {
            AvmMode.setWorkState(cm, ws);
        }
        // 四宫格取景微调：--ei side 2 --ef zoom 2.0 --ef panx 0.8 --ef pany 0
        // side: 0=左右都设(默认)，1=只左灯，2=只右灯。两侧鱼眼镜像，车尾偏向相反，
        //       所以要分开调（左灯通常 panx 取右灯的相反数）。
        // zoom>1 放大（舍弃边缘），panx/pany∈[-1,1] 把窗口往该方向挪（车尾方向）。
        // 约定每条命令带全三参；--ef zoom 0 复位成整格。
        if (it.hasExtra("zoom") || it.hasExtra("panx") || it.hasExtra("pany")) {
            int side = it.getIntExtra("side", 0);
            float z = it.getFloatExtra("zoom", Float.NaN);
            float px = it.getFloatExtra("panx", Float.NaN);
            float py = it.getFloatExtra("pany", Float.NaN);
            if (Float.isNaN(z) && Float.isNaN(px) && Float.isNaN(py)) {
                MirrorSource.setQuadCrop(side, 1.0f, 0f, 0f);   // 复位整格
            } else {
                MirrorSource.setQuadCrop(side,
                        Float.isNaN(z) ? 1.0f : (z <= 0 ? 1.0f : z),
                        Float.isNaN(px) ? 0f : px,
                        Float.isNaN(py) ? 0f : py);
            }
            Log.i("P7Avm", "取景: " + MirrorSource.quadCropInfo());
        }
        Log.i("P7Avm", "读回: " + AvmMode.readBack(cm) + " err=" + AvmMode.lastError());
    }
}
