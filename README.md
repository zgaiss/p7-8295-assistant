# P7 仪表助手 · 源码（v2.14.0 · 打灯影像版）

小鹏 P7 车机 App + 仪表端守护进程。本包是 v2.14.0 的完整源码，对应 APK：`P7仪表助手_v2.14.0.apk`。

---

## 一、这两个程序分别干什么

| 目录 | 语言 | 跑在哪 | 作用 |
|---|---|---|---|
| `carpad/` | Java（无 Gradle，aapt2 + javac + d8 直接打） | 车机 Android | 读车况/媒体信息，通过 FTP 把配置和歌词推给仪表；充电动画资源的部署与调试入口 |
| `instrument/` | Go（交叉编译到 linux/arm） | 仪表 i.MX6（Linux 3.0.35） | 直接写 fb1 前景层，画胎压/歌词/盲区/导航/红绿灯/充电页文字；接收车机推来的打灯影像并在半屏显示 |

两边通过 **仪表侧 FTP（172.20.1.40:2221，root/空密码，chroot 在 /data）** 和 **30510 管理通道**交换数据。

---

## 二、构建

### 1. 一键构建（推荐）

```bash
sh build-all.sh
# 产物：dist/P7仪表助手-原生版.apk
#       dist/仪表端/{p7assist,p7assistctl,p7boot.sh,p7watch.sh,install-autostart.sh,uninstall-autostart.sh}
```

脚本按主机自动选择打包方式，并在动手前检查 JDK / Android SDK 等前置条件：

| 主机 `uname -s` | 打包脚本 | 签名 |
|---|---|---|
| `Darwin` | `carpad/build-mac.sh` | `tools/testkey` |
| `MINGW*` / `MSYS*` / `CYGWIN*` | `carpad/build.sh` | `tools/testkey` |
| 其他（含 Linux） | 不自动分派，会给出手动两步的命令 | — |

流程：主机运行 `go test ./...` 后，由打包脚本交叉编译 `linux/arm` 到构建暂存目录，将新二进制和自启脚本打进 APK，核对版本与哈希，通过后生成两端产物。不改动 Git 跟踪的 `carpad/assets/`。

### 2. 只打车机 APK

**Windows**（调用 `aapt2.exe`/`d8.bat`）：

```bash
cd carpad
sh build.sh
# 产物：../dist/P7仪表助手-原生版.apk
```

依赖同级的 `../tools/`：

```
tools/jdk/<jdk-17>/bin/{java,javac,keytool}
tools/bt/<build-tools>/    aapt2.exe d8.bat zipalign.exe apksigner.bat
tools/plat/<platform>/android.jar
tools/testkey/{testkey.pk8,testkey.x509.pem}   # AOSP testkey，车机信任
```

**macOS**：

```bash
cd carpad
sh build-mac.sh
# 产物：../dist/P7仪表助手-原生版.apk
```

流程与 `build.sh` 相同，工具路径无 `.exe` 后缀、JDK 走 `Contents/Home`。两者签名都使用 `tools/testkey`（AOSP testkey）；Windows 即使生成了 `carpad/p7.keystore`，签名也不使用它。SDK 默认取 `~/Library/Android/sdk`，可用 `ANDROID_SDK_ROOT` 覆盖。

两条脚本产出的文件名都是 `P7仪表助手-原生版.apk`；对外发布时重命名为 `P7仪表助手_v<版本>_充电视频版.apk`（与本文标题、首段一致）。`dist/` 目录由脚本自动创建。

版本号唯一来源是 `carpad/AndroidManifest.xml` 的 `android:versionName/versionCode`。两个APK脚本均先用 `tools/build_version.py` 快照manifest并强制重新编译仪表（需 Go 1.23.4，可用 GO 指定编译器），通过 `-X main.appVersion` 注入同一versionName。AAPT2只读该快照，不再保留fallback版本参数。打包覆盖旧预编译p7assist及旧脚本，签名前后都核对APK版本和内嵌仪表hash，验证通过才替换dist成品；任一步失败停止，不回退打包旧资产。仪表固定使用已有随包 ARM 二进制的 Go 1.23.4 工具链，并禁用自动工具链切换；升级 Go 前须另行验证目标内核兼容性。这不是实车兼容验证。此变化不会自动修改版本号。

### 3. 只编仪表端二进制

从仓库根目录执行：

```bash
python3 tools/build_version.py prepare --out /tmp/p7-instrument-build
```

