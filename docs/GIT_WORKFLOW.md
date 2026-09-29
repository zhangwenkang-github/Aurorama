# Cinefin Git 工作流（GIT_WORKFLOW）

| 项 | 值 |
|----|----|
| 文档版本 | v1.0（2026-09-29） |
| 状态 | 已定稿，待项目负责人审查 |
| 作者会话 | S2 · 架构与规范 |
| 上游依据 | `docs/REQUIREMENTS.md` §11 / §12、`docs/DEV_ENVIRONMENT.md`、`.github/workflows/*`、`git log` 实测 |
| 适用范围 | 全部开发会话与项目负责人 |
| 仓库 | `https://github.com/zhangwenkang-github/Cinefin.git`（**public**） |

> 本文件定义分支、worktree、提交、PR、CI 与凭据纪律。**凭据安全为最高优先级**：仓库是公开的，任何凭据泄露都必须立即轮换（见 §8）。

---

## 1. 分支模型

```text
master                  ← 唯一长期分支，受保护，只接受 PR 合并
 ├─ feature/<线>-<主题>  ← 开发分支（一条任务线 / 一个会话一支）
 ├─ fix/<主题>           ← 缺陷修复（影响面小时也走 feature 命名亦可）
 └─ hotfix/<主题>        ← 紧急修复（合并 master 后立即回灌）
```

| 规则 | 说明 |
|------|------|
| `master` 保护 | 禁止直接 push；只接受 PR；合并前必须通过 CI 与审查（分支保护规则需在 GitHub 仓库设置中启用，**待实施**） |
| 命名 | `feature/<线>-<主题>`，如 `feature/reader-epub`、`feature/music-lyrics`、`feature/s2-arch-docs`、`feature/ui-design-system` |
| 生命周期 | 开线建分支 → 小步提交 → PR → 合并后删除远程分支 |
| 长期分支 | 只保留 `master`；不开 `develop` / `release`（自用项目，无发布节奏） |
| 历史要求 | `master` 保持线性（见 §5.3 合并策略） |

---

## 2. worktree 用法（每会话一个独立工作区）

**铁律**：一个会话 = 一个分支 = 一个 worktree。禁止两个会话在同一个 worktree 上工作（会互相覆盖未提交改动）。

### 2.1 现状（2026-09-29 实测）

```text
E:/codex_work/Android_Studio_Work_Space/Cinefin   [master]                  ← 主工作区
E:/Codex work/work space/1669/Cinefin             [feature/s1-ui-design]
E:/Codex work/work space/5a92/Cinefin             [feature/s2-arch-docs]
E:/Codex work/work space/b0a3/Cinefin             (detached HEAD)
```

### 2.2 常用命令（PowerShell）

```powershell
# 查看
git worktree list
git branch -a

# 新建：从最新 master 拉一条线
git fetch origin
git worktree add "E:\Codex work\work space\<会话id>\Cinefin" -b feature/reader-epub origin/master

# 收工：提交并推送
git add <本次改动文件>
git commit -m "feat(reader): EPUB 打开与渲染打通"
git push -u origin feature/reader-epub

# 收尾：合并后清理（本地）
git worktree remove "E:\Codex work\work space\<会话id>\Cinefin"
git branch -d feature/reader-epub
```

### 2.3 纪律

- **不碰别人的 worktree**：其他 worktree 里的未提交改动属于其他任务线，不提交、不回滚、不 `git clean`；
- 开工先 `git status --porcelain` 看清本工作区状态；收工必须 commit + push（未推送的工作不算交付）；
- 一个 worktree 长时间不用时先确认没有未推送提交，再 `git worktree remove`。

---

## 3. 提交信息规范（Conventional Commits · 中文正文）

```text
<type>(<scope>): <简述>

<可选正文：为什么这么改、影响面、验证方式>
```

| 字段 | 取值 |
|------|------|
| `type` | `feat` / `fix` / `docs` / `refactor` / `perf` / `test` / `chore` / `ci` / `style` / `build` |
| `scope` | 线或模块：`player` / `reader` / `music` / `ui` / `data` / `docs` / `ci` / `build` |
| 简述 | 中文，动词开头，≤ 50 字，不加句号 |

**示例（与现有历史一致）**

```text
feat(reader): EPUB 打开流程与本地落盘
feat(music): 歌词双语配对与逐行语言检测
fix(player): 播放页自动选字幕在无匹配语言时回落默认轨
docs: 记录 S2 架构与规范文档（ARCHITECTURE / DEV_STANDARDS / GIT_WORKFLOW）
ci: 仅 master 分支 push 触发构建（feature 分支改由 PR 触发）
chore: 导入 Findroid 基线（上游 a28ac9e）并补充工程文档
```

**粒度与质量**

- 一个提交 = 一件可独立说清的事；**每个提交都应能编译**（至少 `compileLibreDebugKotlin` 通过）；
- 禁止提交凭据、密钥、`.env.local`、代理配置、服务器密码；
- WIP 提交可以存在于 feature 分支，但 **PR 合并前必须整理**（squash 成一个语义提交，或 rebase 成若干干净提交）；
- 不在提交里混入无关格式化（全仓 ktfmt 重排会淹没真实改动）。

