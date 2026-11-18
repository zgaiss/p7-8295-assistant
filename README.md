# P7 仪表助手 · 源码（v2.12.2 · 充电视频版）

小鹏 P7 车机 App + 仪表端守护进程。本包是 v2.12.2 的完整源码，对应 APK：`P7仪表助手_v2.12.2_充电视频版.apk`。

---

## 一、这两个程序分别干什么

| 目录 | 语言 | 跑在哪 | 作用 |
|---|---|---|---|
| `carpad/` | Java（无 Gradle，aapt2 + javac + d8 直接打） | 车机 Android | 读车况/媒体信息，通过 FTP 把配置和歌词推给仪表；充电动画资源的部署与调试入口 |
| `instrument/` | Go（交叉编译到 linux/arm） | 仪表 i.MX6（Linux 3.0.35） | 直接写 fb1 前景层，画胎压/歌词/盲区/导航/红绿灯/充电页文字 |

两边通过 **仪表侧 FTP（172.20.1.40:2221，root/空密码，chroot 在 /data）** 和 **30510 管理通道**交换数据。

---

## 二、构建

### 1. 一键构建（推荐）

```bash
sh build-all.sh
# 产物：dist/P7仪表助手-原生版.apk
#       dist/仪表端/{p7assist,p7assistctl,p7boot.sh,p7watch.sh,install-autostart.sh,uninstall-autostart.sh}
```

脚本按主机自动选择打包方式，并在动手前先做前置检查（缺 JDK / Android SDK 会直接报错，**不会**先把 `carpad/assets/` 覆盖掉再失败）：

| 主机 `uname -s` | 打包脚本 | 签名 |
|---|---|---|
| `Darwin` | `carpad/build-mac.sh` | `tools/testkey` |
| `MINGW*` / `MSYS*` / `CYGWIN*` | `carpad/build.sh` | `carpad/p7.keystore`（缺则自动生成） |
| 其他（含 Linux） | 不自动分派，会给出手动两步的命令 | — |

流程：`go test ./...`（在主机平台跑纯逻辑用例）→ 交叉编译 `linux/arm` → 拷进 `carpad/assets/` → 打包签名。

> 构建会覆盖被跟踪的 `carpad/assets/p7assist`（以及几个自启脚本）。这是有意的——APK 里带的就是刚编出来的二进制。提交前如不想带上新二进制，`git checkout -- carpad/assets/p7assist` 还原即可。

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

流程与 `build.sh` 相同，差别只有工具路径无 `.exe` 后缀、JDK 走 `Contents/Home`，以及**签名统一用 `tools/testkey`（AOSP testkey）而不是 `carpad/p7.keystore`**。SDK 默认取 `~/Library/Android/sdk`，可用 `ANDROID_SDK_ROOT` 覆盖。

两条脚本产出的文件名都是 `P7仪表助手-原生版.apk`；对外发布时重命名为 `P7仪表助手_v<版本>_充电视频版.apk`（与本文标题、首段一致）。`dist/` 目录由脚本自动创建。

版本号写在 `build.sh` 的 `--version-code/--version-name`，**必须与 `instrument/config.go` 的 `appVersion`、`build-mac.sh` 的同名字段完全一致**（原因见第四节第 6 条）。

### 3. 只编仪表端二进制

```bash
cd instrument
CGO_ENABLED=0 GOOS=linux GOARCH=arm GOARM=7 \
  go build -trimpath -ldflags "-s -w" -o ../dist/p7assist .
```

产物 `p7assist` 需要放进 `carpad/assets/p7assist` 再打 APK——APK 启动后会自动比对仪表版本并推送，否则仪表会一直跑旧二进制。

`instrument/` 里的纯逻辑（状态推导、排版、预览渲染）与 Linux-only 代码是分开的：`daemon.go`/`fb.go`/`shot.go`/`chargepage_linux.go` 带 `//go:build linux`，其余文件在 PC 上也能编译，所以 `go test ./...` 与 `p7assist render` 可以直接在 macOS / Windows 上跑。

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

5. **电压不能取数组第一个元素**。
   `get_host_echarge_voltage` 返回 `[1, 210.8]`，第一个是标志位，真值在第二个。旧代码取 `[0]` 拿到 1，被阈值挡掉 →
   电压、功率整列显示 `--`。现在遍历取最大值。另外 `get_host_echarge_power` 方法在固件里**不存在**，功率只能 V×A 算。

6. **车机 App 会自动把仪表二进制刷回自己 assets 里的版本**。
   `PushService.checkClusterVersion()` 比对 `status.version` 与 APK 的 `versionName`，不一致就推一次。
   所以两边版本号必须完全相同，否则会无限重推。

7. **fb1 是 16bpp，颜色必须按 565 取码并抖动，不能直接用 8bit 值**。
   fb0 是 32bpp（stride 7680），fb1 是 RGB565（stride 3840）——同一块区域跨两个精度不同的平面。
   两个坑都在 `rgb565.go` 里解决：

   - **取码用最近邻，不能截断**。显示端把码值展开回 8bit 是 MSB 复制（`code<<3|code>>2`，抓帧回读同规则）。
     写 `v>>3` 等于一路向下取整，且 R/B 丢 3bit、G 只丢 2bit，误差单向偏负且三通道不等：
     实机把歌词卡片铺成原厂底色时测到 **R−7 G0 B−3**，观感是夜间整块发暗、白天发绿。
   - **平缓渐变要抖动**。夜间底图横向渐变只有 11 级振幅，而 5bit 的 R/B 步长就是 8 级，
     整条渐变只落在两个码值上，交界处是 8 级台阶（屏幕上表现为"波纹"）。
     用 8×8 Bayer 按残差比例把像素拆到相邻两码值，空间平均回到原色；残差为 0 的平坦区不抖，不出颗粒。

   改完实测：卡片与原厂底图 8px 窗口平均偏差从 ±4 级降到 **≤0.8 级**。

---

## 五、目录

仓库顶层：

```
README.md          本文
AGENTS.md          协作规则（Issue / PR / README 同步 / 自查，改动前必读）
build-all.sh       一键构建（自动适配 macOS / Windows）：测试 → 交叉编译 → 拷入 assets → 打 APK
carpad/            车机 Android App
instrument/        仪表端 Go 守护进程
tools/             addzip.py、gen_bgmap.py 与 testkey/（构建与生成必需）
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
  bgmap.go         原厂底图色值表取样：歌词卡片底色按屏幕坐标从底图取色
  bgmap_data.go    色值表数据，由 tools/gen_bgmap.py 生成，勿手改
  ...
```

---

## 六、已知限制

- fb1 单缓冲 → 10fps 下仍有约每秒 1 次撕裂，非 bug。
- 充电桩类型（直流/交流）要观察 120 秒会话峰值才下结论，前 2 分钟显示"未判定"。
- `get_host_icm_charging_duration` 返回 `[-1,0,0]`，充电时长不可用。
- 电池温度/充电口温度在部分桩上返回 0 且 `cellOK/portOK=false`。
- 仪表固件升级后属性名/枚举可能变化，需重新标定（App 内「充电数据自检 → 查看充电原始数据」可以导出原始值）。

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
