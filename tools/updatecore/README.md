# 组件更新核心（离线草稿，生产激活关闭）

基于 main da511f213914bbb7405cfb29e535efc40da11ce4。未混入搁置的 #9。
本模块独立于仪表与 APK 构建，无任何生产调用点，不操作车辆、不发布。
它实现可接入的验签、受限下载、接收端缓存回验及持久事务核心；
**它不是已经接通的车机 OTA，也不保证真实断电恢复**。

## 三档边界

| 组件 | 更新方式 | 本轮状态 |
|---|---|---|
| charge.pack、charge.bin、外置字体 | 数据代次原子换代 | 注册/规划/缓存/事务覆盖；实际格式/schema/尺寸限额校验未接，激活关闭 |
| p7assist、p7videoplay | 独立进程受控重启 | 同一仪表完整 bundle，恢复与健康接入未完成，激活关闭 |
| p7bsd.jar、p7volt.jar | Android shell 高权限进程受控重启 | 已注册但 Android 接收器兼容尚未实现，Plan 也拒绝；不与仪表冒充跨机原子 |
| APK、boot/watch/ctl | 系统安装器或恢复根引导升级 | 禁止进入常规热更新计划，不能远程任意脚本/dex |

组件 ID 与目标只来自编译期白名单；不能由远程清单新增文件路径或命令。
扩展新组件要审查并更新注册表及对应格式、权限、兼容、健康验证。
嵌入 Go 的字体、布局与行为代码仍随仪表程序更新；Android 原生代码仍需安装 APK。
首版是按内容 hash 跳过未变化组件的“组件级增量”，不是二进制差分。

## 已实现 API

- Verify：固定 RSA>=2048 公钥验证原始清单字节，64KiB 清单限额，拒绝重复/未知/大小写别名字段、外仓库/非版本化 URL、任意路径、错误 ABI/protocol。
- VerifiedManifest/VerifiedArtifact：私有字段与无效零值，外部不能用普通结构体绕过验签；Metadata 仅返回拷贝。不防同包恶意代码或 unsafe。
- Plan：APK 前置版本、协议/ABI、发布序号、组件降级/相同版本不同内容检查；显式 Device.Target 必须匹配 Linux ARMv7 接收器，其他目标尚拒绝；完整 bundle 仅同一目标域，恢复根与 APK 拒绝进入此计划。
- Stage：临时文件限量写入，长度+SHA256，文件 fsync 后 no-overwrite hardlink 发布 hash 缓存，再 fsync 目录；拒绝可写共享目录、目标符号链接。新缓存从不使用旧 .new 激活路径。
- Downloader：默认关闭；显式 NewDownloader(true) 才可联网。精确 GitHub Release 起点，HTTPS/有限官方资产域重定向，公网 DNS 筛选后连接已核验 IP，2 分钟总时限、3 次重定向、32KiB 头部、size+1 body 限额，不带 cookie/凭据，不用代理和压缩。新增 Discover：固定仓库公开 stable latest API，256KiB API/64KiB清单/1KiB原始RSA签名限额；拒绝草稿/预发布，签名后核对tag。默认仍关闭，无私库认证/ETag/重试。
- BootstrapStore：显式离线初始化已验签的完整 known-good bundle；已有 journal 一律拒绝覆盖，不会因读取失败自动重置防重放状态。
- OpenStore：0700 私有目录，NOFOLLOW 打开，flock 独占，接收端自己的 pin 重验签且逐文件回验 baseline hash；返回前自动撤销未确认 trial，拒绝旧进程延迟健康确认。
- SimulateTrial：实验接口，只写持久 pending。以已存 good 衍生 installed 状态，拒绝遗漏旧组件，候选全量回验后生成随机 nonce。未实现真实进程启动。
- SimulateConfirm：实验健康消息精确匹配 nonce/boot，再回验 candidate，原子提交 good。真实认证健康来源未实现。
- Recover：丢弃任何未确认 pending，返回完整 known-good bundle；保留已接受 sequence floor，失败版本不自动重试。已确认 bundle 不回退。
- Activate：始终报错，生产激活明确禁用。

