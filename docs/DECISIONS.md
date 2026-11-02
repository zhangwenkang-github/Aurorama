# Aurorama 2.0 决策索引

> 这里只索引已接受/待决事项。难逆转、非显然且有真实取舍的决策写入 `docs/adr/`。

## Accepted

| ID | 决策 | 记录 |
|---|---|---|
| `V2-D001` | 2.0 是正式 `2.0.0`，同时代表 V2 架构与流程代际；保留 1.4.0 用户数据 | [ADR-0003](adr/0003-v2-upgrade-boundary.md) |
| `V2-D002` | 开发前依次通过需求门、方案门、开发门 | `V2_PLAN.md` §6 |
| `V2-D003` | 使用分职责权威文档；现有经验精选归一；外部原文只读 | [ADR-0004](adr/0004-v2-authority-and-integration.md) |
| `V2-D004` | 章节作为只读白名单，以需求域和依赖闭环切会话 | `V2_PLAN.md` §8 |
| `V2-D005` | 澄清无总上限；每轮问当前前沿；事实由团队调查 | `PROJECT_RULES.md` R003 |
| `V2-D006` | 产品/不可逆事项用户拍板；内部可逆事项团队留痕 | [ADR-0004](adr/0004-v2-authority-and-integration.md) |
| `V2-D007` | QA/merge-prep 后及时进 V2 集成分支；外部 push/tag/Release 另授权 | [ADR-0004](adr/0004-v2-authority-and-integration.md) |
| `V2-D008` | 原始需求默认全量候选；1.4.0 遗留进入统一处置矩阵 | `V2_PLAN.md` §9 |
| `V2-D009` | 写测试只用独立可写测试服务器；生产只读；凭据只记位置 | [ADR-0005](adr/0005-server-write-safety-boundary.md) |
| `V2-D010` | 架构安全性、边界、契约、证据与发布门为强约束；工具和格式可适配且弱化须留痕 | `V2_PLAN.md` §5.2.4 |
| `V2-D011` | 文档要求按权威信息能力映射，不预建空文档；无等价落点时才新增正式文件 | `V2_PLAN.md` §5.2.4 |
| `V2-D012` | 分支从批准集成目标 SHA 创建，禁止功能依赖；一日为目标，超期演练，活动期检查拓扑 | [ADR-0004](adr/0004-v2-authority-and-integration.md) |
| `V2-D013` | 常规可逆历史操作由独立 merge-prep 留痕、主责复核；重写或高风险异常交用户 | [ADR-0004](adr/0004-v2-authority-and-integration.md) |
| `V2-D014` | 迁移回滚采用可丢弃 profile、备份恢复或兼容回退，不要求真实数据破坏性降级 | [ADR-0003](adr/0003-v2-upgrade-boundary.md) |
| `V2-D015` | 发布门 P0=0；P1 修复或用户书面延期；P2/P3 留档但不自动阻断 | `V2_PLAN.md` §5.2.4 |
| `V2-D016` | 证据按任务适配且足以复现；截图只用于视觉并遵守预算，不替代行为证据 | `V2_PLAN.md` §5.2.4 |
| `V2-D017` | ARCH 72 条最终为 69 `MUST` / 3 `SHOULD` / 0 `LATER` / 0 `OUT` | `V2_PLAN.md` §5.2.2、§5.2.5 |
| `V2-D018` | 2.0 仅使用本地结构化日志、诊断指标和测试证据，不引入远程遥测、分析或自动崩溃上传 | [ADR-0006](adr/0006-v2-local-observability.md) |
| `V2-D019` | 文档按 README / ARCHITECTURE / DECISIONS+ADR / V2_PLAN / TASKS / TEST_PLAN / RELEASE_PLAN / Release notes 权威映射 | `V2_PLAN.md` §5.2.5 |
| `V2-D020` | G1 建架构违规清单；任务门禁止新增；发布门关闭全部未豁免违规 | [ADR-0004](adr/0004-v2-authority-and-integration.md) |
| `V2-D021` | 主题是覆盖全 App 的完整视觉身份；外观、内容域、阅读显示偏好和动态色是正交维度 | [ADR-0007](adr/0007-v2-app-wide-theme-model.md) |
| `V2-D022` | 所有主题共享信息架构、核心导航、组件行为和交互语义；视觉身份可改变色、字、材质、形状和动效 | [ADR-0007](adr/0007-v2-app-wide-theme-model.md) |
| `V2-D023` | 保留 Prism / Lumen / Nocturne 视觉基因与资产，不继承页面局部换皮方式 | [ADR-0007](adr/0007-v2-app-wide-theme-model.md) |
| `V2-D024` | 系统动态色保留为独立全 App 主题，不叠加在品牌主题上 | [ADR-0007](adr/0007-v2-app-wide-theme-model.md) |
| `V2-D025` | 所有主题及支持外观共享 WCAG AA、焦点、状态、减少动画和响应式布局质量基线 | [ADR-0007](adr/0007-v2-app-wide-theme-model.md) |
| `V2-D026` | 2.0 提供 5 个主题：Prism、Lumen、Nocturne、系统动态色和 1 个待定新主题 | [ADR-0007](adr/0007-v2-app-wide-theme-model.md) |
| `V2-D027` | 所有品牌主题均支持浅色、深色和跟随系统；动态色主题跟随系统壁纸及明暗 | [ADR-0007](adr/0007-v2-app-wide-theme-model.md) |
| `V2-D028` | 内容域角色跨主题稳定；影视、音乐、书籍的具体色组由各主题拥有 | [ADR-0007](adr/0007-v2-app-wide-theme-model.md) |
| `V2-D029` | 产品 UI 最多使用 MiSans + Literata 两族；阅读正文字体设置不计入 | [ADR-0007](adr/0007-v2-app-wide-theme-model.md) |
| `V2-D030` | 每套主题选择一个基础圆角 8 / 12 / 16dp，并只允许同心派生；圆点、胶囊、圆形单列形状 | [ADR-0007](adr/0007-v2-app-wide-theme-model.md) |
| `V2-D031` | 主题驱动的界面交互动效统一为 150–300ms；连续内容运动独立处理 | [ADR-0007](adr/0007-v2-app-wide-theme-model.md) |
| `V2-D032` | 触屏必测默认、按下、聚焦、禁用、加载、空和错误态；Hover 仅用于指针环境 | [ADR-0007](adr/0007-v2-app-wide-theme-model.md) |
| `V2-D033` | 首屏保留媒体内容卡，但必须有单一主视觉焦点，禁止仪表盘式卡片堆叠 | [ADR-0007](adr/0007-v2-app-wide-theme-model.md) |

