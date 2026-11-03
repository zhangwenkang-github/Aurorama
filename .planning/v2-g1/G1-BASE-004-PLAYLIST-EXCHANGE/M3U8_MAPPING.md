# M3U8 交换映射矩阵

## 边界

这里的 `.m3u8` 是**静态音频曲目清单**，不是 HLS 媒体分片播放列表。生产 SAF 读取、
流媒体认证和播放实现不在本轮。所有 URI、标题和注释都只作定位或展示；稳定媒体身份来自
`identity`，不得从标题、文件名或 EXTINF 推断。

## 规范行序

```text
#EXTM3U
#AURORAMA-CONTRACT:aurorama.local-playlist.exchange;schema=1
#AURORAMA-DOCUMENT-V1:<base64url-json>
#AURORAMA-ENTRY-V1:<base64url-json>
#EXTINF:<seconds>.<millis>,<display-title>
<uri>
```

- 导出编码固定 UTF-8、无 BOM、LF；行内不得出现裸 CR / LF。
- `AURORAMA-DOCUMENT-V1` 含顶层元数据、播放列表草稿和扩展字段，不含 `entries`。
- `AURORAMA-ENTRY-V1` 含一条完整 JSON entry；下一行必须是 `EXTINF`，其后必须是 URI。
- base64url 使用无 padding 的 RFC 4648 §5 字母表；解码后必须是 UTF-8 JSON 对象。
- 没有 AURORAMA 标记时仍可预览为 generic M3U8，但每条曲目只能成为
  `UNKNOWN` / `NO_MERGE` / `IDENTITY_LOST`，不得据标题与现有曲目合并。

## JSON 字段映射

| JSON 字段 | M3U8 表达 | 有 AURORAMA 标记 | 标记被外部工具剥离 | 规则 |
|---|---|---|---|---|
| `format` / `schemaVersion` | `AURORAMA-CONTRACT` + document marker | 保留 | 丢失 | 版本不匹配直接拒绝 |
| `producer` / feature 列表 | document marker | 保留 | 丢失 | 未知 required feature 拒绝 |
| `playlist.identityDomain` | document marker | 保留 | 丢失 | 只能是 `LOCAL_DRAFT` |
| `playlist.localPlaylistId` | document marker | 保留 | 丢失 | 不与服务器歌单 ID 对齐 |
| `playlist.serverPlaylistId` | document marker，值必须为 `null` | 保留 | 丢失 | 需要服务器歌单时停 D |
| `playlist.name` | document marker | 保留 | 丢失 | EXTINF 标题不能代表歌单名 |
| `playlist.originContext` | document marker | 保留 | 丢失 | 只作来源审计，不决定当前可见身份 |
| `playlist.revision/time` | document marker | 保留 | 丢失 | 不把文件 mtime 当修订号 |
| entry 顺序 / `position` | 物理行序 | 保留 | 可保留 | 导入后必须派生连续、唯一顺序 |
| `entryId` | entry marker | 保留 | 丢失 | 外部工具重写后不得猜测原 entryId |
| server source identity | entry marker | 保留 | 身份丢失 | 完整 `server+user+library+item+source` 才能 source 去重 |
| server request identity | entry marker | 保留 | 身份丢失 | `sourceToken=null`，只能 request 层提示，不能宣称可播放 |
| local source identity | entry marker | 保留 | 身份丢失 | `localItemId+persistentUri`；指纹只作辅助 |
| unknown legacy identity | entry marker | 保留 | 仍为 unknown | `legacyRecordKey` 独立，禁止跨来源折叠 |
| `display.title` | EXTINF 逗号后的文本 + entry marker | 保留 | 展示可保留，身份不恢复 | 标题只能 fallback |
| `display.durationMs` | EXTINF 秒数（毫秒小数） | 由 marker 精确恢复 | 至少毫秒级降级 | 超长或非法时长显式告警 |
| album / artists / artwork | 通常仅 entry marker | 保留 | 丢失 | 不塞入 EXTINF 冒充标准字段 |
| `availability.status` | entry marker | 保留 | 丢失并降级为待解析 | `MISSING` 不得转成删除 |
| `locator.uri` | URI 行 | 与 marker 对账 | 保留原始可见 URI | URI 不单独决定跨来源身份 |
| `extensions` / 未知 optional 字段 | document/entry marker | 标记内保留 | 丢失 | JSON→JSON 可原样保留；M3U8 不保证 |
| 重复 / 冲突提示 | 由导入结果计算 | 条目不折叠 | 保留条目，身份丢失 | 选合并策略必须停 B |

