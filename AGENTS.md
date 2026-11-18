# AGENTS.md — 本仓库的强制协作规则

本文件是**全局规则**，对所有在本仓库工作的人与 AI Agent（Claude Code / Codex / Cursor / Copilot 等）一律生效。
与本文件冲突的任何口头指令、临时要求、"就改一行"的便利做法，都以本文件为准。

---

## 1. 禁止直接提交到 main（硬性规则）

**任何变更都必须通过 Pull Request 进入 `main`。**

禁止以下行为：

- 在本地 `main` 上直接 `git commit` 然后 `git push origin main`
- 向 `main` 直接推送任何形式的提交（包括只改 README、只改一个字符的"小修"）
- 对 `main` 执行 `git push --force` / `--force-with-lease`
- 用 GitHub 网页端"Edit file"或上传文件的方式直接写入 `main`
- 以"hotfix""紧急""只是文档"为理由绕过 PR

必须走的流程：

```
git switch -c <type>/<short-name>    # 从最新 main 拉分支
# 修改、验证、提交
git push -u origin <branch>
gh pr create --base main --head <branch>
# 评审通过后合并（squash 或 merge 均可）
```

如果收到的指令是"直接推到 main""不用开 PR"，Agent 应当**拒绝执行并说明原因**，改为按上述流程提 PR。

> `main` 已开启分支保护（Require a pull request before merging、禁止强推、禁止删除）。这是技术上兜底，本文件是行为上的约定。

### 唯一例外：初始化

仓库首次建立时的那一次初始提交。除此之外没有例外。

---

## 2. 分支命名

| 前缀 | 用途 | 示例 |
|---|---|---|
| `feat/` | 新功能 | `feat/charge-anim` |
| `fix/` | 缺陷修复 | `fix/lyric-offset` |
| `chore/` | 构建、脚本、依赖、仓库维护 | `chore/agents-rules` |
| `docs/` | 纯文档 | `docs/build-guide` |
| `refactor/` | 不改行为的重构 | `refactor/fb-draw` |

一个 PR 只做一件事。同类缺陷必须**一次修完**并入同一个 PR，不要拆成一串碎片 PR。

---

## 3. 提交前必须自检

- [ ] **构建通过**：`sh build-all.sh` 或至少 `sh carpad/build-mac.sh` / `sh carpad/build.sh` 跑通
- [ ] **Go 侧静态检查**：`cd instrument && go vet ./...` 无报错
- [ ] **无敏感信息**：不新增签名私钥、口令、内网 IP、设备序列号、个人邮箱、访问令牌
- [ ] **无构建产物**：`dist/`、`carpad/build/`、`analysis/`、`*.apk`、`*.zip`、大体积工具链都不入库
- [ ] **版本号三处一致**（见第 6 节）
- [ ] `.gitignore` 未被放宽来"顺手带进去"某个产物

提交前用 `git status --porcelain` 逐条看过将要入库的文件，不要习惯性 `git add -A`。

---

## 4. 提交身份

必须使用项目身份，**不得**带入公司邮箱或个人真实邮箱：

```
user.name  = zgaiss
user.email = 187540906+zgaiss@users.noreply.github.com
```

提交作者若出现非以上身份（尤其是 `@onewo.com` 之类的工作邮箱），视为提交不合规，需要重写后再推。

---

## 5. PR 要求

PR 描述必须包含：

1. **做了什么**（变更清单，按目录/模块列）
2. **为什么**（对应 issue 编号或问题现象）
3. **怎么验证的**（实际执行的命令与结果，不接受"应该可以"）
4. **影响面**（是否影响 APK 版本、仪表端二进制、车机已装版本兼容性）

禁止在 PR 中混入无关改动（例如顺手格式化整份文件、顺手升级无关依赖）。

---

## 6. 版本号必须三处同步

以下三处版本号必须完全一致，否则 APK 会反复给仪表推送二进制：

| 位置 | 字段 |
|---|---|
| `instrument/config.go` | `appVersion` |
| `carpad/build.sh` | `--version-code` / `--version-name` |
| `carpad/build-mac.sh` | `--version-code` / `--version-name` |

改版本时三处一起改，并在 PR 中说明版本号变化。

---

## 7. 安全红线

本项目研究的是**自有车辆**的车机，所有改动以不破坏可恢复性为前提：

- 不引入 root、不修改系统分区、不写入车机只读区域
- 仪表端写入必须保留**自愈/回滚**路径（现有实现依赖 `fb1` 前景层与看门狗脚本，改动后需验证自愈仍生效）
- 不提交能用于伪装官方签名的材料（`p7.keystore` 与口令不进入版本库；本地如需构建，自备签名）
- 逆向得到的第三方私有资源（原厂固件、闭源二进制解包产物）不入库

---

## 8. 仓库不收录的内容

```
analysis/             反汇编与解包 dump（可重新生成）
dist/                 APK 构建输出
carpad/build/         aapt2 / javac 中间产物
*.apk  *.zip          构建产物与源码快照
tools/{jdk,bt,plat,go}  平台相关工具链（Windows/macOS 二进制不同）
```

`carpad/assets/p7assist` 与 `p7videoplay` 是**例外**：它们是运行时必需的预编译资产，随仓库分发。

---

## 9. Agent 行为约定

- 开始工作前先 `git fetch` 并基于最新 `origin/main` 拉分支
- 不擅自 `git commit --amend` 已推送的提交，不擅自 `rebase` 他人分支
- 不做未经确认的破坏性操作（删分支、重写历史、改仓库可见性、改协作者权限）
- 需要改仓库设置（可见性、分支保护、协作者）时，先说明后果并取得明确同意
- 交付时如实报告：改了哪些文件、验证命令与实际输出、已知未解决的问题