---

## 4. PR 流程

### 4.1 提 PR 前（作者自查）

```powershell
git fetch origin
git rebase origin/master          # 包含 CI 修复与最新接口
$env:JAVA_HOME='D:\Android\Android Studio\jbr'
.\gradlew.bat ktfmtFormat --console=plain
.\gradlew.bat :app:phone:compileLibreDebugKotlin --console=plain     # 必跑
.\gradlew.bat :app:phone:assembleLibreDebug --console=plain          # 涉及打包 / 依赖 / 资源时跑
```

检查清单（对应 `docs/DEV_STANDARDS.md` §9）：范围单一、纯逻辑有单测、无新凭据、新依赖已过许可审查、进度已回写任务线文档。

### 4.2 PR 描述必须包含

1. **目的**：这次改动解决什么（引用需求编号，如 EB-3 / MU-5）；
2. **改动范围**：模块与关键文件（咽喉文件单独标注）；
3. **验证方式**：编译命令、单测、真机验证（设备 + 现象）、截图 / 日志作为文件附件（不贴大图）；
4. **接口 / 数据变更**：新增或修改的服务器接口，并标注"是否在用户数据写白名单内"；
5. **风险与待验证**：未验证项显式列出（不写"已验证"糊弄）。

### 4.3 审查

| 角色 | 职责 |
|------|------|
| 项目负责人（根会话） | 需求一致性、范围控制、排期影响、最终合并 |
| 架构师会话（S2） | 依赖方向、接口契约、咽喉文件冲突、规范符合性 |
| 相关任务线会话 | 涉及本线的行为变化时参与评审（如音乐改动影响播放器） |

