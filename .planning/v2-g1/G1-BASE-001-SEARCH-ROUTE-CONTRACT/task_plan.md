# 任务计划：G1-BASE-001 搜索路由状态合同

## 目标

在唯一证据目录内产出可静态验证、可被后续实现消费的搜索路由状态合同，冻结 scope/query
形状、结果身份与排序、取消/代际/截止时间、加载/空/失败区分及返回恢复的最小可观察语义；
不实现、不设备、不联网、不提交。

## 下一步

冻结 `report.json` 与 `commands.log` 的实际测试记录，生成 manifest，再执行含 manifest 校验的
最终正测和写域外改动检查。

## 当前阶段

阶段 4：交付与复核

## 硬边界

- 唯一 cwd：`F:\Develop\code_data\work_tree\Aurorama\v2-requirements-baseline\Aurorama`
- 唯一写域：`.planning/v2-g1/G1-BASE-001-SEARCH-ROUTE-CONTRACT/`
- 不改生产代码、资源、schema；不启动设备/服务器；不 commit/push。
- 只读白名单：AGENTS、R020/R025/R027/R028 命中、`docs/V2_PLAN.md` 指定短段、
  `docs/TASKS.md` / `docs/DECISIONS.md` 指定条目、`.planning/v2-g1/G1-S3/` 摘要；
  现有搜索代码仅允许 graphify query 或 `rg` 定位后局部读取 1–3 个文件。
- 不重做 S3 端点/协议台账；只引用其稳定证据入口与哈希事实。
- 历史 ANR 只能是 runtime/implementation gate，禁止在静态包内声称已解决。

## 各阶段

### 阶段 1：来源与现状取证
- [x] 核对 HEAD / 分支 / 初始工作区并记录
- [x] 读 `docs/PROJECT_PLAN.md`、`AGENTS.md` 必需入口
- [x] 按白名单抽取 R020/R025/R027/R028 与 V2_PLAN / TASKS / DECISIONS 短段
- [x] 读 G1-S3 report/contracts/commands 摘要，建立去重边界
- [x] graphify 缺失后仅局部读取 3 个现有搜索文件
- [x] 把发现写入 `findings.md`
- **状态：** complete

### 阶段 2：合同建模
- [x] 定义完整 query-shape、规范化与 scope 隔离
- [x] 定义路由状态机、事件代际、deadline、取消和 stale 丢弃
- [x] 定义 stable identity、ALL 去重、跨域同标题及稳定排序
- [x] 定义 back stack 最小可观察恢复合同
- [x] 建立用户承诺矩阵与 B/C/D 决策台账
- **状态：** complete

### 阶段 3：fixture 与变异测试
- [x] 固定 scope/query、identity、state、commitment 输入 fixture
- [x] 写独立 PowerShell 7 verifier，不依赖仓库构建
- [x] 正测 exit 0；三类 mutation exit 1
- [x] 检测 JSON、LF、敏感值与报告计数
- **状态：** complete

### 阶段 4：交付与复核
- [x] 生成 `report.json`、矩阵、状态机、短 README、manifest、commands.log
- [x] 与 G1-S3 现有证据做来源去重检查
- [x] 复算全部 hash / bytes / LF 并报告计数
- [x] 检查写域外改动：本会话零越界；发现并保留并行 `.gitattributes` 外部改动
- [x] 执行 Engramory 收尾判断并记录
- **状态：** complete

## 关键问题

1. 现有实现是否已表达完整 query shape、代际取消和返回恢复？
2. ALL 与各 scope 的结果身份是否可稳定关联，跨域同标题会不会误合并？
3. 静态 verifier 能否对三类关键 mutation 给出确定性失败，而不冒充运行时修复？

## 已做决策

| 决策 | 理由 |
|------|------|
| 以行为合同和 fixture 为交付，不碰生产搜索 | 用户明确限定静态证据，且历史 ANR 需运行时实现门禁 |
| query identity 覆盖全部行为参数 | 仅 hash `searchTerm` 已证实会混淆 ALL/VIDEO |
| 旧代迟到响应一律不可提交 UI | 防止旧 scope/query 覆盖新请求 |
| 结果身份使用 `type + server item id`，不以标题为唯一键 | 支持跨域同标题并避免误去重 |
| ALL 去重仅在稳定 service scope 内按稳定 item identity 合并 | 兼顾跨域同标题与重复响应 |

## 遇到的错误

| 错误 | 尝试次数 | 解决方案 |
|------|---------|---------|
| PowerShell `Test-Path ... -and ...` 被解析成参数 | 1 | 用括号包住 `Test-Path` 命令表达式后重跑 |
| `Measure-Object -Property bytes` 无法读取 ordered dictionary 条目 | 1 | 显式遍历条目并累加 `bytes` |

## 完成判据

- 正测 exit 0；`stale-response-overwrite`、`cross-scope-hit`、`empty-as-error` 三类 mutation
  均 exit 1。
- JSON 可解析、manifest 全量覆盖交付文件、SHA-256 可复算、UTF-8 无 BOM、LF-only、
  无敏感值命中。
- 合同与 S3 端点/协议台账职责不重叠，所有未覆盖项明确归入 B/C/D。