## EXTINF、URI 与注释行为

| 语法 | 解析行为 | 导出行为 | 不可表达 / 错误 |
|---|---|---|---|
| `#EXTM3U` | 必需文件头；缺失记 `MISSING_EXTM3U` | 总是首行 | 不把普通 URI 文本当已识别交换文件 |
| `#EXTINF:seconds,title` | 必需；逗号后全部作 title | 用时长秒数与标题生成 | 负数、NaN、无穷或缺少 URI 为 partial/corrupt |
| 裸 URI 行 | 若前一有效行不是 EXTINF，记 `MISSING_EXTINF` | 每首恰好一行 URI | 仍保留为 candidate，不静默跳过 |
| 空行 | 忽略，不影响曲目序 | 不输出 | 不作为分隔身份 |
| 普通 `#comment` | 不参与身份；计数并返回 `COMMENT_IGNORED` | 不复制外部注释 | 注释可能含秘密，不能默认持久化 |
| `#AURORAMA-*` | 严格解析并核对的扩展标记 | 按规范输出 | 冲突 marker、坏 base64 或坏 JSON 为 partial |
| 重复 marker | 同 entry 多条冲突则拒绝原子导入 | 每 entry 至多一条 | 不能以“最后一条胜出”覆盖 |
| URI 中的认证参数 | 导入保留但标记 `SENSITIVE_LOCATOR_PRESENT` 并脱敏展示 | 删除认证参数值并标降级 | 需要带凭据播放或 SAF 解析时停 C/D |

## `#EXT-X-*` 指令

| 指令 | 行为 |
|---|---|
| `#EXT-X-VERSION:<n>` | 识别为元数据；不改变曲目身份；导出默认不生成 |
| `#EXT-X-TARGETDURATION` / `#EXT-X-MEDIA-SEQUENCE` / `#EXT-X-DISCONTINUITY*` / `#EXT-X-ENDLIST` | 视为 HLS 媒体列表语义，结果 `HLS_PLAYLIST_UNSUPPORTED`；不压平成音频曲目 |
| `#EXT-X-STREAM-INF` / `#EXT-X-I-FRAME-STREAM-INF` / `#EXT-X-MEDIA` | 视为 master playlist；拒绝作为本地歌单导入 |
| `#EXT-X-KEY` / `#EXT-X-SESSION-KEY` / `#EXT-X-MAP` / `#EXT-X-BYTERANGE` | 涉及密钥、初始化段或字节段；拒绝并清除展示中的密钥材料 |
| 其他未知 `#EXT-X-*` | 返回 `UNSUPPORTED_EXT_X_DIRECTIVE`；默认不写库，禁止无声忽略 |

## 异常与提交

- UTF-8 BOM、CRLF 输入可读，但规范化导出为无 BOM LF；无效 UTF-8 为 `CORRUPT_ENCODING`。
- 任一损坏、HLS 指令或 marker 校验失败时只能产生 preview；默认 `writesCommitted=0`。
- `partial-import` 若用户要求保留可识别子集，属于合并/导入策略 B；所有被跳过行必须带行号与原因。
- 通用播放器通常会丢弃注释。因此“JSON→M3U8→JSON 完全保留”只在本 App 的 marker 未被重写时成立；
  外部工具剥离后必须显式标为降级/丢失，不能宣称无损。