仪表产物为 `/tmp/p7-instrument-build/instrument/p7assist`，版本来自同目录manifest快照。
不必也不应手动复制到tracked `carpad/assets`：两个APK打包入口都会重新构建并覆盖打包中的旧仪表资产。

`instrument/` 的纯逻辑测试可在主机执行；直接原始 `go build` 只适合开发，
发布构建必须经过统一helper注入版本。

### 版本构建回归
从仓库根目录执行 `python3 -m unittest discover -s tools/tests -v`。
测试使用fake Go/AAPT2验证构建协议与旧asset拒绝，不等于真实Android/ARM构建。
直接执行原始 `go build` 会报告 `dev-unversioned`，不是发布版本；
不要手动复制这种二进制或旧tracked assets进行发版。版本匹配仍由现有
`checkClusterVersion` 的versionName相等判断决定：与APK版本不等时驻车触发上传，
成功采用同版本新构建后停止重复推送。这里没有改动FTP升级安全机制。

---

## 三、充电动画：为什么用"本地播放器"而不是推帧

这是本版最大的改动，也是踩了很多坑换来的。

### 早期方案（已废弃）：车机逐帧推 JPEG

车机把动画拆帧，每帧走一次 FTP 推到仪表 `/data/p7assist/charge.frm`，仪表解码 + 缩放 + 写屏。

实测拆解：

| 环节 | 实测 |
|---|---|
| FTP 传一帧 14KB | 30~37ms |
| 仪表 JPEG 解码 960×360 | 20~34ms |
| 缩放 + 转 RGB565 | 18~28ms |
| 写屏 2.76MB | 7~46ms |

一帧 90ms 起步 → **上限 11fps**，怎么优化传输都没用。

### 现方案：帧包一次性传到仪表，本地播放

```
车机（不再参与）          仪表
                          p7videoplay（静态 ARM，2.5MB）
charge.pack ──FTP──▶        ├ 解码线程：JPEG → RGB888，双缓冲
（100 帧 960×360）          ├ 渲染线程：960×360 → 1920×720（2×）
                            ├ 文字层线程：轮询 chgoverlay.jpg，灰度当 alpha
                            └ 写屏线程：pwrite 2.76MB 到 fb1
                          p7assist daemon
                            ├ 充电时完全不碰 fb1（只每秒生成文字层）
                            └ 负责启动/停止播放器、异常恢复
```

实测 `fps=10.0 dec=13.7ms ren=16.4ms wri=6.8ms`，链路完全够用。

### 帧率与撕裂（重要）

仪表 fb1 是**单缓冲 2,764,800B**，`FBIOPAN_DISPLAY` 返回 EINVAL，写整帧时 IPU 正在扫描同一块内存 → **必然出现撕裂/闪屏**，这是硬件限制。

撕裂次数 ≈ 帧率 × 写屏耗时占单帧时长的比例：

| 帧率 | 单帧时长 | 写屏占比 | 每秒撕裂机会 |
|---|---|---|---|
| 24fps | 41.7ms | 17~43% | 4~10 次 |
| 12fps | 83.3ms | 8~22% | 1~2.6 次 |
| **10fps（当前默认）** | 100ms | 7~9% | 约 1 次 |

所以默认 **10fps**。想彻底消除撕裂，需要 fb0 三缓冲 + `FBIOPAN_DISPLAY` 翻页（对端 v1.0.41-flip 已实测 29.99fps 零丢帧），但要在**开机时**（ECluster 启动前）把 fb0 扩成 3 块再伪装回 1 块，属于高风险改动，本版未做。

帧率可以在车机上现场改：`/data/p7assist/videofps`（8~30），或 App 里「充电模式 · 测试版 → 播放器 → 8/10/12/15/20/24」。

### 帧数与时长

`charge.pack` 是 P7VP 格式（`"P7VP"` + 保留 4B + count/w/h/fps（小端 int32）+ 每帧 `len+JPEG`）。

当前 **100 帧 / 10fps / 10 秒一轮**。注意帧率和帧数要一起看：193 帧按 10fps 播要 19.3 秒，会明显拖慢。

---

## 四、几个必须知道的实现细节

1. **文字层只有左上 760×540 生效**（`p7videoplay` 的 overlay 硬限制）。
   所以标题/公里/剩余时间/数值/底行都压在这个范围内，底行最多到 y=534；数据块用 `chargeVyOff = 80` 下移做垂直居中。
   警示条原本按 1920 居中（`x=750`），在 overlay 里只剩一条边——已改成左对齐、宽度上限 700。