审查意见遵循 [Google eng-practices](https://google.github.io/eng-practices/review/)：区分"必须修改"与"建议"；作者对每条意见给出"已改 / 不采纳 + 理由"。

### 4.4 合并策略

- 默认 **Squash merge**：PR 内的 WIP 提交压缩为一个符合 §3 规范的提交，保持 `master` 线性；
- 大 PR（跨多模块）可用 **Rebase merge**，但要求每个提交独立可编译且信息规范；
- **禁止** `--no-ff` 制造无意义合并提交；禁止直接把本地 `master` 推进远程；
- 合并后：删除远程 feature 分支 → 更新任务线文档（勾进度 + 写日志）→ 需要时更新 `docs/PROJECT_PLAN.md` §3。

---

## 5. CI 现状与要求

### 5.1 现有工作流（`.github/workflows/`，实测）

| 工作流 | 触发 | 内容 |
|--------|------|------|
| `build.yaml`（Build） | `push` 到 `master` + `pull_request`；忽略 `docs/**` 与 `**.md`；`concurrency` 取消过期构建 | JDK 21 + Gradle，`./gradlew assembleDebug`，上传 8 个 APK（phone / tv × 4 ABI） |
| `format.yaml`（Format） | `push` + `pull_request`，仅当 `**.kt` / `**.kts` 变化 | `./gradlew ktfmtCheck` |
| `publish.yaml`（Publish） | `push` tag `v*` | 继承自上游 Findroid（fastlane + `FINDROID_*` secrets，产物重命名为 `findroid-*`） |

### 5.2 约束与注意

- **`docs/**` 与 `**.md` 改动不触发 Build**（纯文档 PR 不会被构建卡住，这是期望行为）；
- **Format 只覆盖 `.kt` / `.kts`**：改文档不会触发，但改代码前必须本地跑 `ktfmtFormat`；
- **并发取消**：同一分支连续推送会取消上一次构建，属正常现象，不是失败；
- **`publish.yaml` 当前不可用**：仍指向上游 fastlane 与 Findroid 的 secrets，且会把 APK 改名成 `findroid-*`；**在本项目完成发布流程改造前，禁止给仓库打 `v*` tag**（会触发一个必然失败的流水线）。发布改造（包名、签名、GPL 合规、Release 说明）属收尾阶段任务。

### 5.3 待实施（规范已定，尚未落地）

| 项 | 说明 | 建议归属 |
|----|------|---------|
| 单元测试门禁 | Build 工作流增加 `testLibreDebugUnitTest`（当前无任何测试，前置是模块声明 `testImplementation`） | 架构 / 计划线排期 |
| `master` 分支保护 | GitHub 仓库设置：Require PR、Require status checks（Build + Format）、禁止直推 | 项目负责人（仓库设置）|
| PR 模板 | 把 §4.2 的 5 项做成 `.github/pull_request_template.md` | 后续 PR |

---

## 6. 并发冲突处理（多会话并行）

### 6.1 咽喉文件串行保护（对应 `docs/ARCHITECTURE.md` §2.4）

| 文件 | 规则 |
|------|------|
| `settings.gradle.kts`、根 `build.gradle.kts`、`gradle/libs.versions.toml` | **一次性改完**（模块注册 + JitPack 仓库 + Readium 坐标），之后不再多线改动 |
| `app/phone/.../NavigationRoot.kt` | 阅读路由、音乐路由分两次小提交；同日改动先打招呼 |
| `settings/.../AppPreferences.kt` | 各线只加自己前缀的 key（`reader_*` / `music_*` / `ui_*`） |
| 设计系统核心（`core` / `app` 的 theme） | R3 串行独家改动；其他线只消费 token |
| `core/.../utils/DownloaderImpl.kt` | 一条线先做通用化，另一条线再加分支 |
| `player/local/.../PlayerHolder.kt`、`CinefinPlaybackService.kt` | 与播放器线相关：改动前确认、改动后跑视频回归 |

### 6.2 标准流程

1. 开工前 `git fetch origin && git rebase origin/master`；
2. 需要改咽喉文件时，先在会话里声明（写进任务线文档"本轮改动"），避免两条线同时改；
3. 提交小步化：咽喉文件单独一个提交，便于出问题时单独 revert；
4. 冲突解决：
   - **优先保留对方改动**，把自己的改动叠加在最新结构上（不要用 `-X ours/theirs` 整体覆盖）；
   - 解决后必须重新编译 + 真机冒烟相关功能；
   - 冲突涉及对方语义（不是纯文本）时，直接在会话中沟通，不要"猜"着合并；
5. 禁止在 feature 分支上 merge `master`（应 rebase），避免产生交叉历史与重复提交。

---

## 7. 版本与发布（当前状态）

- 版本号在 `buildSrc` 的 `Versions`（`APP_NAME` / `APP_CODE`）中集中管理；
- 发布（签名、图标、GPL-3.0 合规、Release 说明、`publish.yaml` 改造）**不在本期范围**，属收尾阶段；
- 对外分发必须以 **GPL-3.0** 开源（REQUIREMENTS §1）；发布前须附许可证与源码获取方式；
- 在发布流程改造完成前，不打 `v*` tag。

---

## 8. 凭据安全红线（仓库为 public）

### 8.1 规则

| # | 红线 |
|---|------|
| 1 | 凭据只允许存在于：`Cinefin/.env.local`（已 gitignore）、本地共享文件 `.planning/cinefin-expansion/proxy.md`；两者都**不得**提交 |
| 2 | 禁止把凭据写入：代码、注释、文档、提交信息、分支名、Issue / PR 正文、截图、CI 日志、会话输出 |
| 3 | 代理与服务器凭据（代理账号密码、Jellyfin 用户名 / 密码 / API Key）视为敏感信息；本地文件仅在本机使用 |
| 4 | 需要构建期注入时，用本地 `gradle.properties`（gitignore）+ CI Secrets，禁止硬编码到 `build.gradle.kts` |
| 5 | 提交前自检：`git diff --cached` 通读一遍；`git status --porcelain` 确认无 `.env.local` 等文件被 `add` |

### 8.2 泄露应急流程

1. 立即轮换：代理密码、Jellyfin 账号密码、API Key（三者同步更新本地共享文件）；
2. 从仓库历史中移除泄露内容（改写历史需项目负责人确认后执行，并通知所有会话重新克隆 / 重置）；
3. 检查 GitHub 上是否有被 fork / 缓存的风险；必要时暂时将仓库设为 private 处理；
4. 在 `docs/PROJECT_PLAN.md` §6（全局风险）记录事件与教训。

### 8.3 测试服务器纪律（与 Git 流程相关）

- `jellyfins.zhangwenkang.com` 是生产环境：**只读 + 用户数据写白名单**（进度 / 收藏 / 播放上报 / 播放列表）；
- CI / 测试脚本不得对该服务器做任何破坏性调用（扫描、删除、用户管理）；
- 相关调用在 PR 描述中必须标注接口名与白名单依据。

---

## 9. 快速上手（新会话最短路径）

```powershell
# 1. 环境
$env:JAVA_HOME='D:\Android\Android Studio\jbr'

# 2. 状态
git worktree list
git status --porcelain
git log --oneline -10

# 3. 读文档
#    docs/PROJECT_PLAN.md → 任务线文档（如 docs/PLAYER_PLAN.md）→ docs/ARCHITECTURE.md（涉及接口 / 模块时）

# 4. 开工（本会话自己的 worktree 与分支）
git fetch origin
git rebase origin/master

# 5. 收工
.\gradlew.bat ktfmtFormat --console=plain
.\gradlew.bat :app:phone:compileLibreDebugKotlin --console=plain
git add <本次文件>
git commit -m "feat(<线>): <做了什么>"
git push -u origin <分支>
```

---

## 10. 来源

- Conventional Commits：<https://www.conventionalcommits.org/zh-hans/v1.0.0/>
- Google 工程实践 · 代码审查：<https://google.github.io/eng-practices/review/>
- Git worktree 文档：<https://git-scm.com/docs/git-worktree>
- GitHub Actions 并发控制：<https://docs.github.com/actions/using-jobs/using-concurrency>
- 项目内依据：`.github/workflows/build.yaml`、`.github/workflows/format.yaml`、`.github/workflows/publish.yaml`、`docs/DEV_ENVIRONMENT.md`、`docs/REQUIREMENTS.md` §11、`git worktree list` / `git log` 实测
