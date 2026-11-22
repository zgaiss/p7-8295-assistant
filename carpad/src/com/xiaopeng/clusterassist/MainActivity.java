package com.xiaopeng.clusterassist;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.res.Configuration;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.SeekBar;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 车机端 App —— 跑在车机屏上，负责「连接仪表 → 上传程序 → 投放/停止 → 调显示项」。
 * 仪表端是另一块屏，跑 /data/p7assist/p7assist，自己读 DBus 自己画。
 *
 * 版式对齐原「P7仪表助手」v1.1.0：左侧 176dp 导航 + 右侧面板，
 * 页面顺序也按原版：连接 → 显示内容 → 调试模式 → 运行日志。
 */
public class MainActivity extends Activity {

    private Prefs prefs;
    private Deploy deploy;
    private Ui ui;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService pool = Executors.newSingleThreadExecutor();

    private final List<TextView> chips = new ArrayList<>();
    private TextView navConn, navDisp, navLyric, navChg, navDebug, navLog, tvChgAnim, tvChgRaw, tvCastDbg, tvStatus, tvLog, tvDeploy, tvConnState, tvAdbAuth;
    private ScrollView pageConn, pageDisp, pageLyric, pageDebug, pageChg;
    private LinearLayout pageLog;
    private LyricPositionMap lyricMap;
    private TextView tvLyricPos, tvLyricPosState, tvLyricScale, tvTlDiag;
    /** 「恢复默认位置」按钮：锁定歌词位置时置灰，避免绕过锁定 */
    private TextView btnLyricReset;
    private TextView btnStart, btnStop;

    private PillSwitch swMaster, swTpmsP, swTpmsD, swBsd, swTpmsHi, swCabin, swVolt, swNav, swLyric,
            swLyricBottom, swTripDist, swTripTime, swTripSpeed, swTripKwh, swDebug, swBridge, swTl,
            swTlDemo, swMirror;
    private TextView tvZoomSpeed, tvZoomMs, tvScale, tvDx, tvDy, btnDayNight;
    private TextView tvMirrorCamL, tvMirrorCamR;
    // 取景微调步进器数值回显（左右各 zoom/panx/pany）
    private TextView tvQZoomL, tvQPanXL, tvQPanYL, tvQZoomR, tvQPanXR, tvQPanYR;
    private EditText etHost, etPort, etUser, etPass;

    /** 打开「歌词显示在下方」之前，底部中央区域四个开关的状态（bit0~3），-1 = 没有备份 */
    private int tripBackup = -1;

    private boolean syncing;

    /** 对齐原版：强制 density=192dpi（density 1.2）。
     *  车机中控屏 2400×1200 物理像素 -> 逻辑 2000×1000 dp，
     *  让布局物理尺寸与原版 App 完全一致，不受系统 dpi 配置影响。 */
    @Override
    protected void attachBaseContext(Context context) {
        try {
            Configuration configuration = new Configuration(context.getResources().getConfiguration());
            configuration.densityDpi = 192;
            super.attachBaseContext(context.createConfigurationContext(configuration));
        } catch (Throwable t) {
            // 兜底：个别车机系统（Android 12 大屏）对 density 强制或 createConfigurationContext
            // 有限制时，回退到原始 context，保证 Activity 一定能起来，而不是「点图标没反应」。
            super.attachBaseContext(context);
        }
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = Prefs.load(this);
        deploy = new Deploy(this, prefs);
        ui = new Ui(this);
        buildUi();
        selectPage(0);
        if (prefs.bridge && prefs.master) PushService.start(this);
        // 打灯影像要用相机：targetSdk 28 必须运行时授权，否则采集端只会在日志里报缺权限
        if (prefs.mirror && checkSelfPermission(android.Manifest.permission.CAMERA)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            try {
                requestPermissions(new String[]{android.Manifest.permission.CAMERA}, 1001);
            } catch (Throwable ignored) { }
        }
        main.postDelayed(new Runnable() {
            @Override public void run() { testConnection(false); }
        }, 350);
        // 授权状态轮播：等待「允许 USB 调试」确认期间页面要实时反映握手进度
        main.postDelayed(new Runnable() {
            @Override public void run() {
                refreshAdbAuth();
                main.postDelayed(this, 1500);
            }
        }, 1500);
        // 昼夜自动跟随：这台车机切昼夜不一定发标准 UiMode 配置变更，
        // 每 30s 用与仪表同一套逻辑判一次；变了就重建界面换配色。
        main.postDelayed(new Runnable() {
            @Override public void run() {
                try {
                    if (NightMode.isNight(MainActivity.this) != ui.night) {
                        android.util.Log.i("P7Night",
                                "昼夜变化，重建界面 " + (ui.night ? "夜间→日间" : "日间→夜间"));
                        recreate();
                        return; // 旧 Activity 不再排下一次检查；新的 onCreate 会排
                    }
                } catch (Throwable ignored) { }
                main.postDelayed(this, 30000);
            }
        }, 30000);
    }

    /** 刷新「本地ADB授权」卡片：文字 + 按状态着色（绿=已授权，橙=等待确认，红=未授权） */
    private void refreshAdbAuth() {
        if (tvAdbAuth == null) return;
        String s = LocalAdb.authStatus();
        tvAdbAuth.setText(s);
        int color = ui.textSub;
        if (s.startsWith("已授权")) color = ui.tagGreen;
        else if (s.contains("等待车机确认")) color = ui.tagWarn;
        else if (s.startsWith("未授权")) color = ui.tagRed;
        tvAdbAuth.setTextColor(color);
    }

    // ---------------- 骨架 ----------------

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setBackgroundColor(ui.bgRoot);

        LinearLayout side = new LinearLayout(this);
        side.setOrientation(LinearLayout.VERTICAL);
        side.setPadding(ui.dp(12), ui.dp(16), ui.dp(12), ui.dp(12));

        TextView title = ui.t("P7 仪表助手", 21, ui.textTitle);
        title.setTypeface(ui.bold);
        title.setPadding(ui.dp(12), 0, 0, 0);
        side.addView(title);
        TextView sub = ui.t("车机端 · 控制仪表", 11, ui.textVer);
        sub.setPadding(ui.dp(12), ui.dp(4), 0, 0);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.bottomMargin = ui.dp(12);
        side.addView(sub, slp);

        navConn = navItem("连接仪表", 0);
        navDisp = navItem("显示内容", 1);
        navLyric = navItem("歌词位置", 4);
        navChg = navItem("充电模式", 5);
        navDebug = navItem("调试模式", 2);
        navLog = navItem("运行日志", 3);
        side.addView(navConn);
        side.addView(navDisp);
        side.addView(navLyric);
        side.addView(navChg);
        side.addView(navDebug);
        side.addView(navLog);