journal 使用文件写入→fsync→原子 rename→目录 fsync。失败后实例被污染，不再允许继续操作，必须关闭并重新打开判断磁盘状态。
解析或一致性检查检测出的损坏、未知 schema、错误 key、损坏 good 文件均 fail closed，不猜槽位，不重置序号。
缓存及旧版永不自动删除，配置没有迁移或修改。初始完整 baseline 由可信引导方提供，本模块不能发现实际设备上未列入 baseline 的组件；这必须由首次盘点校验补齐。

## 已验证与不能推导的结论

纯本地测试覆盖验签/信任根/JSON、兼容与降级、下载预算、数据截断/同长度损坏、符号链接、单写者、持久确认、重启回退、过期健康消息、防重放。
在 trial/confirm/recover 的 write、file-sync、rename、directory-sync 共 12 个边界注入故障后重新打开并恢复，检查旧/新完整状态与 sequence floor。
这些是**文件系统操作返回边界的模拟故障**，不是拔电测试；没有模拟真实 fsync 不可靠或底层存储回滚。
journal 不具防特权攻击/整盘旧快照恢复能力。固定 key 的配置来源、最低序号防回滚锚与密钥轮换需要可信引导。
Store 只做同机元数据事务，不是设备进程回滚，也不是 APK/探针/仪表分布式事务。

## 接生产前的硬门槛（目前都未接，因此关闭）

1. 首次受控引导部署新 APK、独立可信 updater/recovery root 和公钥。不能使用公开仓库中的 APK 私钥作为 OTA 信任根；核实实际 APK 证书及迁移路径。
2. **旧路径隔离**：当前 p7boot 直接采纳 .new；daemon 只判断 ELF/大小及 2 秒稳定，不校验签名且没有备份。不得把新缓存映射为 .new。先升级并验证 boot/watchdog 两槽策略，确保旧 APK 内嵌 deploy 不以版本“不相等”覆盖独立组件；需要独立 ownership/hash/version capability 标记及新旧客户端门禁。本次仅增加防误覆盖门禁，未接安全激活或旧客户端隔离，所以不能启用 OTA。
3. 接收端通过可信传输获得签名清单+完整 bytes，自己验签/hash；缓存路径在该设备应用私有稳定父路径下。目录父链竞态/所有权、磁盘配额、symlink 逃逸防御需按真实 Linux/Android 环境补齐。
4. 资源格式、大小/帧数/尺寸/schema 校验，player-pack 与旧新协议交集检测。当前仅有限 schema、组件版本、总大小与 hash，不构成资源语义安全。
5. 驻车状态来源可信且足够新鲜、无播放冲突、电量/供电/磁盘足够。不能靠人工传 true 当设备安全门禁。
6. 待 pending durable 后才允许切进程/代次；进程 hash/版本、输出 freshness、渲染自检、原厂显示恢复健康确认。探针停旧再启新，避免双轮询。健康消息要有认证 nonce+启动代次，真实来源尚未接。
7. 未确认重启恢复必须由独立 boot/watchdog 执行本地已知好版，不依赖网络。配置迁移必须兼容回滚。
8. ARM/真实文件系统 fsync/rename/exec 行为、车机 TLS/CDN/安装策略、实机故障恢复和长期稳定性验证。

## 本地验证

    go test -race -count=3 ./...
    go vet ./...
    CGO_ENABLED=0 GOOS=linux GOARCH=arm GOARM=7 go test -c -o /tmp/updatecore-arm.test .

所有下载测试使用虚拟 RoundTripper，没有联网或车辆。运行单元测试不启用生产更新。


## 可运行离车链路

已新增 StageRelease 协调器与 cmd/updatebench，真实读取/写入两个独立的本机目录：
GitHub Discover → 固定公钥 Verify → 持久接收端 Prepare → 未变内容跳过 →
受限 Fetch 到 sender → 字节复制到 receiver 并再次 hash → receiver pin 重验签。
它不是 FTP 替代实现，不会执行下载文件。损坏缓存拒绝使用且不静默覆盖。

集成回归把 GitHub API/清单/签名/单个变化的仪表资产完整串起来，使用真实
RSA 验签及真实磁盘文件；player/pack 保持原 hash 时只下载仪表一次。
另覆盖三组件传输后重启回退、重放请求在联网前拒绝、坏 host cache 与
symlink 导入、CLI 本地初始化→暂存→模拟失败回退→新序号模拟确认。
网络响应在测试中由虚拟 transport 提供，不代表已完成真实 CDN 下载。