## Open

| ID | 待决事项 | 推荐答案 | 状态 |
|---|---|---|---|
| `V2-Q-UX-14` | 第 5 个主题采用什么视觉方向？ | 采用“Monolith”：高对比几何、偏不透明材质、强描边、8dp 基础圆角、克制动效；保留媒体图作为唯一大面积色彩来源。 | 待用户回答 |
| `V2-Q-UX-15` | 新装默认主题与外观是什么？ | 默认 Prism + 跟随系统；用户显式选择后持久保存。 | 待用户回答 |
| `V2-Q-UX-16` | 主题选择界面如何表达差异？ | 设置页提供大尺寸实时主题预览，覆盖首页、详情与控件直观样例；外观模式独立选择，确认后立即应用。 | 待用户回答 |
| `V2-Q-UX-17` | 主题偏好按什么范围保存？ | 主题与外观按设备本地保存；不同设备可使用不同主题，不写服务器、不绑定 Jellyfin 用户。 | 待用户回答 |
| `V2-Q-UX-18` | 现有主题偏好如何迁移？ | 保留当前浅 / 深 / 跟随系统；已开启 Material You 的用户映射到系统动态色主题，其余映射到 Prism，不清偏好、不弹一次性阻断式向导。 | 待用户回答 |
| `V2-Q-UX-19` | 主题切换是否需要重启？ | 主题和外观即时全局生效；使用 200ms 可关闭的交叉过渡，不重载页面、数据或滚动位置。 | 待用户回答 |
| `V2-Q-UX-20` | 阅读器显示主题与 App 主题如何持久化？ | 阅读显示主题作为全局阅读偏好独立保存；页面外框服从 App 主题，首版不做逐书覆盖。 | 待用户回答 |
| `V2-Q-UX-21` | Android 12 以下如何呈现系统动态色主题？ | 保留主题入口但显示为禁用，并明确“需要 Android 12+”；不得静默冒充动态色。 | 待用户回答 |
