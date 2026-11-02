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
| `V2-D026` | 2.0 提供 5 个主题：Prism、Lumen、Nocturne、Monolith 和系统动态色 | [ADR-0007](adr/0007-v2-app-wide-theme-model.md) |
| `V2-D027` | 所有品牌主题均支持浅色、深色和跟随系统；动态色主题跟随系统壁纸及明暗 | [ADR-0007](adr/0007-v2-app-wide-theme-model.md) |
| `V2-D028` | 内容域角色跨主题稳定；影视、音乐、书籍的具体色组由各主题拥有 | [ADR-0007](adr/0007-v2-app-wide-theme-model.md) |
| `V2-D029` | 产品 UI 最多使用 MiSans + Literata 两族；阅读正文字体设置不计入 | [ADR-0007](adr/0007-v2-app-wide-theme-model.md) |
| `V2-D030` | 每套主题选择一个基础圆角 8 / 12 / 16dp，并只允许同心派生；圆点、胶囊、圆形单列形状 | [ADR-0007](adr/0007-v2-app-wide-theme-model.md) |
| `V2-D031` | 主题驱动的界面交互动效统一为 150–300ms；连续内容运动独立处理 | [ADR-0007](adr/0007-v2-app-wide-theme-model.md) |
| `V2-D032` | 触屏必测默认、按下、聚焦、禁用、加载、空和错误态；Hover 仅用于指针环境 | [ADR-0007](adr/0007-v2-app-wide-theme-model.md) |
| `V2-D033` | 首屏保留媒体内容卡，但必须有单一主视觉焦点，禁止仪表盘式卡片堆叠 | [ADR-0007](adr/0007-v2-app-wide-theme-model.md) |
| `V2-D034` | 第 5 个品牌主题采用 Monolith 方向：高对比几何、偏不透明材质、强描边、8dp 基础圆角、克制动效 | [ADR-0007](adr/0007-v2-app-wide-theme-model.md) |
| `V2-D035` | 新装默认使用 Lumen 主题与跟随系统外观 | [ADR-0007](adr/0007-v2-app-wide-theme-model.md) |
| `V2-D036` | 主题选择采用大尺寸实时预览；外观模式独立选择并即时应用 | [ADR-0007](adr/0007-v2-app-wide-theme-model.md) |
| `V2-D037` | 主题与外观按设备本地保存，不写服务器、不绑定 Jellyfin 用户 | [ADR-0007](adr/0007-v2-app-wide-theme-model.md) |
| `V2-D038` | 迁移保留浅 / 深 / 跟随系统；动态色用户映射到系统动态色，其余用户映射到 Lumen，不弹阻断式向导 | [ADR-0007](adr/0007-v2-app-wide-theme-model.md) |
| `V2-D039` | 主题和外观即时全局生效；200ms 可关闭交叉过渡，不重载页面、数据或滚动位置 | [ADR-0007](adr/0007-v2-app-wide-theme-model.md) |
| `V2-D040` | 阅读显示主题作为全局阅读偏好独立保存；页面外框服从 App 主题，首版不做逐书覆盖 | [ADR-0007](adr/0007-v2-app-wide-theme-model.md) |
| `V2-D041` | Android 12 以下保留系统动态色主题入口但禁用，并明确平台版本要求 | [ADR-0007](adr/0007-v2-app-wide-theme-model.md) |
| `V2-D042` | 第 5 个品牌主题正式命名为 Monolith，名称不逐语言翻译，说明文案本地化 | [ADR-0007](adr/0007-v2-app-wide-theme-model.md) |
| `V2-D043` | 系统动态色主题用 dynamic Primary / Secondary / Tertiary 映射影视 / 音乐 / 书籍，neutral roles 负责表面层级 | [ADR-0007](adr/0007-v2-app-wide-theme-model.md) |
| `V2-D044` | 系统动态色主题继承 Prism 的字体比例、组件几何、基础圆角和克制动效 | [ADR-0007](adr/0007-v2-app-wide-theme-model.md) |
| `V2-D045` | 主题预览使用内置原创演示海报与样例文本，不读取真实账号内容 | [ADR-0007](adr/0007-v2-app-wide-theme-model.md) |
| `V2-D046` | 主题预览默认当前外观，并提供就地浅 / 深切换 | [ADR-0007](adr/0007-v2-app-wide-theme-model.md) |
| `V2-D047` | 新装阅读显示主题默认跟随系统；已有用户的阅读主题保留 | [ADR-0007](adr/0007-v2-app-wide-theme-model.md) |
| `V2-D048` | 2.0 只提供 5 个策展主题，不提供任意颜色编辑器或主题构建器 | [ADR-0007](adr/0007-v2-app-wide-theme-model.md) |
| `V2-D049` | 设置 → 界面中分别提供“主题”预览入口与“外观模式”选择 | [ADR-0007](adr/0007-v2-app-wide-theme-model.md) |
| `V2-D050` | 保留纸色 / 护眼 / 深色 / OLED / 跟随系统五种阅读显示主题，与 App 主题分离 | [ADR-0007](adr/0007-v2-app-wide-theme-model.md) |
| `V2-D051` | 产品 UI 只使用 400 / 500 / 700 字重，移除 600 | [ADR-0007](adr/0007-v2-app-wide-theme-model.md) |
| `V2-D052` | 布局间距使用 4 / 8 倍数；页面内边距 ≥12dp、区块间距 ≥32dp；1–2dp 仅用于线条 | [ADR-0007](adr/0007-v2-app-wide-theme-model.md) |
| `V2-D053` | 每套主题定义 success / warning / error / info 语义状态色，必须配图标或文字且不用于装饰 | [ADR-0007](adr/0007-v2-app-wide-theme-model.md) |
| `V2-D054` | `docs/UI_DESIGN_SYSTEM.md` 为 V2 人类可读设计规范，`CinefinTokens.kt` 为唯一代码 token 源，不新增重复 `DESIGN.md` | [ADR-0007](adr/0007-v2-app-wide-theme-model.md) |
| `V2-D055` | 本波主题覆盖 `app/phone` 手机 / 平板 / 折叠形态；`app/tv` 保持冻结 | [ADR-0007](adr/0007-v2-app-wide-theme-model.md) |
| `V2-D056` | 正文 / 标签字体指标固定；主题只调整 display / headline 比例、字距与 MiSans / Literata 使用权重 | [ADR-0007](adr/0007-v2-app-wide-theme-model.md) |
| `V2-D057` | 基础圆角为 Prism 12 / Lumen 16 / Nocturne 12 / Monolith 8 / 系统动态色 12dp | [ADR-0007](adr/0007-v2-app-wide-theme-model.md) |
| `V2-D058` | 五主题使用各自材质语汇，不用装饰性背景纹理；用户另要求增加受控极光渐变 / 粒子效果 | [ADR-0007](adr/0007-v2-app-wide-theme-model.md) |
| `V2-D059` | 主图标保持唯一但不禁止重绘；启动页跟当前主题；同一图标不按主题切多份桌面图标 | [ADR-0007](adr/0007-v2-app-wide-theme-model.md) |
| `V2-D060` | G1 每主题提供首页、详情、设置 / 主题预览的明暗稿，加共享组件状态板 | [ADR-0007](adr/0007-v2-app-wide-theme-model.md) |
| `V2-D061` | 现阶段不安装外部 Web 向设计 Skill / MCP；优先用项目原生 token 与本地 HTML/SVG + 无头浏览器渲染 | [ADR-0007](adr/0007-v2-app-wide-theme-model.md) |
| `V2-D062` | 自动检查 token、对比度、间距、圆角、状态与文档漂移；观感由独立视觉 QA 判断 | [ADR-0007](adr/0007-v2-app-wide-theme-model.md) |
| `V2-D063` | 图标需重新绘制并提高亮度；以质量优先加入极光相关元素、渐变和粒子效果 | [ADR-0007](adr/0007-v2-app-wide-theme-model.md) |
| `V2-D064` | 实现前先产出可审查的主题效果图；本地确定性渲染路径可完成时无需安装外部设计 Skill / MCP | [ADR-0007](adr/0007-v2-app-wide-theme-model.md) |