2. **播放器退出会破坏 fb1 的 overlay 注册**。
   它会把 `mode` 写回 `240x320`、`virtual_size` 写回 `240,960`；之后几何"看起来正常"，`ensureMode()` 会直接返回，
   但合成通道已经没注册——**像素在写、屏幕不显示**。必须 `setMode()`（强制）重新注册，见 `daemon.go` 的 `recoverFG()`。

3. **播放器动过 fb1 就必须重开色键**。
   它把色键关成"不透明整层"，如果 daemon 不重开（纯黑=透明），我们的黑色会变成不透明黑把原厂仪表面整个盖住。
   用 `videoTouched` 标志兜住：只要播放器跑过，退出时一律 `setMode + openFB + setFGAttrs + layerOn + clearAll`。

4. **热升级会留僵尸**。
   升级走 `syscall.Exec`（pid 不变、镜像替换），上一代等播放器的 goroutine 消失，播放器退出后没人 `waitpid`。
   启动时用 `reapZombies()`（`Wait4(WNOHANG)`）回收。

5. **视频模式的进入/退出要"进快出慢 + 会话冷却"**。
   控制视频模式的 `chg.on` 用的是 `chgOnDebounce`（`charge.go`），**非对称**：进 2 轮（≈2s）、出 10 轮（≈10s），且双路 DBus 都读不到时保持上次值，不推断成"未充电"。
   原因：误退一次要付 `stopVideo + recoverFG` 近 6 秒渲染阻塞 + `clearAll` 黑闪，误保持只是画面多留几秒，代价不对称。
   另外 `videoMode` 是跨轮持久的会话状态（`videoOn` + 5s 冷却），不是每帧重算的临时布尔量；视频模式下 `daemon.go` 显式不调用 `applyOn`，绝不碰 fb1，避免关层分支把播放器画面抹掉。
   `tAnim`（overlay 警示条轮播）在视频模式的 `continue` 之前单独推进，播放期间不会冻结。

6. **电压不能取数组第一个元素**。
   `get_host_echarge_voltage` 返回 `[1, 210.8]`，第一个是标志位，真值在第二个。旧代码取 `[0]` 拿到 1，被阈值挡掉 →
   电压、功率整列显示 `--`。现在遍历取最大值。另外 `get_host_echarge_power` 方法在固件里**不存在**，功率只能 V×A 算。

7. **车机 App 会自动把仪表二进制刷回自己 assets 里的版本**。
   `PushService.checkClusterVersion()` 比对 `status.version` 与 APK 的 `versionName`，不一致就推一次。
   所以两边版本号必须完全相同，否则会无限重推。

8. **fb1 是 16bpp，颜色必须按 565 取码并抖动，不能直接用 8bit 值**。
   fb0 是 32bpp（stride 7680），fb1 是 RGB565（stride 3840）——同一块区域跨两个精度不同的平面。
   两个坑都在 `rgb565.go` 里解决：

   - **取码用最近邻，不能截断**。显示端把码值展开回 8bit 是 MSB 复制（`code<<3|code>>2`，抓帧回读同规则）。
     写 `v>>3` 等于一路向下取整，且 R/B 丢 3bit、G 只丢 2bit，误差单向偏负且三通道不等：
     实机把歌词卡片铺成原厂底色时测到 **R−7 G0 B−3**，观感是夜间整块发暗、白天发绿。
   - **平缓渐变要抖动**。夜间底图横向渐变只有 11 级振幅，而 5bit 的 R/B 步长就是 8 级，
     整条渐变只落在两个码值上，交界处是 8 级台阶（屏幕上表现为"波纹"）。
     用 8×8 Bayer 按残差比例把像素拆到相邻两码值，空间平均回到原色；残差为 0 的平坦区不抖，不出颗粒。

   改完实测：卡片与原厂底图 8px 窗口平均偏差从 ±4 级降到 **≤0.8 级**。