CLI 用法（需要自己提供可信 baseline、公钥和签名；不提供私钥）：

    go build -o /tmp/updatebench ./cmd/updatebench
    /tmp/updatebench init --root /tmp/p7-bench-new --pin public.pem       --manifest baseline.json --signature baseline.sig --assets baseline-assets --apk-code 101
    /tmp/updatebench stage --root /tmp/p7-bench-new --manifest candidate.json       --signature candidate.sig --assets candidate-assets --apk-code 101
    /tmp/updatebench stage-latest --root /tmp/p7-bench-new --network --apk-code 101
    /tmp/updatebench simulate-cycle --root /tmp/p7-bench-new --manifest candidate.json       --signature candidate.sig --apk-code 101 --boot lab-1
    /tmp/updatebench status --root /tmp/p7-bench-new

init 只允许新建目录，写入 BENCH_ONLY 标记并固定公钥；后续不能用 --pin 替换信任根。
默认无网络；stage-latest 必须显式 --network。模拟周期默认留下未确认 pending，
下一次打开自动回退。仅 --confirm-lab 会在**同一进程**模拟健康确认；
这不是对任何实际进程、车辆状态或资源解析器的健康验证。
APK code 在台架中是用户显式配置值，不是对车机已安装版本的可信检测。

公开 GitHub REST latest 返回 Not Found 时可能是无公开 Release，
也可能是仓库/访问权限原因；客户端不将其误判为已更新。
当前没有可验证签名发布与生产公钥，**真实 GitHub 发布→设备链路仍未完成**；
不得绕过签名、嵌入私人 token，或复用泄露的 APK 签名材料。
本轮停在可测试的离车文件传输/持久事务边界，生产恢复路径继续禁用。

## 仪表恢复与迁移隔离验证

新增 MaterializeRecovery / CLI recover-generation：
先撤销未确认 trial，再从接收端独立验签/校验的 known-good 完整映射复制
仪表、播放器及资源到同一个 manifest-hash 命名目录。每个文件复制后回验、
fsync，再同步目录→原子rename→父目录fsync。只返回完整代次路径，
已存在但损坏的代次拒绝复用，不偷偷覆盖。文件没有执行位，不启动进程、
不切 active symlink、不改真实仪表路径。主机本地副本不能替代独立boot恢复器。

    /tmp/updatebench recover-generation --root /tmp/p7-bench-new

除了函数返回错误注入，新增16个**真实子进程退出**边界测试：
同一测试二进制在 trial/confirm/recover 的4个持久化点和代次复制的4个点
调用 os.Exit(99)，绕过defer后由新进程实例重新打开并恢复。验证三组件
不混代、未确认回旧、确认后保留新代、序号floor不倒退、残留.part不被采纳。
这仍不是存储掉电、控制器缓存丢失或真实车辆断电测试。

新版仪表源码添加 legacyUpdateFence：update-v1 **命名空间本身**任何形态存在
（空目录、文件、坏OWNER、正常/悬空链接）或检查错误均阻断旧.new采纳；
同一daemon一旦看到隔离即锁存，短暂删除也不重开旧通道，rename前再次检查。
新版p7boot/p7watch也遇命名空间直接退出，不启动新更新器、不cleanup活进程。
源码与APK内脚本文本副本同步；**未重建或替换APK内仪表二进制**。
没有创建任何生产update-v1命名空间。没有marker时维持旧部署路径。

这些门禁只防“已升级参与方”误覆盖，**不是完整安全迁移**：
- 已装旧boot/daemon不认识标记；旧APK仍能通过root FTP重写boot及资源。
- FTP的SIZE将缺失/权限/断线都归为-1（Ftp.java:187起），LIST也缺乏严格完成契约
  （:263起）。即使NLST成功，未验证的服务端可能过滤权限项，不能证明未迁移。
- 因此未把未经验证的FTP查询接到Deploy生产入口，避免误放行或阻断所有旧固件。
- 必须先停止旧写者/旧进程，由可信服务端ownership锁和目录权限隔离完成迁移，
  再验证新boot恢复协议。双次检查不消除TOCTOU，不能并发切流。
- watcher退出仅是隔离旧重启路径，不保证原厂清层；真实显示恢复仍是上线硬门槛。
