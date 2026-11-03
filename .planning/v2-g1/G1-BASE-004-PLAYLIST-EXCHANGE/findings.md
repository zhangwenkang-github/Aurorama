# 调研发现

只记录与本合同直接相关的短证据，不复制外部需求正文。

## 基线

- cwd 为唯一指定 worktree；branch `codex/v2-integration`，HEAD
  `6fc0a02995bb24c11237fe3b8753f21f06a57f72`，开工无已跟踪改动。
- 已读：Engramory 索引及 `aurorama-v2-requirements.md`；Q03/Q04 未关闭，G1 仍为静态增量阶段。

## 白名单证据

- `PROJECT_RULES` R020/R025/R027/R028：上下文 30/35 万门、阶段报告维度、角色拆单与
  双 SHA 后继门；本轮不新建负责人线程。
- `V2_PLAN` BASE-004：本地歌单与服务器歌单身份分离；版本化 JSON / M3U8；重复、缺曲、
  损坏和不支持项显式处理；不得静默删除、覆盖或新增第二套媒体身份。
- `V2_PLAN` §5.10.6 / §5.11.9：G1 只产静态合同，实现、设备、服务器写和发布仍留门。
- `V2_PLAN` §5.11.4 与 `G1-S2`：完整 server/user/library/item 请求身份，source 再追加
  `sourceToken`；未知 provenance 保留独立 key，不得用当前账号 / 标题猜测。
- `TASKS` / `DECISIONS`：BASE-004 已由 `V2-D196/D197` 保留为既有 `MUST`；Q03/Q04 未关闭，
  不新增产品决策或把静态证据写成实现 / G1 PASS。

## 生产边界

- 当前 worktree 无 `graphify-out/graph.json`，按约定以 `rg` 定位后用 3 个文件局部核对：
  `MusicEntities.kt`、`LocalPlaylistStorage.kt`、`PlaylistManager.kt`。
- 本地歌单行已有 `trackKind/serverId/userId/libraryId/itemId` 唯一索引，标题、URI 仅作快照；
  存储 port 已有单事务导入；当前未见生产 JSON / M3U8 交换 parser。
- `PlaylistManager.kt` 是服务器队列 / 播放展开职责，和本地歌单草稿存储不是同一身份域。
- 需在合同中标出的实现缺口：现有 `identityKey` 用裸 NUL 连接但无身份版本 / sourceToken /
  provenanceClass；JSON / M3U8 版本、未知字段、部分导入与冲突策略尚未生产实现。

## 合同结论

- v1 schema 的 68 个 property declaration 已由本地 Draft 2020-12 子集 validator 验证。
- 4 个媒体身份分支为 SERVER_SOURCE、LOCAL_SOURCE、SERVER_REQUEST_ONLY、UNKNOWN_LEGACY；
  完整 SOURCE 才能作为已解析 source 去重，标题、URI、当前账号均不可替代 provenance。
- M3U8 映射 34 行；roundtrip 3 lane / 69 行，显式统计为 48 PRESERVE、2 PRESERVE_OPAQUE、
  2 DEGRADE、17 LOSS。
- 11 个 payload fixture / 12 个场景覆盖缺曲、重复、冲突、跨来源同 itemId、标题碰撞、
  未知 required feature、高版本、损坏 JSON、partial M3U8、generic lossy 与 custom roundtrip。
- 首轮 99 项仅缺 partial 聚合 code；同 owner 修正后 99/99 PASS。