9. **打灯盲区影像是"单写者"模型，这是不花屏的全部原因**。
   影像区固定 960×540：打左灯占左半屏 `x 0–960`、打右灯占右半屏 `x 960–1920`，纵向 `y 60–600`
   （顶部让出档位条、底部让出电量/续航/时间，960:540 严格 16:9 不拉伸）。

   车机把画面**预先缩放到 960×540 并转成 RGB565**，zstd 压缩后经 TCP（默认 `:9002`）推过来；
   仪表只做 `解压 → 整行 memmove 进 fb1`，零缩放、零像素格式转换。
   反过来（JPEG/H.264）都不可行：仪表 JPEG 软解实测约 20M 像素/s，960×540 一帧 26ms，
   只够 6~10fps 还要吃满一个核；链路又只有 92Mbps，裸 565 跑 15fps 需要 15MB/s。

   并发模型是三条硬规则，缺一条就会花屏：
   - **网络线程从不碰 fb1**，只负责收帧、解压、放进深度为 1 的槽（保新丢旧）；
   - **主循环是 fb1 的唯一写者**，每轮从槽里取最新帧写入影像矩形；
   - 上屏时用 `rect.minus` 把影像矩形从脏矩形里**减掉**，拆成最多 4 块分别 blit。
     脏矩形可能横跨影像区（左右两侧同时变化时 `prev.union(nd)` 就是整屏），
     不减就会用 canvas 里的透明像素把影像盖成黑块。

   换侧/撤区时，主循环先把旧矩形 `clearBox` 并把旧矩形并进 `prev` 强制重绘，
   否则会留下上一侧的画面残影。撤区触发条件：收到 `side=0`、800ms 无新帧、
   TCP 断开、倒挡整块隐藏、充电页接管、屏幕关闭。

   **ARMv7 的 64 位原子必须对齐**：`atomic.Load64` 在 32 位 ARM 上要求地址 8 字节对齐，
   结构体里的裸 `int64` 字段不保证（Go 文档明确由调用方负责）。
   第一版就用裸 `int64` 存收帧时间戳，实机启动即 `unaligned 64-bit atomic operation` panic。
   现在一律用 `atomic.Int64` / `atomic.Int32`（它们的 `align64` 标记让编译器补齐对齐）。