        LinearLayout master = new LinearLayout(this);
        master.setOrientation(LinearLayout.HORIZONTAL);
        master.setGravity(Gravity.CENTER_VERTICAL);
        master.setPadding(ui.dp(12), ui.dp(12), 0, 0);
        master.addView(ui.t("投放开关", 13, ui.textSub),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        swMaster = new PillSwitch(this);
        swMaster.setCheckedSilently(prefs.master);
        swMaster.setOnChange(new PillSwitch.OnChange() {
            @Override public void onChanged(PillSwitch sw, boolean checked) {
                prefs.master = checked;
                prefs.save(MainActivity.this);
                syncProjectButtons();
                toast(checked ? "已开始投放" : "已停止投放");
                if (checked && prefs.bridge) PushService.start(MainActivity.this);
                schedulePush(120, true);
            }
        });
        master.addView(swMaster, new LinearLayout.LayoutParams(ui.dp(48), ui.dp(22)));
        side.addView(master);

        View spacer = new View(this);
        side.addView(spacer, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        // 左下角版本号从 manifest 读，永远跟随 versionName——之前写死 v2.0.0 从不更新。
        String appVer;
        try {
            appVer = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Throwable t) {
            appVer = "?";
        }
        TextView ver = ui.t("v" + appVer, 10, ui.textVer);
        ver.setGravity(Gravity.CENTER);
        side.addView(ver, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(side, new LinearLayout.LayoutParams(ui.dp(176), ViewGroup.LayoutParams.MATCH_PARENT));

        // 右侧整列：顶部常驻声明条 + 下面的页面容器
        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);

        // 显眼声明：所有页面都看得到（放在滚动态页面之外，不随内容滚走）
        String noticeText = "本软件免费开源，仅供学习研究，禁止倒卖！";
        android.text.SpannableString noticeSs = new android.text.SpannableString(noticeText);
        // 后半句「禁止倒卖！」用红色强调，一眼能看到重点
        int emphAt = noticeText.indexOf("禁止倒卖");
        if (emphAt >= 0) {
            noticeSs.setSpan(new android.text.style.ForegroundColorSpan(ui.tagRed),
                    emphAt, noticeText.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            noticeSs.setSpan(new android.text.style.StyleSpan(Typeface.BOLD),
                    emphAt, noticeText.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        TextView notice = ui.t(noticeText, 15, ui.tagWarn);
        notice.setText(noticeSs);
        notice.setGravity(Gravity.CENTER);
        notice.setTypeface(ui.bold);
        notice.setPadding(ui.dp(14), ui.dp(10), ui.dp(14), ui.dp(10));
        notice.setBackground(ui.stroke(ui.night ? 0x4DE4AC6F : 0x2EB0752A, ui.tagWarn, 12, 1));
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nlp.setMargins(ui.dp(20), ui.dp(12), ui.dp(20), 0);
        right.addView(notice, nlp);

        FrameLayout content = new FrameLayout(this);
        content.setBackgroundColor(ui.bgMain);
        pageConn = scrollPage(buildConnectPage());
        pageDisp = scrollPage(buildDisplayPage());
        pageLyric = scrollPage(buildLyricPosPage());
        pageDebug = scrollPage(buildDebugPage());
        pageChg = scrollPage(buildChargePage());
        pageLog = buildLogPage();
        content.addView(pageConn);
        content.addView(pageDisp);
        content.addView(pageLyric);
        content.addView(pageChg);
        content.addView(pageDebug);
        content.addView(pageLog);
        right.addView(content, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(right, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        setContentView(root);
        if (ui.xpFont != null) applyXpFont(root, ui.xpFont);   // 全局套用普惠体
        syncProjectButtons();
    }

    /** 对齐原版：把整棵视图树的字体统一成普惠体（保留各自的粗体/等宽样式） */
    private void applyXpFont(View view, Typeface typeface) {
        if (view instanceof TextView) {
            TextView tv = (TextView) view;
            if (tv.getTypeface() == Typeface.MONOSPACE) return;   // 日志保留等宽
            tv.setTypeface(typeface, tv.getTypeface() != null ? tv.getTypeface().getStyle() : 0);
        }
        if (view instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) view;
            for (int i = 0; i < vg.getChildCount(); i++) {
                applyXpFont(vg.getChildAt(i), typeface);
            }
        }
    }

    private TextView navItem(String label, final int idx) {
        TextView v = ui.t(label, 16, ui.textNav);
        v.setPadding(ui.dp(12), ui.dp(11), ui.dp(12), ui.dp(11));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = ui.dp(4);
        v.setLayoutParams(lp);
        v.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View x) { selectPage(idx); }
        });
        return v;
    }

    private ScrollView scrollPage(LinearLayout inner) {
        ScrollView sv = new ScrollView(this);
        sv.setPadding(ui.dp(26), ui.dp(19), ui.dp(26), ui.dp(16));
        sv.setVerticalScrollBarEnabled(false);
        sv.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        sv.addView(inner);
        return sv;
    }

    private LinearLayout pageRoot() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return l;
    }

    private TextView pageChip() {
        TextView c = ui.chip("未连接");
        chips.add(c);
        return c;
    }

    private void selectPage(int i) {
        // 按索引直取：0连接 1显示 2调试 3日志 4歌词位置 5充电模式
        View[] pages = {pageConn, pageDisp, pageDebug, pageLog, pageLyric, pageChg};
        for (int k = 0; k < pages.length; k++) {
            pages[k].setVisibility(k == i ? View.VISIBLE : View.GONE);
        }
        TextView[] navs = {navConn, navDisp, navDebug, navLog, navLyric, navChg};
        for (int k = 0; k < navs.length; k++) {
            boolean on = k == i;
            navs[k].setTextColor(on ? ui.textTitle : ui.textNav);
            navs[k].setTypeface(on ? ui.bold : (ui.xpFont != null ? ui.xpFont : Typeface.DEFAULT));
            navs[k].setBackground(on ? ui.round(ui.bgNavSel, 12) : null);
        }
        if (i == 2) refreshStatus(false);
        if (i == 3) refreshLog();
        if (i == 4) refreshLyricPosState();
        if (i == 5) refreshChargePageState();
    }

    // ---------------- 页 1：连接仪表 ----------------

    private LinearLayout buildConnectPage() {
        LinearLayout p = pageRoot();
        p.addView(ui.pageHeader("连接仪表", pageChip()));

        p.addView(ui.group("仪表地址（FTP，根目录 /data）"));
        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.setGravity(Gravity.CENTER_VERTICAL);
        etHost = ui.input(prefs.host, InputType.TYPE_CLASS_TEXT, 0);
        row1.addView(etHost, new LinearLayout.LayoutParams(0, ui.dp(44), 1f));
        TextView colon = ui.t(":", 22, ui.textGrp);
        colon.setPadding(ui.dp(8), 0, ui.dp(8), 0);
        row1.addView(colon);
        etPort = ui.input(String.valueOf(prefs.port), InputType.TYPE_CLASS_NUMBER, 96);
        row1.addView(etPort);
        p.addView(row1);

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams r2lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        r2lp.topMargin = ui.dp(10);
        etUser = ui.input(prefs.user, InputType.TYPE_CLASS_TEXT, 0);
        LinearLayout.LayoutParams ulp = new LinearLayout.LayoutParams(0, ui.dp(44), 1f);
        ulp.rightMargin = ui.dp(10);
        row2.addView(etUser, ulp);
        etPass = ui.input(prefs.pass, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD, 0);
        row2.addView(etPass, new LinearLayout.LayoutParams(0, ui.dp(44), 1f));
        p.addView(row2, r2lp);
        tvConnState = ui.hint("默认 172.20.1.40:2221、用户 root、空密码；FTP 账号就是仪表 /data 的写入口");
        p.addView(tvConnState);

        p.addView(ui.buttonRow(
                ui.button("连接仪表", false, new View.OnClickListener() {
                    @Override public void onClick(View v) { testConnection(true); }
                }),
                ui.button("一键部署", true, new View.OnClickListener() {
                    @Override public void onClick(View v) { deployAll(); }
                })));

        p.addView(ui.sep());
        p.addView(ui.group("本地ADB授权（车机本地，导航/电压/盲区都靠它）"));
        tvAdbAuth = ui.t(LocalAdb.authStatus(), 13, ui.textSub);
        tvAdbAuth.setBackground(ui.round(ui.logBg, 10));
        tvAdbAuth.setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12));
        p.addView(tvAdbAuth);
        p.addView(ui.hint("密钥由车机本地生成并保存（APK 不内置任何密钥）。首次连接时车机屏幕会弹「允许 USB 调试」，点一次允许即长期有效"));
        p.addView(ui.buttonRow(
                ui.button("连接并查询授权", false, new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        toast("正在连接本地 adb…（车机若弹出「允许 USB 调试」，请点允许）");
                        // 必须先清退避：授权失败后 ensure() 会退避 20~30 秒，期间点这个按钮
                        // 等于什么都没做，用户看到的就是"点了拉不起来"。
                        LocalAdb.clearBackoff();
                        new Thread(new Runnable() {
                            @Override public void run() {
                                try {
                                    // 走一遍真实连接：未授权时 adbd 会在车机屏幕弹「允许 USB 调试」
                                    String out = LocalAdb.shell(MainActivity.this, "echo p7-ok", 25000);
                                    final boolean ok = out != null && out.contains("p7-ok");
                                    runOnUiThread(new Runnable() {
                                        @Override public void run() {
                                            refreshAdbAuth();
                                            toast(ok ? "本地 adb 已连接（密钥已授权）"
                                                    : "连接未完成：" + LocalAdb.state());
                                        }
                                    });
                                } catch (Throwable t) {
                                    runOnUiThread(new Runnable() {
                                        @Override public void run() {
                                            refreshAdbAuth();
                                            toast("连接未完成：" + LocalAdb.state());
                                        }
                                    });
                                }
                            }
                        }).start();
                    }
                }),
                ui.button("重新授权", false, new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        new AlertDialog.Builder(MainActivity.this)
                                .setTitle("重新授权")
                                .setMessage("将清除本地密钥并重新生成，连接时车机屏幕会重新弹「允许 USB 调试」。继续吗？")
                                .setPositiveButton("继续", new DialogInterface.OnClickListener() {
                                    @Override public void onClick(DialogInterface d, int w) {
                                        LocalAdb.resetKey(MainActivity.this);
                                        refreshAdbAuth();
                                        toast("密钥已清除，点「连接并查询授权」重新走授权");
                                    }
                                })
                                .setNegativeButton("取消", null)
                                .show();
                    }
                })));
        refreshAdbAuth();

        p.addView(ui.sep());
        p.addView(ui.group("程序部署（仪表 /data/p7assist）"));
        tvDeploy = ui.t("尚未检查", 13, ui.textSub);
        tvDeploy.setBackground(ui.round(ui.logBg, 10));
        tvDeploy.setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12));
        tvDeploy.setLineSpacing(ui.dp(4), 1f);
        p.addView(tvDeploy);
        p.addView(ui.buttonRow(
                ui.button("校验部署", false, new View.OnClickListener() {
                    @Override public void onClick(View v) { verifyDeploy(); }
                }),
                ui.button("读取状态", false, new View.OnClickListener() {
                    @Override public void onClick(View v) { refreshStatus(true); }
                })));
        // 卸载还原：不可逆操作，单独一行 + 二次确认，避免误触
        p.addView(ui.buttonRow(
                ui.button("卸载并还原仪表", false, new View.OnClickListener() {
                    @Override public void onClick(View v) { confirmUninstall(); }
                })));

        p.addView(ui.sep());
        p.addView(ui.group("数据桥（车机 → 仪表）"));
        swBridge = addToggle(p, "启用数据桥", "", "每 5 秒推送电压、导航余量、日夜到 ext.json", prefs.bridge,
                new Setter() { @Override public void set(boolean v) { prefs.bridge = v; } });
        p.addView(ui.hint("导航/歌词需要通知使用权。本车机没有可跳转的设置页，这里用本地 adb 自动授权"));
        p.addView(ui.buttonRow(
                ui.button("授权通知使用权", false, new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        toast("正在通过本地 adb 授权…");
                        new Thread(new Runnable() {
                            @Override public void run() {
                                final boolean ok = LocalAdb.grantNotificationListener(MainActivity.this);
                                runOnUiThread(new Runnable() {
                                    @Override public void run() {
                                        if (ok) {
                                            toast("已授权，歌词/导航立即生效");
                                        } else {
                                            // 本地 adb 还没授权时再退回系统设置页（多数车机没有这个页面）
                                            try {
                                                startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
                                                toast("本地 adb 不可用，已尝试打开系统设置");
                                            } catch (Throwable t) {
                                                toast("授权失败：请先在设置里点「连接仪表」并允许 USB 调试");
                                            }
                                        }
                                    }
                                });
                            }
                        }).start();
                    }
                }),
                ui.button("重启数据桥", false, new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        PushService.stop(MainActivity.this);
                        PushService.start(MainActivity.this);
                        toast("数据桥已重启");
                    }
                })));

        p.addView(ui.sep());
        p.addView(ui.group("首次安装（只需一次）"));
        p.addView(ui.note("1. 先点「连接仪表」确认状态里的管理通道是否可用\n"
                + "2. 点「一键部署」，App 上传并自动启动（30510 管理通道）\n"
                + "3. 若提示还差一步，那条命令必须粘到【仪表】的 shell，"
                + "不是车机 adb shell（提示符 shell@au8295_xp 的是车机）\n"
                + "4. 之后开关、升级都在 App 里点，不用再碰 shell"));
        p.addView(ui.buttonRow(
                ui.button("复制安装命令", false, new View.OnClickListener() {
                    @Override public void onClick(View v) { copyCommands(); }
                }),
                ui.button("复制卸载命令", false, new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        copyToClipboard("cd /data/p7assist\nsh uninstall-autostart.sh\n./p7assistctl stop\nrm -rf /data/p7assist");
                        toast("卸载命令已复制");
                    }
                })));
        return p;
    }

    // ---------------- 页 2：显示内容（投放控制 + 分区开关，对齐原版 v1.1.0） ----------------

    private LinearLayout buildDisplayPage() {
        LinearLayout p = pageRoot();
        p.addView(ui.pageHeader("显示内容", pageChip()));

        p.addView(ui.group("投放控制（作用在仪表那块屏）"));
        btnStart = ui.button("开始投放", true, new View.OnClickListener() {
            @Override public void onClick(View v) { setProject(true); }
        });
        btnStop = ui.button("停止投放", false, new View.OnClickListener() {
            @Override public void onClick(View v) { setProject(false); }
        });
        p.addView(ui.buttonRow(btnStart, btnStop));
        p.addView(ui.hint("开始 = 仪表显示并叠加前景层；停止 = 清屏 + 关层，程序仍在，可秒开"));

        p.addView(ui.group("仪表端布局（另一块屏，1920 × 720）"));
        InstrumentMap map = new InstrumentMap(this);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(ui.dp(480), ui.dp(180));
        mlp.topMargin = ui.dp(4);
        mlp.gravity = Gravity.CENTER_HORIZONTAL;
        p.addView(map, mlp);
        p.addView(ui.hint("虚线框对应下面几组开关；车机屏只负责设置，画面在仪表上"));

        // 左下角区域：电压 / 车内温度 二选一
        p.addView(ui.group("左下角区域（行车模式右侧）"));
        LinearLayout r4 = toggle("蓄电池电压", "二选一", "显示小电瓶电压", prefs.volt,
                new Setter() { @Override public void set(boolean v) { prefs.volt = v; } });
        swVolt = (PillSwitch) r4.getTag();
        LinearLayout r5 = toggle("车内温度", "二选一", "显示车内温度", prefs.cabin,
                new Setter() { @Override public void set(boolean v) { prefs.cabin = v; } });
        swCabin = (PillSwitch) r5.getTag();
        p.addView(ui.pair(r4, r5));

        // 中央 SR 车模区域：胎压/胎温（P 档 / D 档）、盲区提醒
        p.addView(ui.group("中央SR车模区域"));
        LinearLayout r1 = toggle("胎压 / 胎温（P 档）", "", "停车界面车模四角分数显示", prefs.tpmsP,
                new Setter() { @Override public void set(boolean v) { prefs.tpmsP = v; } });
        swTpmsP = (PillSwitch) r1.getTag();
        LinearLayout r2 = toggle("胎压 / 胎温（D 档）", "", "行车界面车模四角分数显示", prefs.tpmsD,
                new Setter() { @Override public void set(boolean v) { prefs.tpmsD = v; } });
        swTpmsD = (PillSwitch) r2.getTag();
        p.addView(ui.pair(r1, r2));

        LinearLayout r3 = toggle("盲区提醒", "", "车辆盲区有车时，车模侧后方出现红色 C 型弧线", prefs.bsd,
                new Setter() { @Override public void set(boolean v) { prefs.bsd = v; } });
        swBsd = (PillSwitch) r3.getTag();

        // 胎压数据源：默认只读仪表总线，保持"纯仪表本地取数"的初衷；
        // 想要 0.01 bar 精度才打开，走车机 CarProperty（需要本地 adb 通道）。
        LinearLayout r6 = toggle("高精度胎压", "0.01 bar",
                "关：仪表总线，0.1 bar 精度（如 2.4，默认，不依赖 adb）\n"
                        + "开：车机 CarProperty，0.01 bar 精度（如 2.42，需要 adb 通道）",
                prefs.tpmsHi,
                new Setter() { @Override public void set(boolean v) { prefs.tpmsHi = v; } });
        swTpmsHi = (PillSwitch) r6.getTag();
        p.addView(ui.pair(r3, r6));

        // 打灯影像：把该侧相机画面推到仪表半屏（960×540）。默认关——它会盖住半屏。
        p.addView(ui.group("打灯影像（推到仪表半屏）"));
        LinearLayout r7 = toggle("打灯影像", "960×540",
                "打左/右转向灯时，把该侧相机画面推到仪表左/右半屏；松灯约 1 秒后自动撤区。\n"
                        + "倒挡、双闪不触发。需要仪表端 2.14.0+ 且车机已授予相机权限。",
                prefs.mirror,
                new Setter() { @Override public void set(boolean v) { prefs.mirror = v; } });
        swMirror = (PillSwitch) r7.getTag();
        p.addView(r7);

        // 视角来源：车机把 AVM 合成画面当一路相机暴露出来，默认是 360 俯视 + 前视。
        // 打灯要看的是侧后方广角，所以默认切到四路鱼眼四宫格再裁对应象限。
        LinearLayout r8 = toggle("鱼眼视角", "四路鱼眼",
                "开：打灯时把 AVM 切到四路鱼眼四宫格，取该侧广角视野，松灯自动还原。\n"
                        + "关：直接用 AVM 默认的 360 俯视 + 前视拼接画面。",
                prefs.mirrorAvm,
                new Setter() { @Override public void set(boolean v) { prefs.mirrorAvm = v; } });
        p.addView(r8);

        // 相机 id 是实车标定的（哪一路是左后/右后没有统一约定），所以给两个步进器现场调。
        LinearLayout mcL = ui.stepperItem("影像源 · 左灯相机", "id " + prefs.mirrorCamL,
                new View.OnClickListener() {
                    @Override public void onClick(View v) { bumpMirrorCam(true, -1); }
                },
                new View.OnClickListener() {
                    @Override public void onClick(View v) { bumpMirrorCam(true, 1); }
                });
        tvMirrorCamL = (TextView) mcL.getTag();
        p.addView(mcL);
        LinearLayout mcR = ui.stepperItem("影像源 · 右灯相机", "id " + prefs.mirrorCamR,
                new View.OnClickListener() {
                    @Override public void onClick(View v) { bumpMirrorCam(false, -1); }
                },
                new View.OnClickListener() {
                    @Override public void onClick(View v) { bumpMirrorCam(false, 1); }
                });
        tvMirrorCamR = (TextView) mcR.getTag();
        p.addView(mcR);
        p.addView(ui.hint("相机 id 在 0~7 之间循环。哪一路是左后/右后因车而异，打灯实测着调"));

        buildQuadCropUi(p);

        // 底部中央区域：里程 / 时间 / 平均车速 / 平均能耗
        p.addView(ui.group("底部中央区域"));
        LinearLayout d1 = toggle("里程", "", "启动后距离", prefs.tripDist,
                new Setter() { @Override public void set(boolean v) { prefs.tripDist = v; onTripSwitch(v); } });
        swTripDist = (PillSwitch) d1.getTag();
        LinearLayout d2 = toggle("时间", "", "启动后时长", prefs.tripTime,
                new Setter() { @Override public void set(boolean v) { prefs.tripTime = v; onTripSwitch(v); } });
        swTripTime = (PillSwitch) d2.getTag();
        p.addView(ui.pair(d1, d2));

        LinearLayout d3 = toggle("平均车速", "", "启动后平均车速", prefs.tripSpeed,
                new Setter() { @Override public void set(boolean v) { prefs.tripSpeed = v; onTripSwitch(v); } });
        swTripSpeed = (PillSwitch) d3.getTag();
        LinearLayout d4 = toggle("平均能耗", "", "启动后能耗均值", prefs.tripKwh,
                new Setter() { @Override public void set(boolean v) { prefs.tripKwh = v; onTripSwitch(v); } });
        swTripKwh = (PillSwitch) d4.getTag();
        p.addView(ui.pair(d3, d4));

        // 导航地图区域
        p.addView(ui.group("导航地图区域"));
        LinearLayout n1 = toggle("导航信息", "测试版", "导航时地图卡片（仅适配右侧）车标两侧显示剩余里程/时间与到达时间", prefs.nav,
                new Setter() { @Override public void set(boolean v) { prefs.nav = v; } });
        swNav = (PillSwitch) n1.getTag();
        LinearLayout n2 = toggle("歌词", "", "显示当前歌词行（读取车机音乐 App 的 MediaSession，仅需通知使用权）", prefs.lyric,
                new Setter() { @Override public void set(boolean v) { prefs.lyric = v; } });
        swLyric = (PillSwitch) n2.getTag();
        p.addView(ui.pair(n1, n2));

        // 红绿灯读秒：数据来自「红绿灯领航」自带 SDK 的日志，与它的界面无关
        p.addView(ui.group("红绿灯（路口读秒）"));
        LinearLayout t1 = toggle("红绿灯读秒", "内测",
                "在仪表地图卡下方、原厂胶囊位显示左转 / 直行的灯色与倒计时；仅在 D 档且路口有相位时出现。\n"
                        + "数据取自「红绿灯领航」App 的后台日志（0.5 秒一条），不需要登录、也不依赖它自己的悬浮窗；"
                        + "该 App 没装或没在后台运行时，这一块保持不显示。",
                prefs.tl,
                new Setter() { @Override public void set(boolean v) { prefs.tl = v; } });
        swTl = (PillSwitch) t1.getTag();
        // 调试模拟：停车时也能把版式、颜色、读秒、相位切换全过一遍，不依赖真实路口数据
        LinearLayout t2 = toggle("模拟相位", "调试",
                "不读真实日志，本地 30 秒循环：红 15s → 绿 12s → 黄 3s；前 20 秒双灯（左转+直行），后 10 秒单灯居中。\n"
                        + "只在停车调试时打开，上路前关掉即可恢复真实数据。",
                prefs.tlDemo,
                new Setter() { @Override public void set(boolean v) { prefs.tlDemo = v; } });
        swTlDemo = (PillSwitch) t2.getTag();
        p.addView(ui.pair(t1, t2));
        p.addView(ui.hint("只有能算出倒计时的相位才会画；转向箭头常没有读秒（数据源只给灯色），这类相位会被跳过。"));
        p.addView(ui.hint("当前不显示 = 车停着时数据源只回 cur=0（无相位）。打开「模拟相位」即可验证仪表端绘制是否正常。"));

        p.addView(ui.group("歌词显示位置"));
        LinearLayout lb = toggle("歌词显示在下方", "二选一",
                "关闭（默认）：歌词在仪表左上角，含歌名/歌手，三行；底部行程行正常显示。\n"
                        + "打开：歌词移到仪表底部中央（原里程/平均/能耗的位置），显示两行歌词，"
                        + "歌名/歌手改到歌词左侧上方的小字行；打开时会自动关闭"
                        + "「里程 / 时间 / 平均车速 / 平均能耗」四个开关，底部中央区域完全让给歌词。"
                        + "关掉本开关会恢复这四个开关原来的状态。",
                prefs.lyricBottom,
                new Setter() {
                    @Override public void set(boolean v) {
                        prefs.lyricBottom = v;
                        if (v) {
                            // 记住开启前的状态，关闭时还原
                            tripBackup = (prefs.tripDist ? 1 : 0) | (prefs.tripTime ? 2 : 0)
                                    | (prefs.tripSpeed ? 4 : 0) | (prefs.tripKwh ? 8 : 0);
                            prefs.tripDist = false;
                            prefs.tripTime = false;
                            prefs.tripSpeed = false;
                            prefs.tripKwh = false;
                            syncTripSwitches();
                            toast("歌词已移到下方，行程行四个开关已自动关闭");
                        } else {
                            int m = tripBackup >= 0 ? tripBackup : 15;
                            prefs.tripDist = (m & 1) != 0;
                            prefs.tripTime = (m & 2) != 0;
                            prefs.tripSpeed = (m & 4) != 0;
                            prefs.tripKwh = (m & 8) != 0;
                            tripBackup = -1;
                            syncTripSwitches();
                            toast("歌词回到左上，行程行已恢复");
                        }
                    }
                });
        swLyricBottom = (PillSwitch) lb.getTag();
        p.addView(ui.pair(lb, null));
        p.addView(ui.hint("下方区域已做位置限制：两行歌词以屏幕中央为轴、宽度不超过 700px，"
                + "落在仪表底部带内；歌名/歌手显示在歌词左侧上方的小字行。"
                + "歌词过长会自动缩小字号、仍超则截断，不会压到左侧电瓶/车内温度或右下导航卡。"));

        p.addView(ui.hint("没有有效胎压 / 胎温时整块不画，不会显示 0 或占位"));
        return p;
    }

    // ---------------- 页 5：歌词位置（大画布拖动，仅作用于左上角歌词） ----------------

    private LinearLayout buildLyricPosPage() {
        LinearLayout p = pageRoot();
        p.addView(ui.pageHeader("歌词位置", pageChip()));

        p.addView(ui.group("在仪表上拖动歌词（左上角三行歌词）"));
        p.addView(ui.hint("在下面的画布上按住绿色歌词框拖动，就是歌词在仪表上的位置。"
                + "画布按仪表 1920 × 720 等比显示；车机屏只负责设置，画面在仪表上。"
                + "范围已做吸附：歌词块不会被拖出仪表屏幕。"));

        tvLyricPosState = ui.hint("");
        tvLyricPosState.setTextColor(ui.tagWarn);
        p.addView(tvLyricPosState);

        // 锁定位置：调好之后，翻设置页时手指在画布上滑一下就会把歌词挪走，且当场推到仪表。
        // 这个开关只关掉"改位置"的入口，仪表上照常显示；纯车机端状态，不写进 config.json。
        p.addView(toggle("锁定歌词位置", "", "锁定后无法在下方画布上拖动歌词，防止翻页时误触；仪表显示不受影响", prefs.lyricLock,
                new Setter() { @Override public void set(boolean v) {
                    prefs.lyricLock = v;
                    if (lyricMap != null) lyricMap.setLocked(v);
                    applyLyricLockUi();
                    refreshLyricPosState();
                    toast(v ? "歌词位置已锁定，画布不可拖动" : "歌词位置已解锁，可以拖动");
                } }));

        // 背景卡片：颜色取自车机中控「正在播放条」下方那层（夜 #191A1F / 昼 #DFE3EB），
        // 仪表端按当前昼夜自动挑，用户不用管。
        p.addView(toggle("歌词背景", "", "在歌词块后面加一层底色卡片，颜色跟随车机播放条，昼夜自动切换", prefs.lyricBg,
                new Setter() { @Override public void set(boolean v) {
                    prefs.lyricBg = v;
                    prefs.save(MainActivity.this);
                    schedulePush(120, false);
                } }));

        // 居中排布：块内文字在限宽区域内水平居中（块本身位置不变）
        p.addView(toggle("歌词居中排布", "", "块内文字水平居中；关掉则左对齐（左侧强调竖条只在左对齐时显示）", prefs.lyricCenter,
                new Setter() { @Override public void set(boolean v) {
                    prefs.lyricCenter = v;
                    prefs.save(MainActivity.this);
                    schedulePush(120, false);
                } }));

        // 暂停隐藏：暂停/停止播放时整块不画（默认关——暂停后停在最后一句更常见）
        p.addView(toggle("暂停时隐藏歌词", "", "暂停或停止播放后整块歌词不再显示；关掉则停在最后一句", prefs.lyricHideOnPause,
                new Setter() { @Override public void set(boolean v) {
                    prefs.lyricHideOnPause = v;
                    prefs.save(MainActivity.this);
                    schedulePush(120, false);
                } }));

        // 整体大小：0.6 ~ 1.8 共 25 档（步进 0.05）。改这里会同时缩字号、行高、间距，
        // 预览画布的包围盒和可拖动范围也跟着变（放大后块更大、可达范围更小）。
        p.addView(ui.group("歌词整体大小"));
        tvLyricScale = ui.hint("");
        p.addView(tvLyricScale);
        SeekBar sbScale = new SeekBar(this);
        sbScale.setMax(24);
        sbScale.setProgress(scaleToProgress(prefs.lyricScale));
        sbScale.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int prog, boolean fromUser) {
                float k = progressToScale(prog);
                prefs.lyricScale = k;
                if (lyricMap != null) lyricMap.setScale(k);
                updateLyricScaleLabel(k);
                updateLyricPosLabel(prefs.lyricPosX, prefs.lyricPosY);
                if (fromUser) {
                    prefs.save(MainActivity.this);
                    schedulePush(260, false);   // 拖动过程中限速推送，松手再推一次最终值
                }
            }
            @Override public void onStartTrackingTouch(SeekBar sb) { }
            @Override public void onStopTrackingTouch(SeekBar sb) {
                prefs.save(MainActivity.this);
                schedulePush(120, false);
                toast("歌词大小已设为 " + String.format(java.util.Locale.ROOT, "%.0f%%", prefs.lyricScale * 100));
            }
        });
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = ui.dp(4);
        p.addView(sbScale, slp);
        updateLyricScaleLabel(prefs.lyricScale);

        // 大画布：按 8:3（1920:720）比例，宽度取大屏内容区可调的较大值，触摸热区大
        lyricMap = new LyricPositionMap(this);
        // 画布尺寸见 applyLyricMapSize()：必须落在内容区可用宽度内，
        // 且全屏 ↔ 分屏切换后要按新宽度重算
        int[] ms = lyricMapSize();
        int mapW = ms[0], mapH = ms[1];
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(mapW, mapH);
        mlp.topMargin = ui.dp(8);
        mlp.gravity = Gravity.CENTER_HORIZONTAL;
        p.addView(lyricMap, mlp);

        lyricMap.setScale(prefs.lyricScale);
        lyricMap.setPos(prefs.lyricPosX, prefs.lyricPosY);
        lyricMap.setLocked(prefs.lyricLock);
        lyricMap.setListener(new LyricPositionMap.OnPosChanged() {
            @Override public void onChanged(int x, int y, boolean dragging) {
                prefs.lyricPosX = x;
                prefs.lyricPosY = y;
                updateLyricPosLabel(x, y);
                if (!dragging) {
                    prefs.save(MainActivity.this);
                    schedulePush(150, false);   // 松手即推到仪表，立即生效
                    toast("歌词位置已设为 X" + x + " Y" + y);
                }
            }
        });
        updateLyricPosLabel(prefs.lyricPosX, prefs.lyricPosY);

        tvLyricPos = ui.hint("");
        p.addView(tvLyricPos);

        p.addView(ui.sep());
        p.addView(ui.buttonRow(
                btnLyricReset = ui.button("恢复默认位置", false, new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        // 锁定的意义就是"位置改不了"，这个按钮也必须让路，否则一下就绕过去了
                        if (prefs.lyricLock) {
                            toast("歌词位置已锁定，先在上方关掉「锁定歌词位置」");
                            return;
                        }
                        prefs.lyricPosX = 0;
                        prefs.lyricPosY = 0;
                        prefs.lyricScale = 1f;
                        if (sbScale != null) sbScale.setProgress(scaleToProgress(1f));
                        updateLyricScaleLabel(1f);
                        prefs.save(MainActivity.this);
                        if (lyricMap != null) lyricMap.setPos(0, 0);
                        updateLyricPosLabel(0, 0);
                        schedulePush(150, false);
                        toast("歌词位置已恢复默认（左上角）");
                    }
                }),
                ui.button("推送到仪表", true, new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        prefs.save(MainActivity.this);
                        schedulePush(120, true);
                    }
                })
        ));
        p.addView(ui.hint("说明：本页只调「歌词显示在下方」关闭时的左上角歌词。"
                + "若已打开「显示内容 → 歌词显示在下方」，请关掉它再看效果；"
                + "底部歌词位置固定，不受本页影响。"));
        applyLyricLockUi();
        return p;
    }

    /** 锁定态联动：「恢复默认位置」按钮置灰，避免用这个入口绕过锁定改位置 */
    private void applyLyricLockUi() {
        if (btnLyricReset == null) return;
        btnLyricReset.setEnabled(!prefs.lyricLock);
        btnLyricReset.setAlpha(prefs.lyricLock ? 0.4f : 1f);
    }

    /**
     * 画布尺寸：宽度必须落在内容区里（屏宽 - 侧栏 - 边距），
     * 宽高锁 8:3 与仪表 1920×720 严格等比，否则坐标映射会失真。
     * 全屏/分屏切换后窗口宽度变了，必须重算——否则仍按旧尺寸画，
     * 右侧会被裁掉但触摸区还在，表现为「拖到右边看不见却还能拖」。
     */
    private int[] lyricMapSize() {
        int sideW = ui.dp(176);
        int availW = getResources().getDisplayMetrics().widthPixels - sideW - ui.dp(56);
        int w = Math.min(ui.dp(1320), Math.max(ui.dp(480), (int) (availW * 0.98f)));
        return new int[]{w, (int) (w * 720f / 1920f)};
    }

    private void applyLyricMapSize() {
        if (lyricMap == null) return;
        int[] ms = lyricMapSize();
        ViewGroup.LayoutParams lp = lyricMap.getLayoutParams();
        if (lp != null) {
            lp.width = ms[0];
            lp.height = ms[1];
            lyricMap.setLayoutParams(lp);
            lyricMap.invalidate();
        }
    }

    @Override
    public void onConfigurationChanged(Configuration nc) {
        super.onConfigurationChanged(nc);
        // 全屏 ↔ 分屏（freeform 窗口改尺寸）：系统不会重建 Activity（manifest 里
        // 声明了 screenSize），这里手动把画布按新宽度重排一次。
        if (main != null) {
            main.post(new Runnable() {
                @Override public void run() {
                    applyLyricMapSize();
                }
            });
        }
    }

    private void updateLyricPosLabel(int x, int y) {
        if (tvLyricPos != null) {
            float k = prefs.lyricScale <= 0 ? 1f : prefs.lyricScale;
            int mw = Math.round(1920f - 640f * k);
            int mh = Math.round(720f - 186f * k);
            tvLyricPos.setText("当前位置：X = " + x + " px，Y = " + y + " px"
                    + "　（0,0 = 原左上角；当前 " + Math.round(k * 100) + "%，"
                    + "范围 X 0–" + mw + " / Y 0–" + mh + "）");
        }
    }

    /** 0.6 ~ 1.8 映射到 0 ~ 24，步进 0.05 */
    private static int scaleToProgress(float k) {
        if (k <= 0) k = 1f;
        int v = Math.round((k - 0.6f) / 0.05f);
        return Math.max(0, Math.min(24, v));
    }

    private static float progressToScale(int prog) {
        int p = Math.max(0, Math.min(24, prog));
        return 0.6f + p * 0.05f;
    }

    private void updateLyricScaleLabel(float k) {
        if (tvLyricScale != null) {
            tvLyricScale.setText("当前大小：" + Math.round(k * 100) + "%"
                    + "（100% = 原始；范围 60%~180%。放大后歌词块占位更大，可拖动范围相应变小）");
        }
    }

    private void refreshLyricPosState() {
        if (tvLyricPosState == null) return;
        if (prefs.lyricBottom) {
            tvLyricPosState.setText("⚠ 当前「歌词显示在下方」已开启：本页位置不生效，请先到「显示内容」页关掉它。");
            tvLyricPosState.setTextColor(ui.tagWarn);
        } else if (!prefs.lyric) {
            tvLyricPosState.setText("⚠ 当前「歌词」开关已关闭：仪表不显示歌词，请在「显示内容」页打开。");
            tvLyricPosState.setTextColor(ui.tagWarn);
        } else if (prefs.lyricLock) {
            tvLyricPosState.setText("● 歌词已开启，位置已锁定：下方画布不可拖动，关掉「锁定歌词位置」即可调整。");
            tvLyricPosState.setTextColor(ui.tagWarn);
        } else {
            tvLyricPosState.setText("● 歌词已开启，拖动下方歌词框即可调整位置，松手自动生效。");
            tvLyricPosState.setTextColor(ui.tagGreen);
        }
    }

    // ---------------- 页 3：调试模式（实验功能 + 缩放/日夜/位置 + 运维） ----------------

    // ---------------- 页 6：充电模式 ----------------

    private LinearLayout buildChargePage() {
        LinearLayout p = pageRoot();
        p.addView(ui.pageHeader("充电模式 · 测试版", pageChip()));

        p.addView(ui.group("充电界面"));
        p.addView(ui.hint("充电时把整个仪表切成充电页：续航大数字、剩余时间、电量、已充时长，"
                + "底部一排 功率 | 电压 | 电流。数据全部从仪表总线直读，不依赖车机网络。"
                + "背景是内置的极光动画（121 帧循环，随昼夜不变）。"));
        // 用数组做中转：Setter 里要拿到开关本体做"拒绝后回退"，但变量本身
        // 还在初始化中，不能直接引用
        final LinearLayout[] chgRow = new LinearLayout[1];
        LinearLayout rChargePage = toggle("充电界面", "测试版",
                "充电时整屏显示充电页，替代原厂充电界面（开启前需确认免责声明）",
                prefs.chargePage, new Setter() {
                    @Override public void set(boolean v) {
                        if (!v) {
                            prefs.chargePage = false;
                            return;
                        }
                        // 打开前必须确认免责声明；用户拒绝时开关自动退回关闭
                        PillSwitch sw = chgRow[0] == null ? null : (PillSwitch) chgRow[0].getTag();
                        showChargeDisclaimer(sw);
                    }
                });
        chgRow[0] = rChargePage;
        p.addView(ui.pair(rChargePage, null));

        p.addView(ui.sep());
        p.addView(ui.group("调试"));
        p.addView(ui.hint("驻车、没插枪时也能看充电页：用演示数值（478km / 99% / 5min / "
                + "6.0kW / 494.7V / 14.8A）铺满整屏，用来调版面。实车验收前记得关掉。"));
        p.addView(toggle("充电页调试", "演示数据", "强制显示充电页并使用演示数值（不看真实充电状态）",
                prefs.chargeDebug,
                new Setter() { @Override public void set(boolean v) {
                    prefs.chargeDebug = v;
                    prefs.save(MainActivity.this);
                    schedulePush(120, false);
                } }));

        p.addView(toggle("8 种界面态轮播", "每 6 秒一换", "未连接 → 准备 → 充电中 → 完成 → 故障 → "
                + "定时充电 → 电池加热 → 电池冷却，用来核对每个态的版面（需同时打开「充电页调试」）",
                prefs.chargeStatePreview,
                new Setter() { @Override public void set(boolean v) {
                    prefs.chargeStatePreview = v;
                    prefs.save(MainActivity.this);
                    schedulePush(120, false);
                } }));

        p.addView(toggle("警示条预览", "轮播 16 条", "把原厂 16 条充电/电池警示每 4 秒轮换一条，"
                + "用来核对每条文案的版式（需同时打开上面的「充电页调试」）",
                prefs.chargeWarnPreview,
                new Setter() { @Override public void set(boolean v) {
                    prefs.chargeWarnPreview = v;
                    prefs.save(MainActivity.this);
                    schedulePush(120, false);
                } }));

        p.addView(ui.sep());
        p.addView(ui.group("播放器"));
        p.addView(ui.hint("动画由仪表本地播放器渲染：帧包一次性传到仪表、本地循环播放，车机不再逐帧推。"));
        tvChgAnim = ui.hint("");
        tvChgAnim.setTextColor(ui.textSub);
        p.addView(tvChgAnim);
        p.addView(ui.buttonRow(
                ui.button("检查资源", false, new View.OnClickListener() {
                    @Override public void onClick(View v) { checkVideoRes(); }
                }),
                ui.button("上传资源", true, new View.OnClickListener() {
                    @Override public void onClick(View v) { pushVideoRes(); }
                }),
                ui.button("还原", false, new View.OnClickListener() {
                    @Override public void onClick(View v) { restoreVideoRes(); }
                })
        ));

        p.addView(ui.hint("帧率"));
        p.addView(ui.buttonRow(
                ui.button("8", false, fpsBtn(8)),
                ui.button("10", false, fpsBtn(10)),
                ui.button("12", false, fpsBtn(12))
        ));
        p.addView(ui.buttonRow(
                ui.button("15", false, fpsBtn(15)),
                ui.button("20", false, fpsBtn(20)),
                ui.button("24", false, fpsBtn(24))
        ));

        tvCastDbg = ui.hint("");
        tvCastDbg.setTextColor(ui.textSub);
        p.addView(tvCastDbg);
        p.addView(ui.buttonRow(
                ui.button("刷新状态", true, new View.OnClickListener() {
                    @Override public void onClick(View v) { refreshCastDebug(); }
                }),
                ui.button("重启播放器", false, new View.OnClickListener() {
                    @Override public void onClick(View v) { restartVideo(); }
                }),
                ui.button("播放器日志", false, new View.OnClickListener() {
                    @Override public void onClick(View v) { showVideoLog(); }
                })
        ));

        p.addView(ui.hint("写屏方式（切换后仪表自动重启播放器，约 5 秒黑屏）。"
                + "pwrite 走系统调用把整帧拷进显存；mmap 把显存映射进进程直接写，少一次拷贝。"
                + "切完点上面「刷新状态」对比「写屏」那一项的耗时，哪个低用哪个。"));
        p.addView(ui.buttonRow(
                ui.button("写屏 pwrite", false, writeBtn("pwrite")),
                ui.button("写屏 mmap", false, writeBtn("mmap"))
        ));

        p.addView(ui.hint("垂直同步（切换后同样会自动重启播放器）。"
                + "on：每次写屏前等 VSYNC，撕裂线固定在同一个位置；"
                + "off：不等，更容易跑满帧，但撕裂位置随机。帧率上不去时先试 off。"));
        p.addView(ui.buttonRow(
                ui.button("同步 on", false, vsyncBtn("on")),
                ui.button("同步 off", false, vsyncBtn("off"))
        ));

        p.addView(ui.sep());
        p.addView(ui.group("充电数据自检"));
        p.addView(ui.hint("读仪表端每秒落盘的原始值（mcu / gun / echarge 三组枚举，"
                + "以及电量、续航、电压电流、电芯温度、当前警示）。"
                + "充电时点一次，把这行数据发我，就能把枚举和真实状态对齐。"));
        tvChgRaw = ui.hint("");
        tvChgRaw.setTextColor(ui.textSub);
        p.addView(tvChgRaw);
        p.addView(ui.buttonRow(
                ui.button("查看充电原始数据", true, new View.OnClickListener() {
                    @Override public void onClick(View v) { showChargeRaw(); }
                })
        ));
        return p;
    }

    /**
     * 充电界面（测试版）免责声明。
     *
     * 必须"先声明后启用"：这个功能会整屏接管仪表，一旦出问题用户看到的不是
     * 原厂界面而是我们的画面，所以要把可能发生的后果逐条讲清楚再让他自己决定。
     * 用户点「不同意」时把开关退回关闭（静默设置，避免再次触发本对话框）。
     */
    private void showChargeDisclaimer(final PillSwitch sw) {
        String msg = "该功能为测试功能，启用此功能所造成的一切后果由您承担。\n\n"
                + "开启后可能发生的后果：\n"
                + "1. 数据显示可能不准：电压、电流、功率、剩余时间、电量等由仪表总线自行换算，"
                + "尚未经过完整实车标定，可能与车机或充电桩显示不一致。\n"
                + "2. 整屏接管：充电时整个仪表画面由本功能绘制，原厂充电界面被覆盖；"
                + "原厂的部分提示、指示灯可能不再出现。本功能自绘的警示条只覆盖已知的 16 条"
                + "充电类文案，其他报警可能看不到。\n"
                + "3. 可能出现显示异常：黑屏、闪烁、画面卡住或动画不播放。出现时把本开关关掉即可"
                + "恢复原厂界面。\n"
                + "4. 不影响充电本身：本功能只改变显示，不参与充电控制，不会改变充电功率或策略；"
                + "但请勿用本界面判断能否拔枪或离车，以车机和充电桩为准。\n"
                + "5. 固件升级后可能失效或表现异常，需等待适配。\n"
                + "6. 本功能为非官方功能，与车辆厂商无关，因使用产生的任何直接或间接后果由使用者自行承担。\n\n"
                + "点「我已了解并开启」表示您已阅读并同意以上全部内容。";
        new AlertDialog.Builder(this)
                .setTitle("充电界面（测试版）")
                .setMessage(msg)
                .setPositiveButton("我已了解并开启", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        prefs.chargePage = true;
                        prefs.save(MainActivity.this);
                        schedulePush(120, false);
                        toast("已开启充电界面（测试版）");
                        // 顺便把「本地播放器 + 帧包」推到仪表：有这两个文件才是 24fps 本地播放，
                        // 没有就自动回落到逐帧投屏（约 11fps）。已一致时不会重复上传。
                        uploadChargeVideoAsync();
                    }
                })
                .setNegativeButton("不同意", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        if (sw != null) sw.setCheckedSilently(false);
                        prefs.chargePage = false;
                        prefs.save(MainActivity.this);
                        schedulePush(120, false);
                        toast("已保持关闭");
                    }
                })
                .setCancelable(false)
                .show();
    }

    /** 后台上传视频模式资源（播放器 + 帧包），失败静默——不影响充电页的逐帧投屏兜底 */
    private void uploadChargeVideoAsync() {
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    long n = deploy.pushChargeVideo(null);
                    if (n > 0) {
                        // 换了包必须让播放器重启，否则它还在播内存里的旧帧
                        try { deploy.requestRestartVideo(); } catch (Throwable ignored) { }
                        final long mb = Math.max(1, n / 1048576);
                        runOnUiThread(new Runnable() {
                            @Override public void run() {
                                toast("视频资源已上传 " + mb + "MB，仪表正在重启播放器");
                            }
                        });
                    }
                } catch (Throwable ignored) { }
            }
        }, "p7-videores").start();
    }

    /** 读 status.json 看仪表端播放器状态 */
    private void refreshChargePageState() {
        if (tvChgAnim == null) return;
        tvChgAnim.setText("点「检查资源」看仪表端播放器和帧包是否就位。");
    }

    /** 检查仪表端视频资源（播放器 + 帧包）与当前模式 */
    private void checkVideoRes() {
        if (tvChgAnim == null) return;
        tvChgAnim.setText("检查中…");
        new Thread(new Runnable() {
            @Override public void run() {
                String r;
                try {
                    org.json.JSONObject st = deploy.status();
                    long[] sz = deploy.videoResDiag();
                    long player = sz[0], pack = sz[1], pPart = sz[2], kPart = sz[3];
                    boolean ok = player > 100000 && pack > 100000;
                    String v = st.optString("chgVideo", "");
                    String mode = "vplay".equals(v) ? "本地播放器接管整屏"
                            : ("screen".equals(st.optString("chgMode", "")) ? "逐帧投屏（回落通道）"
                            : "未接管（没充电、或充电页开关没开、或资源不齐）");
                    StringBuilder b = new StringBuilder();
                    b.append(ok ? "✅ 资源就位：" : "⚠️ 资源缺失：");
                    b.append("\n播放器 ").append(player > 0 ? (player / 1024) + " KB" : "无")
                     .append("　帧包 ").append(pack > 0 ? (pack / 1024) + " KB" : "无");
                    if (pPart > 0 || kPart > 0) {
                        b.append("\n正在上传：播放器 ").append(pPart / 1024).append(" KB")
                         .append("　帧包 ").append(kPart / 1024).append(" KB")
                         .append("\n（.part 有值 = 还没传完，等它变成正式文件再看）");
                    }
                    b.append("\n当前模式：").append(mode);
                    b.append("\n仪表版本：").append(st.optString("version", "?"));
                    String pv = deploy.remotePlayerVer();
                    if (!pv.isEmpty()) b.append("\n播放器版本：").append(pv.substring(0, Math.min(8, pv.length())));
                    if (!ok) b.append("\n点「上传资源」推一次（播放器 2MB + 帧包 35MB）");
                    r = b.toString();
                } catch (Throwable t) {
                    r = "读不到仪表状态：" + t.getMessage();
                }
                final String out = r;
                runOnUiThread(new Runnable() {
                    @Override public void run() { tvChgAnim.setText(out); }
                });
            }
        }).start();
    }

    /** 帧率按钮：写 videofps，仪表会把播放器重启一遍 */
    private View.OnClickListener fpsBtn(final int fps) {
        return new View.OnClickListener() {
            @Override public void onClick(View v) { setVideoFps(fps); }
        };
    }

    /** 写屏方式按钮：写 videowrite，仪表会把播放器重启一遍 */
    private View.OnClickListener writeBtn(final String mode) {
        return new View.OnClickListener() {
            @Override public void onClick(View v) { setVideoWrite(mode); }
        };
    }

    private void setVideoWrite(final String mode) {
        final String label = "mmap".equals(mode) ? "mmap" : "pwrite";
        toast("正在切换写屏方式：" + label + "…");
        new Thread(new Runnable() {
            @Override public void run() {
                String m;
                try {
                    deploy.setVideoWrite(mode);
                    m = "已切到 " + label + "，播放器重启中（约 5 秒），之后点「刷新状态」对比写屏耗时";
                } catch (Throwable t) {
                    m = "切换失败：" + t.getMessage();
                }
                final String out = m;
                runOnUiThread(new Runnable() {
                    @Override public void run() { toast(out); }
                });
            }
        }).start();
    }

    /** 垂直同步开关：写 videovsync，仪表会把播放器重启一遍 */
    private View.OnClickListener vsyncBtn(final String mode) {
        return new View.OnClickListener() {
            @Override public void onClick(View v) { setVideoVsync(mode); }
        };
    }

    private void setVideoVsync(final String mode) {
        final String label = "off".equals(mode) ? "关" : "开";
        toast("正在设置垂直同步：" + label + "…");
        new Thread(new Runnable() {
            @Override public void run() {
                String m;
                try {
                    deploy.setVideoVsync(mode);
                    m = "垂直同步已" + label + "，播放器重启中（约 5 秒），之后点「刷新状态」看实测帧率";
                } catch (Throwable t) {
                    m = "设置失败：" + t.getMessage();
                }
                final String out = m;
                runOnUiThread(new Runnable() {
                    @Override public void run() { toast(out); }
                });
            }
        }).start();
    }

    private void setVideoFps(final int fps) {
        toast("正在设置 " + fps + "fps…");
        new Thread(new Runnable() {
            @Override public void run() {
                String m;
                try {
                    deploy.setVideoFps(fps);
                    m = "已设为 " + fps + "fps，播放器重启中（约 5 秒）";
                } catch (Throwable t) {
                    m = "设置失败：" + t.getMessage();
                }
                final String out = m;
                runOnUiThread(new Runnable() {
                    @Override public void run() { toast(out); }
                });
            }
        }).start();
    }

    /** 让仪表重启本地播放器（改完帧包/画面不对时先试这个） */
    private void restartVideo() {
        new Thread(new Runnable() {
            @Override public void run() {
                String m;
                try {
                    deploy.requestRestartVideo();
                    m = "已请求仪表重启播放器（约 2 秒后生效）";
                } catch (Throwable t) {
                    m = "请求失败：" + t.getMessage();
                }
                final String msg = m;
                runOnUiThread(new Runnable() {
                    @Override public void run() { toast(msg); }
                });
            }
        }).start();
    }

    /**
     * 读播放器日志：看仪表端真实 fps / 解码 / 缩放 / 写屏耗时。
     *
     * 失败时再自动重试一次（Ftp 内部也会重建连接）：这类失败绝大多数是
     * 长连接空闲被服务端回收，不是仪表上真没有日志。
     */
    private void showVideoLog() {
        if (tvCastDbg == null) return;
        tvCastDbg.setText("读取播放器日志…");
        new Thread(new Runnable() {
            @Override public void run() {
                String t = deploy.videoLogTail(1200);
                if (t == null) t = deploy.videoLogTail(1200);
                final String out = t;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        tvCastDbg.setText(out == null || out.trim().isEmpty()
                                ? "读不到播放器日志：仪表上没有日志文件，或 FTP 连接异常（可再点一次）"
                                : out);
                    }
                });
            }
        }).start();
    }

    /**
     * 投屏调试：把车机发送侧 + 仪表接收侧的真实状态拼在一起。
     *
     * 视频模式下一切数据都取自仪表上报的实测值（chgWantFps / chgRealFps / chgDecMs …），
     * 不再写死"24fps"这种会过期的说明——之前那版就是照着旧参数写的，一改帧率就全错。
     */
    private void refreshCastDebug() {
        if (tvCastDbg == null) return;
        tvCastDbg.setText("读取中…");
        new Thread(new Runnable() {
            @Override public void run() {
                // 车机侧已经不再推帧（逐帧投帧通道 2026-11-20 移除），
                // 所以这里固定说明，不再去读一个永远不动的发送统计。
                String send = "车机：不推帧（动画只走仪表本地播放器）";
                String recv;
                try {
                    org.json.JSONObject st = deploy.status();
                    String video = st.optString("chgVideo", "");
                    String mode = st.optString("chgMode", "");
                    int age = st.optInt("chgFrmAgeMs", -1);
                    int kb = st.optInt("chgFrmKB", 0);
                    String ver = st.optString("version", "?");
                    if ("vplay".equals(video)) {
                        StringBuilder b = new StringBuilder();
                        b.append("仪表：本地播放器接管整屏（version ").append(ver).append("）");
                        String want = st.optString("chgWantFps", "");
                        String write = st.optString("chgWrite", "");
                        int packFrames = st.optInt("chgPackFrames", 0);
                        int packKB = st.optInt("chgPackKB", 0);
                        boolean statsOk = st.optBoolean("chgStatsOk", false);
                        if (packFrames > 0) {
                            b.append("\n帧包：").append(packFrames).append(" 帧 / ")
                             .append(packKB / 1024).append(" MB");
                        }
                        if (!want.isEmpty()) {
                            b.append("　目标 ").append(want).append(" fps");
                        }
                        if (!write.isEmpty()) {
                            b.append("　写屏 ").append(write);
                        }
                        if (statsOk) {
                            double real = st.optDouble("chgRealFps", 0);
                            double dec = st.optDouble("chgDecMs", 0);
                            double ren = st.optDouble("chgRenMs", 0);
                            double wri = st.optDouble("chgWriMs", 0);
                            b.append(String.format(java.util.Locale.US,
                                    "\n实测 %.1f fps　解码 %.1fms　缩放 %.1fms　写屏 %.1fms", real, dec, ren, wri));
                            // 写屏占单帧周期的比例：越接近 100%，撕裂几乎每帧都会出现
                            if (real > 0.1) {
                                double duty = wri * real / 10.0;
                                b.append("\n写屏占空比 ").append(Math.round(duty)).append("%");
                                if (duty >= 90) b.append("（几乎每帧都写满整个周期）");
                            }
                            b.append("\n主要耗时：").append(
                                    wri >= dec && wri >= ren ? "写屏（换编码省不掉）"
                                  : dec >= ren ? "JPEG 解码（换 RGB565 可省）" : "缩放/转 565（换 RGB565 可省）");
                        } else {
                            b.append("\n还没读到统计行（播放器启动后约 2 秒出第一行）");
                        }
                        String vsync = st.optString("chgVsync", "");
                        if (!vsync.isEmpty()) {
                            b.append("\n").append(vsync);
                            b.append(vsync.contains("ioctl=0") ? "（支持等垂直同步）" : "（内核不支持，等了个空）");
                        }
                        b.append("\n—— 这个模式下汽车机不推帧，画面全靠仪表自己跑");
                        recv = b.toString();
                    } else if ("screen".equals(mode)) {
                        recv = "仪表：投屏接收中（" + (age >= 0 ? age + " 毫秒前" : "刚刚")
                                + "收到一帧，" + kb + " KB）";
                    } else if ("none".equals(mode)) {
                        recv = "仪表：没有可用帧（不会接管充电页，保持原厂画面）";
                    } else {
                        recv = "仪表：状态未知（version " + ver + "，可能还是旧版本）";
                    }
                } catch (Throwable t) {
                    recv = "仪表：读取失败（" + t.getMessage() + "）";
                }
                final String out = send + "\n" + recv;
                runOnUiThread(new Runnable() {
                    @Override public void run() { tvCastDbg.setText(out); }
                });
            }
        }).start();
    }

    /** 读仪表端 chgraw.json，把关键量拼成一行人话 */
    private void showChargeRaw() {
        tvChgRaw.setText("读取中…");
        new Thread(new Runnable() {
            @Override public void run() {
                String out;
                try {
                    String j = deploy.chargeRaw();
                    if (j == null || j.trim().isEmpty()) {
                        out = "⚠️ 读不到 chgraw.json。可能是：充电页开关没开（只在开启时落盘）、"
                                + "仪表端还没升到 2.9.1、或 FTP 读取失败。";
                    } else {
                        org.json.JSONObject o = new org.json.JSONObject(j);
                        StringBuilder sb = new StringBuilder();
                        sb.append("状态：").append(o.optString("state", "?"));
                        if (o.optBoolean("on")) sb.append("（充电中）");
                        sb.append("\nmcu=").append(o.optInt("mcu", -9))
                          .append("  gun=").append(o.optInt("gun", -9))
                          .append("  echarge=").append(o.optInt("st", -9));
                        sb.append("\n电压 ").append(o.optDouble("volt", 0))
                          .append(" V ｜ 电流 ").append(o.optDouble("cur", 0))
                          .append(" A ｜ 功率 ").append(o.optDouble("power", 0)).append(" kW");
                        sb.append("\n电量 ").append(o.optInt("soc", -1)).append("%")
                          .append(" ｜ 续航 ").append(o.optInt("rangeKm", -1)).append(" km")
                          .append(" ｜ 剩余 ").append(o.optInt("leftMin", -1)).append(" min")
                          .append(" ｜ 已充 ").append(o.optInt("durMin", -1)).append(" min");
                        if (o.optBoolean("cellOK")) {
                            sb.append("\n电芯温度 ").append(o.optDouble("cellTemp", 0))
                              .append("℃（").append(o.optString("cellProp", "")).append("）");
                        } else {
                            sb.append("\n电芯温度：无属性（候选名都没命中）");
                        }
                        sb.append("\n标志：batHot=").append(o.optInt("batHot", 0))
                          .append(" batCold=").append(o.optInt("batCold", 0))
                          .append(" batFault=").append(o.optInt("batFault", 0))
                          .append(" hvCut=").append(o.optInt("hvCutoff", 0))
                          .append(" ind12v=").append(o.optInt("ind12v", 0))
                          .append(" pwLimit=").append(o.optInt("pwLimit", 0))
                          .append(" socLow=").append(o.optInt("socLow", 0));
                        sb.append("\n枪口：L=").append(o.optInt("portL", 0))
                          .append(" R=").append(o.optInt("portR", 0))
                          .append(" ｜ L故障=").append(o.optInt("portLFault", 0))
                          .append(" R故障=").append(o.optInt("portRFault", 0));
                        sb.append("\n预约：").append(o.optInt("apptH", 0)).append(":")
                          .append(o.optInt("apptM", 0))
                          .append(" ｜ 预处理 ").append(o.optInt("batPrepMin", 0)).append(" min")
                          .append(" ｜ alarmID ").append(o.optInt("alarmID", 0));
                        sb.append("\n桩类型：").append(o.optString("pileName", "未判定"))
                          .append("（本会话峰值 ").append(String.format(java.util.Locale.ROOT, "%.1f", o.optDouble("maxPower", 0)))
                          .append(" kW / ").append(String.format(java.util.Locale.ROOT, "%.1f", o.optDouble("maxCur", 0)))
                          .append(" A / ").append(String.format(java.util.Locale.ROOT, "%.1f", o.optDouble("maxVolt", 0)))
                          .append(" V，已持续 ").append(o.optInt("sessSec", 0)).append(" s）");
                        String w = o.optString("warns", "");
                        sb.append("\n当前警示：").append("[]".equals(w) || w.isEmpty() ? "无" : w);
                        out = sb.toString();
                    }
                } catch (Throwable t) {
                    out = "读取失败：" + t.getMessage();
                }
                final String r = out;
                runOnUiThread(new Runnable() {
                    @Override public void run() { tvChgRaw.setText(r); }
                });
            }
        }).start();
    }

    /**
     * 上传播放器 + 帧包（约 14MB，两边一致时自动跳过）。
     *
     * 传完主动让仪表重启播放器：播放器启动时把 charge.pack 整个读进内存，
     * 不重启就还在播旧帧——这就是以前「必须先还原再上传」才换得掉的原因。
     */
    private void pushVideoRes() {
        if (tvChgAnim == null) return;
        tvChgAnim.setText("正在上传播放器 + 帧包（播放器 2MB + 帧包 35MB，慢的话等几分钟）…");
        new Thread(new Runnable() {
            @Override public void run() {
                String r;
                try {
                    final long[] lastKb = {0};
                    long n = deploy.pushChargeVideo(new Ftp.Progress() {
                        @Override public void onProgress(long sent, long total) {
                            final long kb = sent / 1024, tkb = total / 1024;
                            if (sent < total && kb - lastKb[0] < 1024) return;
                            lastKb[0] = kb;
                            runOnUiThread(new Runnable() {
                                @Override public void run() {
                                    if (tvChgAnim != null) {
                                        tvChgAnim.setText("上传中 " + kb + " KB / " + tkb + " KB…");
                                    }
                                }
                            });
                        }
                    });
                    if (n > 0) {
                        // 新版 daemon 会自己发现换包；这行对旧版 daemon 是唯一生效路径。
                        // 两边共用 5 秒冷却，重合时只重启一次。
                        try { deploy.requestRestartVideo(); } catch (Throwable ignored) { }
                        r = "✅ 已上传 " + (n / 1024) + " KB，仪表正在重启播放器（约 5 秒黑屏后生效）";
                    } else {
                        r = "ℹ️ 仪表端已是同一份资源，无需重传";
                    }
                } catch (Throwable t) {
                    r = "❌ 上传失败：" + t.getMessage();
                }
                final String out = r;
                runOnUiThread(new Runnable() {
                    @Override public void run() { tvChgAnim.setText(out); }
                });
            }
        }).start();
    }

    /** 还原充电动画包：先确认再删仪表上的播放器与帧包（可逆，重新上传即可恢复） */
    private void restoreVideoRes() {
        if (tvChgAnim == null) return;
        new AlertDialog.Builder(this)
                .setTitle("还原充电动画包")
                .setMessage("将从仪表删除本地播放器和帧包（p7videoplay / charge.pack）。\n\n"
                        + "· 仪表会在 1 秒内退出视频模式、停掉播放器\n"
                        + "· 之后充电时不再本地播动画；若上面「充电界面」开关仍开着，"
                        + "会退回车机逐帧投屏（约 11fps）\n"
                        + "· 想完全回到原厂充电界面，把「充电界面」开关一起关掉\n"
                        + "· 想恢复本地动画，点「上传资源」重新推回即可（约 6MB）\n\n"
                        + "助手的其他功能不受影响。")
                .setPositiveButton("还原", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { doRestoreVideoRes(); }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void doRestoreVideoRes() {
        if (tvChgAnim == null) return;
        tvChgAnim.setText("正在还原…");
        new Thread(new Runnable() {
            @Override public void run() {
                String r;
                try {
                    int n = deploy.restoreChargeVideo();
                    r = n > 0 ? "✅ 已还原：删除 " + n + " 个文件，仪表已退出视频模式"
                              : "ℹ️ 仪表上本来就没有动画包，无需还原";
                } catch (Throwable t) {
                    r = "❌ 还原失败：" + t.getMessage();
                }
                final String out = r;
                runOnUiThread(new Runnable() {
                    @Override public void run() { tvChgAnim.setText(out); }
                });
            }
        }, "p7-videores-restore").start();
    }

    private LinearLayout buildDebugPage() {
        LinearLayout p = pageRoot();
        p.addView(ui.pageHeader("调试模式", pageChip()));

        // 实验功能：盲区调试
        p.addView(ui.group("实验功能"));
        LinearLayout dbg = toggle("盲区调试", "", "盲区 C 型弧线恒显示（忽略真实盲区信号），仅用于观察显示效果", prefs.debug,
                new Setter() { @Override public void set(boolean v) { prefs.debug = v; } });
        swDebug = (PillSwitch) dbg.getTag();
        p.addView(ui.pair(dbg, null));

        p.addView(ui.sep());

        // 红绿灯链路自检：别人"开了开关却没显示"时，一键把这条链路逐环查清
        p.addView(ui.group("红绿灯自检"));
        p.addView(ui.hint("按顺序查：红绿灯 App 是否安装 → 日志目录 → 最新日志多久没更新 → "
                + "日志里有没有路口数据 → 我们本地解析状态。哪一环断了一眼就能看出来。"));
        tvTlDiag = ui.hint("");
        tvTlDiag.setTextColor(ui.textSub);
        p.addView(tvTlDiag);
        p.addView(ui.buttonRow(
                ui.button("校验红绿灯", true, new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        tvTlDiag.setText("检查中…");
                        new Thread(new Runnable() {
                            @Override public void run() {
                                final String r = TlSource.diag(MainActivity.this);
                                runOnUiThread(new Runnable() {
                                    @Override public void run() { tvTlDiag.setText(r); }
                                });
                            }
                        }).start();
                    }
                })
        ));

        p.addView(ui.sep());

        // 缩放设置（对齐原版）
        p.addView(ui.group("缩放设置"));
        LinearLayout zs = ui.stepperItem("缩放车速", fmtSpeed(), new View.OnClickListener() {
            @Override public void onClick(View v) { bumpZoomSpeed(-1); }
        }, new View.OnClickListener() {
            @Override public void onClick(View v) { bumpZoomSpeed(1); }
        });
        tvZoomSpeed = (TextView) zs.getTag();
        p.addView(zs);
        p.addView(ui.hint("仪表显示车速达到该值时车模开始缩小"));
        LinearLayout zt = ui.stepperItem("缩放时长", fmtMs(), new View.OnClickListener() {
            @Override public void onClick(View v) { bumpZoomMs(-500); }
        }, new View.OnClickListener() {
            @Override public void onClick(View v) { bumpZoomMs(500); }
        });
        tvZoomMs = (TextView) zt.getTag();
        p.addView(zt);
        p.addView(ui.hint("车模缩小/放大的动画全程时长（秒）"));

        p.addView(ui.sep());

        // 日夜配色（扩展）
        p.addView(ui.group("日夜配色"));
        LinearLayout dn = new LinearLayout(this);
        dn.setOrientation(LinearLayout.HORIZONTAL);
        dn.setGravity(Gravity.CENTER_VERTICAL);
        dn.setPadding(0, ui.dp(8), 0, ui.dp(8));
        dn.addView(ui.t("配色模式", 15, ui.textTitle),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        btnDayNight = ui.button(dayNightLabel(), false, new View.OnClickListener() {
            @Override public void onClick(View v) { cycleDayNight(); }
        });
        dn.addView(btnDayNight, new LinearLayout.LayoutParams(ui.dp(150), ui.dp(44)));
        p.addView(dn);
        p.addView(ui.hint("自动 = 跟随车机深色模式；车机不在线时按本地时间 6:00–18:00 判断"));

        p.addView(ui.sep());

        // 位置微调（扩展）
        p.addView(ui.group("位置微调"));
        LinearLayout sc = ui.stepperItem("整体缩放", fmtScale(), new View.OnClickListener() {
            @Override public void onClick(View v) { bumpScale(-0.02f); }
        }, new View.OnClickListener() {
            @Override public void onClick(View v) { bumpScale(0.02f); }
        });
        tvScale = (TextView) sc.getTag();
        p.addView(sc);
        LinearLayout dx = ui.stepperItem("横向偏移", prefs.dx + " px", new View.OnClickListener() {
            @Override public void onClick(View v) { bumpOffset(-2, 0); }
        }, new View.OnClickListener() {
            @Override public void onClick(View v) { bumpOffset(2, 0); }
        });
        tvDx = (TextView) dx.getTag();
        p.addView(dx);
        LinearLayout dy = ui.stepperItem("纵向偏移", prefs.dy + " px", new View.OnClickListener() {
            @Override public void onClick(View v) { bumpOffset(0, -2); }
        }, new View.OnClickListener() {
            @Override public void onClick(View v) { bumpOffset(0, 2); }
        });
        tvDy = (TextView) dy.getTag();
        p.addView(dy);
        p.addView(ui.buttonRow(
                ui.button("重置位置", false, new View.OnClickListener() {
                    @Override public void onClick(View v) { resetLayout(); }
                }),
                ui.button("立即推送", true, new View.OnClickListener() {
                    @Override public void onClick(View v) { pushConfig(true); }
                })));

        p.addView(ui.sep());

        // 仪表状态
        p.addView(ui.group("仪表状态（status.json，2 秒刷新）"));
        tvStatus = ui.t("尚未读取", 13, ui.textSub);
        tvStatus.setBackground(ui.round(ui.logBg, 10));
        tvStatus.setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12));
        tvStatus.setLineSpacing(ui.dp(4), 1f);
        p.addView(tvStatus);
        p.addView(ui.buttonRow(
                ui.button("刷新状态", false, new View.OnClickListener() {
                    @Override public void onClick(View v) { refreshStatus(true); }
                }),
                ui.button("校验部署", false, new View.OnClickListener() {
                    @Override public void onClick(View v) { verifyDeploy(); }
                })));

        p.addView(ui.sep());
        p.addView(ui.group("数据来源"));
        p.addView(ui.note("胎压/胎温/挡位/车速/空调温度/行程/能耗/盲区：仪表本地 DBus\n"
                + "com.xiaopeng.DomainController.Cluster（etpms / vcu / eac / trip / eind）\n"
                + "电压、导航余量、到达时间：仪表总线没有，由本机数据桥写 ext.json（30s 有效期）"));

        p.addView(ui.sep());
        p.addView(ui.group("危险操作"));
        p.addView(ui.note("「彻底停止」会让仪表进程退出并还原原厂画面；之后需要一次 shell 才能重新拉起，"
                + "日常请用「停止投放」。"));
        p.addView(ui.buttonRow(
                ui.button("彻底停止进程", false, new View.OnClickListener() {
                    @Override public void onClick(View v) { hardStop(); }
                }),
                ui.button("复制重启命令", false, new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        copyToClipboard("cd /data/p7assist && sh p7boot.sh");
                        toast("重启命令已复制");
                    }
                })));
        return p;
    }

    // ---------------- 页 4：运行日志 ----------------

    private LinearLayout buildLogPage() {
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setPadding(ui.dp(26), ui.dp(19), ui.dp(26), ui.dp(16));
        p.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        p.addView(ui.pageHeader("运行日志", pageChip()));

        p.addView(ui.group("仪表 /data/p7assist/log/hud.log"));
        p.addView(ui.buttonRow(
                ui.button("刷新日志", true, new View.OnClickListener() {
                    @Override public void onClick(View v) { refreshLog(); }
                }),
                ui.button("复制日志", false, new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        copyToClipboard(tvLog == null ? "" : tvLog.getText().toString());
                        toast("日志已复制");
                    }
                })));

        ScrollView sv = new ScrollView(this);
        tvLog = ui.t("点「刷新日志」从仪表拉取", 11, ui.textTitle);
        tvLog.setTypeface(Typeface.MONOSPACE);
        tvLog.setBackground(ui.round(ui.logBg, 10));
        tvLog.setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12));
        tvLog.setLineSpacing(ui.dp(3), 1f);
        tvLog.setTextIsSelectable(true);
        sv.addView(tvLog);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        slp.topMargin = ui.dp(10);
        p.addView(sv, slp);
        return p;
    }

    // ---------------- 开关/部件 ----------------

    private interface Setter { void set(boolean v); }

    /** 相机 id 在 0~7 之间循环；改完立刻存盘，轮询下一拍就用新值。 */
    private void bumpMirrorCam(boolean left, int delta) {
        int n = 8;
        int v = left ? prefs.mirrorCamL : prefs.mirrorCamR;
        v = ((v + delta) % n + n) % n;
        if (left) prefs.mirrorCamL = v; else prefs.mirrorCamR = v;
        prefs.save(this);
        TextView tv = left ? tvMirrorCamL : tvMirrorCamR;
        if (tv != null) tv.setText("id " + v);
    }

    // ===== 取景微调 UI（四宫格鱼眼放大车尾）=====

    /** 参数种类：缩放 / 左右偏 / 上下偏。 */
    private static final int Q_ZOOM = 0, Q_PANX = 1, Q_PANY = 2;

    /** 构建「取景微调」区：总开关 + 左右各三组步进器 + 说明 + 复位。 */
    private void buildQuadCropUi(LinearLayout p) {
        p.addView(ui.group("取景微调（放大车尾，舍弃车头）"));
        LinearLayout sw = toggle("取景微调", "实时生效",
                "开：四宫格里把该侧鱼眼按下面的参数放大、往车尾偏。\n"
                        + "关：整格显示该侧完整鱼眼，不做任何偏向。",
                prefs.quadCropEnable,
                new Setter() { @Override public void set(boolean v) {
                    prefs.quadCropEnable = v;
                    MirrorSource.setQuadCropEnabled(v);
                } });
        p.addView(sw);

        // 左灯（panx 为负=窗口左移，因左后鱼眼车尾在画面左侧）
        p.addView(ui.hint("— 左灯（打左转向灯）—"));
        tvQZoomL = quadStepperRow(p, "左 · 放大倍数", 1);
        tvQPanXL = quadStepperRow(p, "左 · 左右偏向", 2);
        tvQPanYL = quadStepperRow(p, "左 · 上下偏向", 3);

        // 右灯（panx 为正=窗口右移）
        p.addView(ui.hint("— 右灯（打右转向灯）—"));
        tvQZoomR = quadStepperRow(p, "右 · 放大倍数", 4);
        tvQPanXR = quadStepperRow(p, "右 · 左右偏向", 5);
        tvQPanYR = quadStepperRow(p, "右 · 上下偏向", 6);

        p.addView(ui.buttonRow(
                ui.button("复位默认", false, new View.OnClickListener() {
                    @Override public void onClick(View v) { resetQuadCrop(); }
                })));
        p.addView(ui.hint("放大 1.0=整格原样，越大车尾越大（1.0~3.0）。"
                + "偏向 −1~+1：把取景窗往该方向挪，正=右/下，负=左/上。"
                + "左右鱼眼互为镜像，两侧偏向值通常互为相反数。改完实时生效并自动保存。"));
    }

    /**
     * 加一行步进器。slot 编码：1/2/3 = 左灯 zoom/panx/pany，4/5/6 = 右灯 zoom/panx/pany。
     * 返回数值 TextView 供回显。
     */
    private TextView quadStepperRow(LinearLayout p, final String label, final int slot) {
        final int side = slot <= 3 ? 1 : 2;
        final int kind = (slot - 1) % 3;   // 0=zoom 1=panx 2=pany
        LinearLayout row = ui.stepperItem(label, quadValueText(slot),
                new View.OnClickListener() {
                    @Override public void onClick(View v) { bumpQuad(slot, side, kind, -1); }
                },
                new View.OnClickListener() {
                    @Override public void onClick(View v) { bumpQuad(slot, side, kind, +1); }
                });
        p.addView(row);
        return (TextView) row.getTag();
    }

    /** 按 0.1 步进改一个取景参数，夹到合法区间，实时下发 MirrorSource 并存盘。 */
    private void bumpQuad(int slot, int side, int kind, int dir) {
        float step = 0.1f * dir;
        switch (kind) {
            case Q_ZOOM:
                if (side == 1) prefs.quadZoomL = clamp(prefs.quadZoomL + step, 1f, 3f);
                else prefs.quadZoomR = clamp(prefs.quadZoomR + step, 1f, 3f);
                break;
            case Q_PANX:
                if (side == 1) prefs.quadPanXL = clamp(prefs.quadPanXL + step, -1f, 1f);
                else prefs.quadPanXR = clamp(prefs.quadPanXR + step, -1f, 1f);
                break;
            case Q_PANY:
                if (side == 1) prefs.quadPanYL = clamp(prefs.quadPanYL + step, -1f, 1f);
                else prefs.quadPanYR = clamp(prefs.quadPanYR + step, -1f, 1f);
                break;
        }
        // 实时生效：当前正在推流时下一帧就按新参数裁
        MirrorSource.setQuadCrop(side,
                side == 1 ? prefs.quadZoomL : prefs.quadZoomR,
                side == 1 ? prefs.quadPanXL : prefs.quadPanXR,
                side == 1 ? prefs.quadPanYL : prefs.quadPanYR);
        prefs.save(this);
        refreshQuadViews();
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /** 复位成出厂默认（右 2.0/+0.8，左 2.0/-0.8，上下 0）。 */
    private void resetQuadCrop() {
        prefs.quadCropEnable = true;
        prefs.quadZoomL = 2.0f; prefs.quadPanXL = -0.8f; prefs.quadPanYL = 0f;
        prefs.quadZoomR = 2.0f; prefs.quadPanXR = +0.8f; prefs.quadPanYR = 0f;
        MirrorSource.setQuadCropEnabled(true);
        MirrorSource.applyFromPrefs(prefs);
        prefs.save(this);
        refreshQuadViews();
        syncProjectButtons();
    }

    private String quadValueText(int slot) {
        int side = slot <= 3 ? 1 : 2;
        int kind = (slot - 1) % 3;
        if (kind == Q_ZOOM) return String.format("%.1f×", side == 1 ? prefs.quadZoomL : prefs.quadZoomR);
        if (kind == Q_PANX) return fmtPan(side == 1 ? prefs.quadPanXL : prefs.quadPanXR);
        return fmtPan(side == 1 ? prefs.quadPanYL : prefs.quadPanYR);
    }

    private static String fmtPan(float v) {
        return (v > 0 ? "+" : "") + String.format("%.1f", v);
    }

    private void refreshQuadViews() {
        if (tvQZoomL != null) tvQZoomL.setText(quadValueText(1));
        if (tvQPanXL != null) tvQPanXL.setText(quadValueText(2));
        if (tvQPanYL != null) tvQPanYL.setText(quadValueText(3));
        if (tvQZoomR != null) tvQZoomR.setText(quadValueText(4));
        if (tvQPanXR != null) tvQPanXR.setText(quadValueText(5));
        if (tvQPanYR != null) tvQPanYR.setText(quadValueText(6));
    }

    private LinearLayout toggle(String title, String tag, String desc, boolean val, final Setter setter) {
        return ui.toggleItem(title, tag, desc, val, new PillSwitch.OnChange() {
            @Override public void onChanged(PillSwitch sw, boolean checked) {
                if (syncing) return;
                setter.set(checked);
                prefs.save(MainActivity.this);
                schedulePush(700, false);
            }
        });
    }

    /** 把底部中央区域四个开关同步到 prefs 的当前值（不触发 onChanged 回调） */
    private void syncTripSwitches() {
        boolean old = syncing;
        syncing = true;
        if (swTripDist != null) swTripDist.setCheckedSilently(prefs.tripDist);
        if (swTripTime != null) swTripTime.setCheckedSilently(prefs.tripTime);
        if (swTripSpeed != null) swTripSpeed.setCheckedSilently(prefs.tripSpeed);
        if (swTripKwh != null) swTripKwh.setCheckedSilently(prefs.tripKwh);
        syncing = old;
    }

    /** 反向联动：用户手动打开任一行程开关时，让底部歌词自动退出，保持二选一 */
    private void onTripSwitch(boolean on) {
        if (!on || !prefs.lyricBottom) return;
        prefs.lyricBottom = false;
        tripBackup = -1;
        boolean old = syncing;
        syncing = true;
        if (swLyricBottom != null) swLyricBottom.setCheckedSilently(false);
        syncing = old;
        toast("行程行已重新启用，歌词回到左上");
    }

    private PillSwitch addToggle(LinearLayout page, String title, String tag, String desc,
                                 boolean val, final Setter setter) {
        LinearLayout row = toggle(title, tag, desc, val, setter);
        page.addView(row);
        return (PillSwitch) row.getTag();
    }

    // ---------------- 行为 ----------------

    private void toast(String s) {
        // 车机底部有系统栏/导航区，默认 toast 位置经常被盖住；
        // 全部提示统一移到顶部居中，并留出状态栏高度。
        Toast t = Toast.makeText(this, s, Toast.LENGTH_SHORT);
        t.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL, 0, 96);
        t.show();
    }

    /** 投放 = 全局开关 */
    private void setProject(boolean on) {
        prefs.master = on;
        prefs.save(this);
        if (swMaster != null) swMaster.setCheckedSilently(on);
        syncProjectButtons();
        schedulePush(120, true);
        toast(on ? "已开始投放" : "已停止投放");
        if (on && prefs.bridge) PushService.start(this);
    }

    private void syncProjectButtons() {
        if (btnStart == null) return;
        btnStart.setBackground(ui.round(prefs.master ? ui.btnGreen : ui.btnDark, 12));
        btnStart.setTextColor(prefs.master ? ui.btnPriText : ui.btnDarkText);
        btnStop.setBackground(ui.round(prefs.master ? ui.btnDark : ui.btnGreen, 12));
        btnStop.setTextColor(prefs.master ? ui.btnDarkText : ui.btnPriText);
    }

    private void schedulePush(int delayMs, final boolean loud) {
        main.postDelayed(new Runnable() {
            @Override public void run() { pushConfig(loud); }
        }, delayMs);
    }

    private void readConn() {
        if (etHost == null) return;
        prefs.host = etHost.getText().toString().trim();
        try { prefs.port = Integer.parseInt(etPort.getText().toString().trim()); } catch (Throwable ignored) { }
        prefs.user = etUser.getText().toString().trim();
        prefs.pass = etPass.getText().toString();
        prefs.save(this);
        deploy.reset();
    }

    private void setChip(String text, int color) {
        for (TextView c : chips) {
            c.setText(text);
            c.setTextColor(color);
        }
    }

    private void testConnection(final boolean loud) {
        readConn();
        setChip("连接中…", ui.textChip);
        pool.execute(new Runnable() {
            @Override public void run() {
                try {
                    deploy.reset();
                    if (!deploy.ping()) throw new Exception("无响应");
                    // 顺带探测 30510 管理通道（能否免 shell 的关键）
                    String mgmt = "仅 FTP";
                    try {
                        String out = new IcRpc(prefs.host).exec("echo IC_OK");
                        if (out != null && out.contains("IC_OK")) mgmt = "管理通道可用";
                    } catch (Throwable ignored) { }
                    final String fmgmt = mgmt;
                    JSONObject st = null;
                    try { st = new JSONObject(deploy.statusRaw()); } catch (Throwable ignored) { }
                    final JSONObject fst = st;
                    main.post(new Runnable() {
                        @Override public void run() {
                            if (fst == null) {
                                setChip("已连接 · " + fmgmt, ui.tagWarn);
                                if (tvDeploy != null) tvDeploy.setText("仪表 /data/p7assist 下还没有运行中的程序\n"
                                        + "点「一键部署」即可自动完成（管理通道 " + fmgmt + "）");
                                if (tvStatus != null) tvStatus.setText("status.json 不存在：FTP 通了，但守护进程没跑。");
                                return;
                            }
                            setChip("已连接 · " + fmgmt, ui.tagGreen);
                            fillStatus(fst);
                            if (loud) toast("连接成功：" + prefs.host + "（" + fmgmt + "）");
                        }
                    });
                } catch (final Throwable t) {
                    main.post(new Runnable() {
                        @Override public void run() {
                            setChip("未连接", ui.tagRed);
                            if (loud) toast("连不上仪表：" + t.getMessage());
                        }
                    });
                }
            }
        });
    }

    private void pushConfig(final boolean loud) {
        readConn();
        final Prefs p = prefs.copy();
        pool.execute(new Runnable() {
            @Override public void run() {
                try {
                    deploy.pushConfig(p);
                    JSONObject st = null;
                    try { st = deploy.status(); } catch (Throwable ignored) { }
                    final JSONObject fst = st;
                    main.post(new Runnable() {
                        @Override public void run() {
                            if (fst != null) fillStatus(fst);
                            if (loud) toast(p.master ? "设置已推送，仪表已开始投放" : "设置已推送，仪表已停止投放");
                        }
                    });
                } catch (final Throwable t) {
                    main.post(new Runnable() {
                        @Override public void run() { toast("推送失败：" + t.getMessage()); }
                    });
                }
            }
        });
    }

    /** 一键部署：上传全部文件（二进制 + 脚本 + 配置），守护进程不在线时提示最后一步 */
    private void deployAll() {
        readConn();
        toast("开始部署（上传全部文件）…");
        pool.execute(new Runnable() {
            @Override public void run() {
                try {
                    deploy.install(new Ftp.Progress() {
                        @Override public void onProgress(final long sent, final long total) {
                            final int pct = (int) (sent * 100 / Math.max(1, total));
                            main.post(new Runnable() {
                                @Override public void run() {
                                    setChip("部署 " + pct + "%", ui.tagWarn);
                                    if (tvDeploy != null) tvDeploy.setText("正在上传全部文件… " + pct + "%");
                                }
                            });
                        }
                    });
                    main.post(new Runnable() {
                        @Override public void run() { setChip("已连接", ui.tagGreen); }
                    });
                    // 收尾要走 30510 socket + FTP，必须留在后台线程（主线程会被
                    // NetworkOnMainThreadException 拦下、被 catch 静默吞掉）。
                    // 上传后再采样一次心跳：本固件上 p7assist.new 的热替换(syscall.Exec)
                    // 实测可能把进程搞没，而看门狗只清屏不重启，不能无条件相信「将自动热升级」。
                    final boolean aliveAfter = deploy.probeAlive(5000);
                    final boolean done = aliveAfter || deploy.finalizeInstall();
                    final String diag = deploy.lastDiag;
                    main.post(new Runnable() {
                        @Override public void run() {
                            // 后台收尾已结束，此时再校验，避免两个线程同时 reset FTP 连接
                            verifyDeploy();
                            if (aliveAfter) {
                                toast("已上传，已确认仪表程序仍在运行");
                            } else if (done) {
                                toast("已自动完成部署并拉起，仪表程序已在运行");
                            } else {
                                showLastStepDialog(diag);
                            }
                        }
                    });
                } catch (final Throwable t) {
                    main.post(new Runnable() {
                        @Override public void run() {
                            setChip("部署失败", ui.tagRed);
                            toast("部署失败：" + t.getMessage());
                        }
                    });
                }
            }
        });
    }

    /** 首次安装的最后一步：只需在【仪表】的 shell 里执行一条极短命令 */
    private void showLastStepDialog(String diag) {
        final String cmd = "sh /data/p7assist/p7boot.sh";
        String msg = "自动收尾没成功，只差在【仪表】的 shell 里执行一次（之后开机自动拉起，只此一次）。\n\n"
                + "⚠ 不是车机的 adb shell！提示符是 shell@au8295_xp 的那个是【车机】，"
                + "粘进去一定会报 cd /data/p7assist: inaccessible or not found。"
                + "必须用仪表的 shell（串口 / 仪表 adb / 原接收器）。\n\n"
                + cmd + "\n\n"
                + "这条脚本会自己转正程序、赋可执行权限、启动并写开机自启，"
                + "不用再拼 chmod / mv 那一长串，也不怕粘贴时空格被吞。\n\n"
                + (diag == null || diag.length() == 0 ? "" : "自动收尾诊断（直连 + shell 中继都试过）：\n" + diag)
                + "\n校验页会显示 status.json / boot.log 两项，齐全且仪表盘出现实时数据才算真跑起来。\n"
                + "之后开关、升级都在本 App 里点，不用再进 shell。";
        new AlertDialog.Builder(this)
                .setTitle("文件已上传，还差最后一步")
                .setMessage(msg)
                .setPositiveButton("复制这条命令", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        copyToClipboard(cmd);
                        toast("命令已复制，粘到仪表的 shell（不是车机）");
                    }
                })
                .setNeutralButton("重试自动完成", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { retryFinalize(); }
                })
                .setNegativeButton("知道了", null)
                .show();
    }

    /** 不重新上传，只重跑自动收尾（后台线程） */
    private void retryFinalize() {
        toast("正在重试自动收尾…");
        pool.execute(new Runnable() {
            @Override public void run() {
                final boolean ok = deploy.finalizeInstall();
                final String diag = deploy.lastDiag;
                main.post(new Runnable() {
                    @Override public void run() {
                        if (ok) {
                            toast("已自动完成部署并拉起，仪表程序已在运行");
                            verifyDeploy();
                        } else {
                            showLastStepDialog(diag);
                        }
                    }
                });
            }
        });
    }

    /** 读回仪表上的二进制比对 md5 */
    private void verifyDeploy() {
        readConn();
        if (tvDeploy != null) tvDeploy.setText("正在校验远端程序…");
        pool.execute(new Runnable() {
            @Override public void run() {
                final StringBuilder sb = new StringBuilder();
                try {
                    String local = deploy.localBinaryMd5();
                    String remote = deploy.remoteBinaryMd5();
                    long size = deploy.remoteBinarySize();
                    boolean same = local.equals(remote);
                    JSONObject st = null;
                    try { st = deploy.status(); } catch (Throwable ignored) { }
                    // 用「status.json 是否持续被改写」判断存活，不用仪表与车机无法对齐的绝对时钟
                    final boolean alive = deploy.probeAlive(4000);
                    final boolean fontOk = deploy.remoteFontOk();
                    final long statusSize = deploy.remoteStatusSize();
                    final long bootLogSize = deploy.remoteBootLogSize();
                    sb.append(same ? "● 仪表程序与内置版本一致" : "○ 仪表程序与内置版本不一致").append("\n");
                    sb.append("本机内置  ").append(local).append("\n");
                    sb.append("仪表文件  ").append(remote).append("  ").append(size / 1024).append(" KB\n");
                    sb.append("中文字体  ").append(fontOk ? "● 已部署" : "○ 缺失（歌词会显示方框）").append("\n");
                    // 三条成功标准：status.json 有内容 + boot.log 有内容 + 仪表盘出现实时数据
                    sb.append("运行状态  ").append(statusSize > 0 ? "● status.json " + statusSize + " B" : "○ status.json 不存在").append("\n");
                    sb.append("启动脚本  ").append(bootLogSize > 0 ? "● boot.log " + bootLogSize + " B" : "○ boot.log 无（p7boot.sh 没跑过）").append("\n");
                    if (!same) sb.append("→ 点「一键部署」重新部署\n");
                    if (!fontOk) sb.append("→ 点「一键部署」补传字体\n");
                    sb.append("守护进程  ").append(alive ? "在线（v" + (st != null ? st.optString("version", "?") : "?") + "）" : "未运行，需首次安装");
                    sb.append(alive && statusSize > 0
                            ? "\n● 判定：已真正运行，最后请肉眼确认仪表出现实时数据（电压/档位/音量）"
                            : "\n○ 判定：文件躺在里面，程序没跑起来（不算部署成功）");
                    main.post(new Runnable() {
                        @Override public void run() {
                            if (tvDeploy != null) tvDeploy.setText(sb.toString());
                        }
                    });
                } catch (final Throwable t) {
                    main.post(new Runnable() {
                        @Override public void run() {
                            if (tvDeploy != null) tvDeploy.setText("校验失败：" + t.getMessage()
                                    + "\n（仪表上还没有 p7assist 文件时属于正常）");
                        }
                    });
                }
            }
        });
    }

    /** 卸载前的二次确认：不可逆操作，必须让用户看清后果再点 */
    private void confirmUninstall() {
        new AlertDialog.Builder(this)
                .setTitle("卸载并还原仪表？")
                .setMessage("会依次做四件事：\n"
                        + "1. 停掉车机侧盲区探针，避免边卸边推\n"
                        + "2. 停止仪表端程序（胎压 / 歌词 / 盲区 / 行程立刻消失）\n"
                        + "3. 移除开机自启，仪表重启后不会再拉起\n"
                        + "4. 删除仪表 /data/p7assist（含二进制、字体、日志）\n\n"
                        + "仪表会立刻回到原厂显示，原厂功能完全不受影响。\n"
                        + "此操作不可撤销，想恢复需要重新点「一键部署」。")
                .setPositiveButton("确认卸载", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { doUninstall(); }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void doUninstall() {
        toast("正在卸载并还原仪表…");
        new Thread(new Runnable() {
            @Override public void run() {
                // 1) 先关车机侧：数据桥开关 + 常驻探针，避免卸载过程中还在推数据
                prefs.bridge = false;
                prefs.master = false;
                prefs.save(MainActivity.this);
                LocalAdb.stopProbes(MainActivity.this);
                // 2) 仪表端：杀看门狗 → 杀 daemon → 移除自启 → 删目录
                StringBuilder diag = new StringBuilder();
                boolean ok = false;
                try {
                    ok = deploy.uninstall(diag);
                } catch (Throwable t) {
                    diag.append("异常: ").append(t).append("\n");
                }
                final boolean fok = ok;
                final String fdiag = diag.toString();
                main.post(new Runnable() {
                    @Override public void run() {
                        if (swBridge != null) swBridge.setCheckedSilently(false);
                        if (swMaster != null) swMaster.setCheckedSilently(false);
                        if (fok) {
                            tvDeploy.setText("● 已卸载\n"
                                    + "仪表已还原为出厂显示，原厂功能不受影响。\n"
                                    + "想恢复请点「一键部署」。");
                            toast("已还原仪表");
                        } else {
                            tvDeploy.setText("○ 卸载未完成\n" + fdiag);
                            toast("卸载未完成，请看下方提示");
                        }
                    }
                });
            }
        }).start();
    }

    private void refreshStatus(final boolean loud) {
        pool.execute(new Runnable() {
            @Override public void run() {
                try {
                    final JSONObject st = deploy.status();
                    main.post(new Runnable() {
                        @Override public void run() {
                            fillStatus(st);
                            if (loud) toast("状态已刷新");
                        }
                    });
                } catch (final Throwable t) {
                    main.post(new Runnable() {
                        @Override public void run() {
                            if (tvStatus != null) {
                                tvStatus.setText("读取失败：" + t.getMessage() + "\n先到「连接仪表」点一次连接。");
                            }
                            if (loud) toast("读取失败");
                        }
                    });
                }
            }
        });
    }

    private void hardStop() {
        pool.execute(new Runnable() {
            @Override public void run() {
                try {
                    deploy.ftp().put("p7assist/stop", new byte[]{'1'});
                    main.post(new Runnable() {
                        @Override public void run() {
                            toast("已发送退出指令，仪表已还原原厂画面");
                            setChip("已连接 · 未运行", ui.tagWarn);
                        }
                    });
                } catch (final Throwable t) {
                    main.post(new Runnable() {
                        @Override public void run() { toast("发送失败：" + t.getMessage()); }
                    });
                }
            }
        });
    }

    private void refreshLog() {
        pool.execute(new Runnable() {
            @Override public void run() {
                final String s = deploy.logTail(12000);
                main.post(new Runnable() {
                    @Override public void run() {
                        if (tvLog != null) {
                            tvLog.setText(s == null ? "读取失败：先到「连接仪表」测试连接" : s);
                        }
                    }
                });
            }
        });
    }

    private void fillStatus(JSONObject st) {
        if (st == null) return;
        boolean alive = deploy.daemonAlive(st);
        boolean showing = st.optBoolean("enabled", false) && alive;
        setChip(alive ? (showing ? "已连接 · 投放中" : "已连接 · 已停止") : "已连接 · 未运行",
                alive ? (showing ? ui.tagGreen : ui.tagWarn) : ui.tagWarn);

        StringBuilder sb = new StringBuilder();
        sb.append(alive ? "● 守护进程在线" : "○ 守护进程未运行")
          .append("    版本 ").append(st.optString("version", "?"))
          .append("    投放 ").append(st.optBoolean("enabled", false) ? "开" : "关")
          .append("    屏幕 ").append(st.optString("screen", "?")).append("\n");
        sb.append("总线 ").append(st.optBoolean("bus", false) ? "OK" : "断开")
          .append("    模式 ").append(st.optString("mode", "?"))
          .append("    挡位 ").append(st.optInt("gear", -1))
          .append("    车速 ").append(Math.round(st.optDouble("speed", 0))).append(" km/h\n");
        JSONArray tp = st.optJSONArray("tpms");
        if (tp != null && tp.length() >= 4) {
            sb.append("胎压 ");
            for (int i = 0; i < 4; i++) {
                double v = tp.optDouble(i, -1);
                sb.append(v > 0 ? String.format(java.util.Locale.ROOT, "%.2f", v) : "--").append(i < 3 ? " / " : " bar\n");
            }
        }
        JSONArray tt = st.optJSONArray("temp");
        if (tt != null && tt.length() >= 4) {
            sb.append("胎温 ");
            for (int i = 0; i < 4; i++) {
                double v = tt.optDouble(i, -999);
                sb.append(v > -100 ? String.format(java.util.Locale.ROOT, "%.0f", v) : "--").append(i < 3 ? " / " : " ℃\n");
            }
        }
        double volt = st.optDouble("volt", -1);
        double cabin = st.optDouble("cabin", -999);
        sb.append("电压 ").append(volt > 0 ? String.format(java.util.Locale.ROOT, "%.1fV", volt) : "--")
          .append("    车内 ").append(cabin > -100 ? String.format(java.util.Locale.ROOT, "%.0f℃", cabin) : "--")
          .append("    导航 ").append(navAge(st.optLong("navAgeMs", -1))).append("\n");
        sb.append("行程 ").append(fmt(st.optDouble("tripKm", -1), 1)).append(" km / ")
          .append(fmt(st.optDouble("tripMin", -1), 0)).append(" min / ")
          .append(fmt(st.optDouble("tripAvg", -1), 0)).append(" km/h / ")
          .append(fmt(st.optDouble("tripKwh", -1), 1)).append(" kWh\n");
        sb.append("帧 ").append(st.optLong("frames", 0))
          .append("    ext ").append(st.optLong("extAgeMs", -1)).append(" ms")
          .append("    pid ").append(st.optInt("pid", 0));
        if (tvStatus != null) tvStatus.setText(sb.toString());

        // 注意：这里不能把 status.enabled 回写到 prefs.master。
        // master 是车机端本地开关，enabled 是它推送过去的镜像；回写会引入竞态——
        // 用户刚点开开关、推送尚未完成时切页刷新，读到旧 enabled=false 就把开关又覆盖成"关"，
        // 造成"功能生效了但按钮显示关"的 bug。状态栏的「投放 开/关」文本已独立反映真实状态。
        syncProjectButtons();
    }

    private String navAge(long ms) {
        if (ms < 0) return "无数据";
        if (ms < 60000) return (ms / 1000) + "s 前";
        return (ms / 60000) + "min 前";
    }

    private String fmt(double v, int dec) {
        if (v < 0) return "--";
        return String.format(java.util.Locale.ROOT, "%." + dec + "f", v);
    }

    // ---------------- 参数 ----------------

    private String fmtSpeed() { return String.format(java.util.Locale.ROOT, "%.0f km/h", prefs.zoomSpeed); }
    private String fmtMs() { return String.format(java.util.Locale.ROOT, "%.1f s", prefs.zoomMs / 1000f); }
    private String fmtScale() { return String.format(java.util.Locale.ROOT, "%.2f", prefs.scale); }

    private void bumpZoomSpeed(int dir) {
        prefs.zoomSpeed = Math.max(40, Math.min(90, prefs.zoomSpeed + dir * 5));
        prefs.save(this);
        if (tvZoomSpeed != null) tvZoomSpeed.setText(fmtSpeed());
        schedulePush(700, false);
    }

    private void bumpZoomMs(int delta) {
        prefs.zoomMs = Math.max(1000, Math.min(15000, prefs.zoomMs + delta));
        prefs.save(this);
        if (tvZoomMs != null) tvZoomMs.setText(fmtMs());
        schedulePush(700, false);
    }

    private void bumpScale(float d) {
        prefs.scale = Math.max(0.8f, Math.min(1.2f, Math.round((prefs.scale + d) * 100) / 100f));
        prefs.save(this);
        if (tvScale != null) tvScale.setText(fmtScale());
        schedulePush(700, false);
    }

    private void bumpOffset(int dx, int dy) {
        prefs.dx = Math.max(-200, Math.min(200, prefs.dx + dx));
        prefs.dy = Math.max(-200, Math.min(200, prefs.dy + dy));
        prefs.save(this);
        if (tvDx != null) tvDx.setText(prefs.dx + " px");
        if (tvDy != null) tvDy.setText(prefs.dy + " px");
        schedulePush(700, false);
    }

    private void resetLayout() {
        prefs.scale = 1f;
        prefs.dx = 0;
        prefs.dy = 0;
        prefs.save(this);
        if (tvScale != null) tvScale.setText(fmtScale());
        if (tvDx != null) tvDx.setText("0 px");
        if (tvDy != null) tvDy.setText("0 px");
        schedulePush(200, false);
        toast("位置已重置");
    }

    private String dayNightLabel() {
        if ("day".equals(prefs.dayNight)) return "强制日间";
        if ("night".equals(prefs.dayNight)) return "强制夜间";
        return "自动（跟随车机）";
    }

    private void cycleDayNight() {
        if ("auto".equals(prefs.dayNight)) prefs.dayNight = "day";
        else if ("day".equals(prefs.dayNight)) prefs.dayNight = "night";
        else prefs.dayNight = "auto";
        prefs.save(this);
        if (btnDayNight != null) btnDayNight.setText(dayNightLabel());
        schedulePush(300, false);
    }

    private void copyCommands() {
        copyToClipboard("sh /data/p7assist/p7boot.sh");
        toast("安装命令已复制（粘到【仪表】的 shell，不是车机 adb shell）");
    }

    private void copyToClipboard(String s) {
        try {
            ((ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE))
                    .setPrimaryClip(ClipData.newPlainText("p7", s));
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // recreate() 换昼夜配色时，旧实例的循环任务必须停掉：
        // 否则 refreshAdbAuth / 连接自检还挂在旧对象上继续跑。
        main.removeCallbacksAndMessages(null);
        pool.shutdownNow();
        deploy.reset();
    }
}