## Open

| ID | 待决事项 | 推荐答案 | 状态 |
|---|---|---|---|
| `V2-Q-UX-43` | “图标重绘”覆盖哪些图标？ | 覆盖产品 UI 图标系统和启动图标；UI 图标统一几何 / 光学尺寸，启动图标保留同一品牌母题并重绘为更明亮的极光版本。 | 待用户回答 |
| `V2-Q-UX-44` | 新图标采用什么视觉语法？ | 默认使用 1.75–2dp 圆角描边，选中 / 激活态改用填充或双色；极光渐变只用于品牌图标和关键选中态，不用于每个普通图标。 | 待用户回答 |
| `V2-Q-UX-45` | 极光元素出现在哪些界面和主题中？ | 作为全主题共享品牌层：启动页、首页 Hero、播放器待机 / 控制层、进度与关键激活态、主题预览；Lumen 最强，Prism 中等，Nocturne 较轻，Monolith 只保留几何极光切面，动态色使用系统色重组。 | 待用户回答 |
| `V2-Q-UX-46` | 渐变使用边界是什么？ | 每个视口最多一个主极光渐变场，必须来自主题 token；禁止彩虹铺满、紫蓝默认渐变和影响文字的装饰层。 | 待用户回答 |
| `V2-Q-UX-47` | 粒子是否动画，如何控制性能与无障碍？ | 默认静态微粒；只在启动页、首页 Hero、播放器待机等非滚动品牌层低调运动；离屏 / 后台暂停，减少动画时静止；不使用动画模糊或滚动列表内粒子。 | 待用户回答 |
| `V2-Q-UX-48` | “质量优先”的效果性能底线是什么？ | 以 60fps 为目标、无持续 jank / 过热 / 异常耗电；效果按设备能力降级，质量优先不等于无条件堆叠粒子、模糊或叠加层。 | 待用户回答 |
| `V2-Q-UX-49` | 效果图如何生产与分阶段确认？ | 先用确定性界面板 + 本地生成极光素材制作每主题 1 张风格帧；用户确认后再制作每主题 3 个代表界面的明暗稿和共享组件状态板；不安装外部设计 MCP。 | 待用户回答 |
| `V2-Q-UX-50` | 图标前景最低对比度采用什么口径？ | 关键操作图标至少 4.5:1，目标 7:1；次要 / 禁用图标按状态分别处理，绝不再用 62% 白作为普通导航默认前景。 | 待用户回答 |