10. **本地 adb 首次授权只弹一次框**。
    adb 协议里 `AUTH(3)` 是"我有新钥匙，请决定信不信"，只能上报一次。
    `AdbClient.connect()` 先用签名试；签名被拒才上报公钥触发「允许 USB 调试」弹框。
    用户点允许后，adbd 会在**同一条连接**上再发一帧 TOKEN 要求重签（证明持有私钥），
    这帧必须回 `AUTH(2)` 签名——若又回 `AUTH(3)`，车机框架会当成新的密钥请求再弹一次框。
    判定状态因此是"公钥是否已上报"，不是"这是第几帧 TOKEN"。
    公钥上报有 60s 进程级冷却（点「取消」后重连不重复弹框），点「重新授权」会清掉冷却。
    等待期间不重发公钥心跳；EOF 立即标记死连接并抛错重连，不再空转到超时。

   配置在 `config.json` 的 `video` 段：`{"enable": true, "port": 9002}`。
   **默认关闭**，且字段缺失按关闭处理——影像会盖住半屏，不能像 `popupHide` 那样"缺失即开"。

   车机端取哪一路视野（`carpad/.../AvmMode.java` + `MirrorSource.java`）：
   - 车机把 AVM 合成画面当**一路虚拟相机**（cameraId=0 / `video51`）暴露，不是四路独立物理相机；
     打灯要的侧后广角靠切 `display-mode=8`（2×2 四宫格）再裁象限拿到。
   - **切不到鱼眼有两层根因（V5.10.0.8814 真机实测，对照能出鱼眼的 P7打灯转向辅助 v1.46）**，
     缺一不可：
     1. **只写裸 `display-mode` —— 千万别补配套字段**：v1.46 下发原文就是
        `params =display-mode=8`，紧接着 `XpCameraCmdController: setAvmControlMode()` 被触发、
        打印 `displaymode:8, Overlaywkst:-1,...`（配套全 -1=未设），随后 connect camera 0
        出四路鱼眼。反之补齐 `overlay-workst/out-mode/transbody` 等字段时，controller 的
        `setAvmControlMode()` 根本不被调用，视角停在三分屏——**"参数不完整被丢弃"是错判，
        多写字段才是毒药**。`AvmMode` 现刻意只写裸字段，普通 `set` 优先、`earlyset` 兜底。
     2. **下发与取流必须同源（cameraId 一致）**：`display-mode=8` 只作用于 cameraId=0
        那一路流（真机 logcat `setCmdControlParameters ... cameraId =0`）。若打灯取流打开的是
        cameraId=1，四宫格根本不在这路上，表现为「下发成功但画面不变」。原仓库同样是
        「下发 0 + openCamera ids[0]=video51」，且注明 ids[1]/video52 画面会缺一块。现在
        `mirrorAvm` 打开时强制用 camera 0 取流，避免左右灯分设 0/1 导致右灯取错路。
     下发成功即按四宫格裁（左灯左下格、右灯右下格），失败退回三分屏侧栏 ROI。
     排查手法：`adb logcat` 里搜 `XpCameraCmdController`——有 `setAvmControlMode()` 就是
     controller 真生效了；只有 `CameraService: setCmdControlParameters` 而没这行，就是
     下发的字段让 controller 拒绝了。
     调试口：`am broadcast -f 0x01000000 -a com.xiaopeng.clusterassist.AVM --ei mode 8
     --ez dump true`（`-f` 是前台广播标志，否则被后台执行限制拦掉）。
   - **取景偏向车尾（APP 内可调）**：四宫格每格默认整格=该侧完整鱼眼。真机调定「放大 2 倍
     突出车尾、舍弃车头」：右灯窗口右移 `panx=+0.8`、左灯因鱼眼镜像取 `-0.8`，各存一套不共用。
     **调节入口**：APP「显示」页 →「取景微调（放大车尾，舍弃车头）」区：
       - 总开关：开=按参数微调，关=整格显示完整鱼眼。
       - 左右各三组步进器（放大倍数 1.0~3.0 / 左右偏向 −1~+1 / 上下偏向 −1~+1），
         点 `−`/`+` 每次 0.1，**打灯状态下实时生效下一帧就变**，并自动存盘（改完不用装机）。
       - 「复位默认」按钮恢复出厂值（右 2.0/+0.8、左 2.0/−0.8）。
       参数落在 SharedPreferences 的 `quad*` 键，进程重启后打灯时自动恢复。
     **adb 调试口**（等价、无需打开 APP）：`am broadcast -f 0x01000000 -a
     com.xiaopeng.clusterassist.AVM --ei side 2 --ef zoom 2.0 --ef panx 0.8 --ef pany 0`
     （`side` 0=左右都设/1=左/2=右；`--ef zoom 0` 复位整格）。注意广播只改本次进程内存、
     不写盘，重启后以 APP 存的值为准。
   - 参数对象的 `getAvmDisplayMode()`/`flatten()` 在本车抛异常，读回只能直读内部
     `LinkedHashMap mMap` 的 `display-mode` 键（`AvmMode.current()` 已按 getter→flatten→mMap 三级兜底）。

---

### 组件更新核心（开发草稿，未启用）
`tools/updatecore` 提供独立的签名清单、组件级 hash 增量计划、受限下载、
接收端回验和持久 known-good/pending 事务测试。涵盖仪表/播放器/动画/字体，
并区分 Android shell 探针与 APK/恢复根引导通道。
**没有接通生产 OTA；生产激活始终禁用。**
新增仪表/boot/watch 的 update-v1 命名空间防误覆盖门禁，但未自动创建标记，
未改 Java FTP 部署器，不能保证旧客户端隔离。不得把暂存缓存交给旧 `.new` 路径。
详细安全门槛、接口和离线故障注入范围见 [更新核心说明](tools/updatecore/README.md)。

## 五、目录

仓库顶层：

```
README.md          本文
AGENTS.md          协作规则（Issue / PR / README 同步 / 自查，改动前必读）
build-all.sh       一键构建（自动适配 macOS / Windows）：测试 → 交叉编译 → 拷入 assets → 打 APK
carpad/            车机 Android App
instrument/        仪表端 Go 守护进程
tools/             addzip.py、gen_bgmap.py、仪表诊断脚本（icrpc/icrpc_put/probe_card）与 testkey/
.gitignore         忽略 dist/、carpad/build/、字体、工具链
```

主要源码：

