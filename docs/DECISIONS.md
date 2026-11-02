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
| `V2-D063` | App Logo 是本次重绘主对象；界面图标系统也需重绘并提高亮度；界面尽量减少可见文字，优先以图标和动效表达 | [ADR-0007](adr/0007-v2-app-wide-theme-model.md) |
| `V2-D064` | 实现前先产出可审查的主题效果图；本地确定性渲染路径可完成时无需安装外部设计 Skill / MCP | [ADR-0007](adr/0007-v2-app-wide-theme-model.md) |
| `V2-D065` | App Logo 与界面动作图标分类管理；Logo 用于品牌 / launcher / splash，动作图标服务于可识别操作 | [ADR-0007](adr/0007-v2-app-wide-theme-model.md) |
| `V2-D066` | App Logo 沿用并强化“棱镜 + 极光帘幕”母题，重绘明亮光核、分层极光与少量微粒，并派生自适应 / 单色 / 浅色 / 启动页版本 | [ADR-0007](adr/0007-v2-app-wide-theme-model.md) |
| `V2-D067` | UI 图标以 1.75–2dp 圆角几何描边为主，关键激活态使用填充或双色；极光渐变只用于少数关键图标 | [ADR-0007](adr/0007-v2-app-wide-theme-model.md) |
| `V2-D068` | 删除与熟悉图标重复的可见动作标签、辅助文案和装饰说明；保留媒体标题、数值、错误、权限和破坏性确认 | [ADR-0007](adr/0007-v2-app-wide-theme-model.md) |
| `V2-D069` | 视觉无文字的可操作图标必须保留不可见可访问名称、角色和状态 | [ADR-0007](adr/0007-v2-app-wide-theme-model.md) |
| `V2-D070` | 仅高频且约定俗成的操作可纯图标；低频、抽象、危险或不可逆操作保留简短文字或确认 | [ADR-0007](adr/0007-v2-app-wide-theme-model.md) |
| `V2-D071` | 极光、渐变和粒子仅用于 Logo / 图标资产及少量图标状态，不强制进入页面背景或普通界面图层 | [ADR-0007](adr/0007-v2-app-wide-theme-model.md) |
| `V2-D072` | 图标效果以 60fps、无持续 jank / 过热 / 异常耗电为底线；减少动画时静止或降级 | [ADR-0007](adr/0007-v2-app-wide-theme-model.md) |
| `V2-D073` | 关键操作图标至少 4.5:1、目标 7:1；普通图标至少 3:1，普通导航不再默认使用 62% 白 | [ADR-0007](adr/0007-v2-app-wide-theme-model.md) |
| `V2-D074` | 效果图先交付每主题 1 张风格帧，确认后再扩展为每主题 3 个代表界面的明暗稿和共享组件状态板 | [ADR-0007](adr/0007-v2-app-wide-theme-model.md) |
| `V2-D075` | 使用本地 HTML/SVG + 无头浏览器生成确定性效果图；当前不安装外部设计 Skill / MCP | [ADR-0007](adr/0007-v2-app-wide-theme-model.md) |
| `V2-D076` | App Logo / launcher 沿用当前 W38「极光帘幕」自适应图标；本轮 A/B/C 仅作未采用探索，停止新 Logo 派生。界面动作图标是否重绘不在本决定内，另案确认 | [ADR-0007](adr/0007-v2-app-wide-theme-model.md) |
| `V2-D077` | 界面动作图标只重绘低对比、有歧义或高频动作，不做整包换风格；App Logo 保持当前 | [ADR-0007](adr/0007-v2-app-wide-theme-model.md) |
| `V2-D078` | 冻结当前一级导航 IA：手机底栏首页 / 视频 / 音乐 / 书架；平板侧轨；媒体库、下载、控制台保留在侧栏或抽屉 | [ADR-0007](adr/0007-v2-app-wide-theme-model.md) |
| `V2-D079` | 首页以继续观看 / 阅读 / 收听为单一主焦点，下方保留紧凑“最近内容”，并提供三域快捷入口；禁止仪表盘式堆卡 | [ADR-0007](adr/0007-v2-app-wide-theme-model.md) |
| `V2-D080` | 信息密度采用双档：内容浏览页偏影院沉浸，设置 / 库管理偏高效率；共享 4/8 网格、48dp 触控底线与同一组件状态语义 | [ADR-0007](adr/0007-v2-app-wide-theme-model.md) |
| `V2-D081` | 接受 38 条 UX 最终等级 `21 MUST / 3 SHOULD / 2 LATER / 12 OUT`，以及页面 / 状态 / 响应式 / 图标词表 / 五主题约束需求包；仅生效需求基线，不批准 G1、实现或发布 | `V2_PLAN.md` §5.3.15～§5.3.17 |
| `V2-D082` | 以外部 527–529 的实际项目边界作为 2.0 MVP 范围权威，403“全部核心模块 P0”作为完整候选全集；86 条全部保留，最终等级仍需整包确认 | `V2_PLAN.md` §5.4.7；用户于 2026-11-02 23:58:02 +08:00 原话“1–6 全部按推荐接受” |
| `V2-D083` | 2.0 格式包：EPUB / TXT / PDF / CBZ 为 `MUST`；HTML / FB2 为 `LATER`；UMD / CHM 为 `OUT` | `V2_PLAN.md` §5.4.7；用户于 2026-11-02 23:58:02 +08:00 原话“1–6 全部按推荐接受” |
| `V2-D084` | 阅读器默认沉浸；控制层顶层只保留返回与更多，书名可显示；宽屏重排内容自动双页，窄屏 / 手机横屏不强行双页；继续服从安全区、48dp 与可访问性基线 | `V2_PLAN.md` §5.4.7；用户于 2026-11-02 23:58:02 +08:00 原话“1–6 全部按推荐接受” |
| `V2-D085` | 分页默认左 / 中 / 右 1/3 分别上一页 / 控制层 / 下一页；双击书签为可选开关且默认关闭；退役双击左右翻页与中间适宽，适宽移入显式缩放控件 | `V2_PLAN.md` §5.4.7；用户于 2026-11-02 23:58:02 +08:00 原话“1–6 全部按推荐接受” |
| `V2-D086` | 本地导入复用现有 SAF 本地媒体库；文件选择器只添加文件 / 文件夹，不新建阅读器私有导入书库 | `V2_PLAN.md` §5.4.7；用户于 2026-11-02 23:58:02 +08:00 原话“1–6 全部按推荐接受” |
| `V2-D087` | 阅读显示主题保持纸色 / 护眼 / 深色 / OLED / 跟随系统五套策展值，不开放任意背景 / 正文 / 链接 / 高亮颜色；外框继续服从 App 主题 | `V2_PLAN.md` §5.4.7；用户于 2026-11-02 23:58:02 +08:00 原话“1–6 全部按推荐接受” |
| `V2-D088` | EPUB/TXT TTS 基础闭环进入 2.0，并且运行时必须纯本地、完全离线、不依赖网络；只可展示实际可离线工作的音色。播放 / 暂停、上一句 / 下一句、语速、句级高亮与回到原文生效，定时与词级高亮后置。具体成熟实现方案由后续公开资料调研与 G1 Spike 评估，不在需求门选型 | `V2_PLAN.md` §5.4.8；用户于 2026-11-03 00:19:04 +08:00 原话“关于tts的实现方案后续可以查网上的成熟方案，tts功能是纯本地的不依赖网络。其他的全部同意。” |
| `V2-D089` | EPUB/TXT 划词批注进入核心闭环：长按选词、就近工具栏、至少三色具名高亮、笔记、复制、编辑 / 删除与排版无关锚点；PDF 固定页矩形批注保留；CBZ 固定页批注、导出、翻译、词典后置 | `V2_PLAN.md` §5.4.8；用户于 2026-11-03 00:19:04 +08:00 原话“其他的全部同意” |
| `V2-D090` | PDF 搜索保留 `MUST`；EPUB/TXT 全书搜索为 `SHOULD`，不阻塞 MVP；CBZ 文本搜索 `OUT`；无文本能力必须显式说明 | `V2_PLAN.md` §5.4.8；用户于 2026-11-03 00:19:04 +08:00 原话“其他的全部同意” |
| `V2-D091` | SAF 文件只引用用户源文件；元数据尽力提取并可解释回落；移出媒体库不删源文件；身份包含来源作用域，内容指纹用于稳定关联与重复提示，不静默合并异源记录 | `V2_PLAN.md` §5.4.8；用户于 2026-11-03 00:19:04 +08:00 原话“其他的全部同意” |
| `V2-D092` | 性能 / 缓存 / 取消 / 恢复作为 2.0 发布阻断原则：解析和首次分页不阻塞主线程、稳定键落盘缓存、设置变化按 locator 回位、翻页不等网络、内存不随页数线性增长、离页取消、进程重建恢复、离线核心闭环无 FATAL / ANR / Input timeout；具体数值与样本矩阵留 G1 | `V2_PLAN.md` §5.4.8；用户于 2026-11-03 00:19:04 +08:00 原话“其他的全部同意” |

## Open

`V2-REQ-UX` 仍无 Open；`V2-Q-UX-60` 已由 `V2-D081` 关闭，`V2-Q-UX-56`～`V2-Q-UX-59`
已由 `V2-D077`～`V2-D080` 关闭。

`V2-REQ-READER` 首轮 `V2-Q-READER-01`～`06` 已由 `V2-D082`～`V2-D087` 关闭，第二轮
`V2-Q-READER-07`～`11` 已由 `V2-D088`～`V2-D092` 关闭。用户于 2026-11-03 00:19:04
+08:00 回复 TTS 必须纯本地且不依赖网络、实现方案后续调研，并确认“其他的全部同意”。
这不等于接受 86 条候选的最终等级整包。

当前无已登记 Open。第三轮前沿须由新负责人先派全新子代理按白名单只读检查文档、用户确认
范围与依赖，再由负责人使用 `grilling` 登记并询问；负责人本会话不再逐篇读长文档。