```
carpad/
  src/com/xiaopeng/clusterassist/
    MainActivity.java      设置界面（含「充电模式 · 测试版」）
    PushService.java       常驻推送：ext.json/配置/充电资源/投帧兜底
    Deploy.java            FTP 部署与状态读取
    ChargeAnim.java        逐帧投屏（仪表缺播放器时的回落通道）
    Ftp.java IcRpc.java IcRelay.java    传输层
  assets/
    p7assist               仪表端二进制（构建后替换）
    p7videoplay            视频播放器（第三方，见第七节）
    charge.pack            充电动画帧包（P7VP）
    chargeanm/*.jpg        逐帧投屏兜底帧
    Alibaba-PuHuiTi-Regular.ttf   中文字体
  res/ AndroidManifest.xml build.sh build-mac.sh

instrument/
  daemon.go        主循环（fb1 绘制、充电让出、视频模式切换）
  videoplay.go     播放器进程管理、僵尸回收、帧率配置
  chgoverlay.go    文字层生成（760×540 灰度 JPEG）
  chargeframe.go   逐帧投屏接收（回落通道）
  chargepage.go    充电页绘制/排版/状态判定
  chargepage_linux.go  充电页运行时采集（依赖 box，仅 Linux）
  chargewarn.go    充电警示文案表
  canvas.go fb.go  framebuffer 与绘制
  rgb565.go        RGB565 最近邻取码与 8×8 Bayer 抖动（见第四节第 7 条）
  mirror.go        打灯影像：帧头协议、影像区几何（见第四节第 9 条）
  mirror_srv.go    打灯影像：TCP 收帧、zstd 解压、帧槽交接
  rectutil.go      矩形减法（把影像区从脏矩形里裁掉）
  bgmap.go         原厂底图色值表取样：歌词卡片底色按屏幕坐标从底图取色
  bgmap_data.go    色值表数据，由 tools/gen_bgmap.py 生成，勿手改
  ...
```

---

独立实验模块：`tools/updatecore/`（含离车CLI、签名/事务/恢复测试，不接生产激活）。

## 六、已知限制

- 组件更新核心仍为默认关闭实验实现，未接通生产OTA；旧客户端隔离、真实健康和断电验证是硬门槛。

- fb1 单缓冲 → 10fps 下仍有约每秒 1 次撕裂，非 bug。
- 充电桩类型（直流/交流）要观察 120 秒会话峰值才下结论，前 2 分钟显示"未判定"。
- `get_host_icm_charging_duration` 返回 `[-1,0,0]`，充电时长不可用。
- 电池温度/充电口温度在部分桩上返回 0 且 `cellOK/portOK=false`。
- 仪表固件升级后属性名/枚举可能变化，需重新标定（App 内「充电数据自检 → 查看充电原始数据」可以导出原始值）。
- 打灯影像一期只到约 15fps（受主循环 50ms 节拍与 zstd 软解限制），二期换 `/dev/mxc_vpu` 硬解才能上 30fps。
- 影像功能需要车机端主动推流；车机没推时仪表侧完全静默，`video.enable=true` 也不会多占 CPU。

### 仪表端部署约束（真机踩出来的，别绕）

1. **ftpd 不支持 `SITE CHMOD`**，新建文件一律 `644`，只有**覆盖已存在文件**才保留原权限。
   所以部署新二进制必须走 `p7assist.new` + 热升级（`syscall.Exec` 原地替换，PID 不变），
   **不要 `STOR` 覆盖 `p7assist` 本体** —— 会掉执行位，重启后 `p7boot.sh` 起不来。
   `p7boot.sh` 每次开机自己 `chmod 755`，是唯一能自愈的兜底。
2. **30510 通道在仪表重启窗口期"连得上但零响应"**，不是通道坏了。车机 `adb server` 也会
   卡成 `device offline`，命令根本没发到车上。判断通道死了之前先 `adb kill-server` 重来一遍。
3. **影像帧头第 5 字节是 codec**（`0=raw / 1=zstd`）。写错不会报错，服务端走 raw 分支长度
   校验失败后**静默丢帧**，表现是连接 ESTABLISHED、Send-Q 一直积压、画面不出、日志空白。
4. 车机 → 仪表链路实测 **11.01 MB/s（92 Mbps）**，写屏 **175 MB/s**，JPEG 软解约 **20M 像素/s**，
   `p7assist` 自身已占 50% 单核。这四条是尺寸与编码方式的硬边界，改方案前先拿它们对一遍。

---

## 七、第三方与安全

- `assets/p7videoplay` 是**第三方交付的静态 ARM 播放器**（P7 充电动画投屏方案 v1.0.35 配套），本仓库不包含其源码；
  对应源码在对方交付包的 `source/players/p7videoplay.c`（依赖 zig + libjpeg-turbo 构建）。
- `carpad/p7.keystore` 是**签名私钥**（口令 `p7p7p7`）。外传后别人可以签出同名包覆盖安装，自行决定是否保留。

  > ⚠️ 该私钥目前仍随仓库分发，与 `AGENTS.md` 第 10 节「签名材料不入库」冲突，清理计划见 Issue #3。在清理完成前：**不要用这把私钥签任何要对外分发的包**。注意两个构建脚本签名来源不同——`build.sh`（Windows）复用同目录的 `p7.keystore`，`build-mac.sh` 用的是 `tools/testkey`（AOSP testkey）。

- 充电界面是**非官方功能**，会整屏接管仪表。App 内首次开启有免责声明，请勿用该界面判断能否拔枪/离车，以车机和充电桩为准。

---

## 八、参与开发

动手之前请先读 **[`AGENTS.md`](AGENTS.md)**，那是本仓库的强制协作规则。要点：

1. **先建 Issue**，说清背景、目标、方案要点、影响面、验收标准
2. 从最新 `main` 拉分支（`feat/` `fix/` `chore/` `docs/` `refactor/`）
3. 提 PR，正文写 `Closes #N`
4. 自查自己的 diff，不合理的地方就地修正
5. **同一个 PR 内更新本文档**（构建方式、目录、版本号、行为变化都要同步）

`main` 已开启强制 PR 规则，直接推送会被拒绝：

```
remote: error: GH013: Repository rule violations found for refs/heads/main.
remote: - Changes must be made through a pull request.
```

提交身份必须是 `zgaiss <187540906+zgaiss@users.noreply.github.com>`，不要带入个人或公司邮箱。

---

## 九、仪表端诊断脚本

`tools/` 下的三个脚本用于**只能在仪表上取证**的场景（底图取色、RGB565 偏色、撕裂、帧率），走 30510 管理通道，不需要登录 shell、不需要 root：

| 脚本 | 用途 |
|---|---|
| `icrpc.py` | 下发一条 shell 命令并解码回包（30510 帧头 + protobuf） |
| `icrpc_put.py` | 在其上做 base64 分块上传：本机连不上仪表 FTP，而 30510 只提供 shell、没有文件传输 opcode，传文件只能这么走 |
| `probe_card.sh` | 抓一帧（fb0+fb1 合成）拉回本机，打印歌词卡片矩形内的采样色 |

三个脚本只连 `IC_HOST:IC_PORT`，默认 `127.0.0.1:9000`（即下面的中继端口），脚本里不写仪表地址。

### 1. 先起中继

Mac 直连仪表 30510 会被直接关连接，必须经车机转发。本固件的 `/system/bin/busybox` 不可用（`IcRelay.java` 依赖它，所以 App 里的中继在这台车上是坏的），改用 toybox `nc`——它收到连接后会把后面的 COMMAND 当子进程跑，正好用来做转发：

```bash
adb shell "setsid nc -s 127.0.0.1 -L -p 9000 nc 172.20.1.40 30510 >/dev/null 2>&1 &"
adb forward tcp:9000 tcp:9000
```

`-L` 不能和 `-l` 同用（会报 `No 'L' with 'l'`）；`setsid` + `&` 是为了让监听进程不随 adb shell 退出。

用完立刻收掉，不要把这个入口留在车上：

```bash
adb shell 'P=9000; pkill -f "nc -s 127.0.0.1 -L -p $P"'   # 变量拼接，避免这条命令把自己也匹配掉
adb forward --remove tcp:9000
```

### 2. 用法

```bash
# 只读命令
python3 tools/icrpc.py 'uname -r; ls -l /data/p7assist'

# 上传：先写 .part，md5 校验通过才 mv 落位，热升级不会读到半个二进制
python3 tools/icrpc_put.py dist/p7assist /data/p7assist/p7assist.new

# 抓帧看歌词卡片底色（需要本机 python3 + Pillow，产物在 dist/probe/s.png）
sh tools/probe_card.sh
```

`probe_card.sh` 抓的是合成帧，实测要 ≥8 秒，脚本里留了 9 秒；采样矩形默认取当前歌词位（`x 1263~1821`、`y 459~599`），换版式后用 `CARD_X0/CARD_X1/CARD_Y0/CARD_Y1` 覆盖。夜间实机一次输出：

```
卡片矩形 (1263,459)-(1821,599)  10 点平均 #0C0E11 (L=13.8)
```
