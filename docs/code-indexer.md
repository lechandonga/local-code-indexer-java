# 本地代码索引与检索服务

一套仅运行在本机、零外部依赖的代码仓库上下文服务：扫描配置指定的本地目录建立文本索引，
通过 HTTP 提供关键字/符号检索，供开发助手读取仓库内容。不访问网络、不依赖数据库或任何外部进程。

## 1. 索引与刷新行为

### 扫描

- 扫描根目录由 `code-indexer.root` 指定（绝对路径），递归遍历所有普通文件；
  目录遍历顺序与文件处理结果均按路径排序，保证结果确定。
- 目录名命中 `code-indexer.exclude-dir-names`（默认 `.git,target,build,node_modules,.idea,.gradle`）的整个目录被忽略。
- 文件按行切分（UTF-8），行号从 1 开始，保存在内存快照中。

### 增量刷新

刷新基于上一次快照做增量判定，判据为 **文件大小 + 最后修改时间（mtime）**：

- 未变化（size 与 mtime 都相同）：直接复用已解析条目，不重复读盘解析，计入 `unchangedCount`；
- 新增或被修改：重新读取解析，计入 `reindexedCount`；
- 在上次快照中存在、本次扫描已不存在：从新快照移除，计入 `deletedCount`，之后不能再被检索到。
- 无论有无变化，每次成功刷新都会产生一个新版本号 `revision`（单调递增），并原子替换当前快照。

注意：请保留文件的 mtime（普通编辑工具会更新 mtime）。若外部工具以相同 size、相同 mtime 改写文件内容，
本服务按约定视为未变化。

### 跳过原因（按文件可区分，不允许悄悄跳过）

每个被跳过的文件都记录相对路径、稳定原因码与简短说明，可通过 `GET /api/index/skipped` 查询；
任何单个文件的问题都不会导致整次刷新失败。

| 原因码 | 含义 |
| --- | --- |
| `NOT_REGULAR_FILE` | 不是普通文件（设备、管道、套接字、指向目录的链接等） |
| `FILE_TOO_LARGE` | 文件大小超过 `code-indexer.max-file-bytes` |
| `BINARY_OR_UNDECODABLE_FILE` | 探测字节含 NUL 或可疑控制字符占比 > 30%，或不是合法 UTF-8 |
| `UNREADABLE_FILE` | 读失败：IO 错误、权限不足、扫描过程中消失等 |
| `LINK_OUTSIDE_ROOT` | 符号链接损坏，或 `toRealPath()` 解析后落在索引根目录之外（防止越界读取） |

二进制判定固定且可解释：检查文件前 `code-indexer.binary-probe-bytes` 字节，
出现 NUL 字节即判二进制；否则统计非文本控制字符（`\t \n \r \f ESC` 除外）占比，超过 30% 判二进制。

## 2. 查询与排序规则

### 两种模式

- `KEYWORD`（默认）：大小写不敏感的子串匹配，适合关键字搜索；可传 `caseSensitive=true` 改为大小写敏感。
- `SYMBOL`：默认大小写敏感，按“符号 token”匹配。token 字符为 `[A-Za-z0-9_]`，
  命中位置两侧必须是 token 边界（非 token 字符或行首/行尾），因此搜 `findMe` 不会命中
  `findMeCount` 或 `doNotFindMe`，但会命中字符串字面量中带标点边界的 `findMe`。

### 返回内容

每条命中包含：`file`（相对根目录路径）、`line`（行号，从 1 开始）、`matchedTerm`、
`matchStartColumn`、`fragment`（命中点两侧各 `code-indexer.fragment-window-chars` 字符的片段）、
`fragmentStartColumn` 以及查询所基于的 `revision`。同一行多个命中返回多条记录。

### 排序（确定性）

结果固定按以下顺序排序，因此同一查询、同一快照下多次执行结果完全一致：

1. 文件相对路径字典序（UTF-16 比较）；
2. 行号升序；
3. 命中起始列升序；
4. 命中文本字典序。

### 参数校验

- `q` 为空/空白 → HTTP 400 `EMPTY_QUERY`；长度超过 200 → `QUERY_TOO_LONG`。
- `mode` 不是 `KEYWORD`/`SYMBOL` → HTTP 400 `INVALID_MODE`。
- `limit` 非正 → `INVALID_LIMIT`；超过 `code-indexer.max-limit` → `INVALID_LIMIT`。
  默认 `code-indexer.default-limit`；被截断时响应中 `truncated=true`，`totalHits` 为截断前总数。

## 3. 并发语义

- **单飞（single-flight）**：同一时间最多一个刷新任务。`POST /api/index/refresh` 参数：
  - `reuse=true`（默认）：已有刷新进行中时复用同一个任务，响应体 `reused=true`，最终状态一致；
  - `reuse=false`：已有刷新进行中时直接返回 HTTP 409 `REFRESH_ALREADY_RUNNING`，不会启动第二个写任务。
  - `wait=true`（默认）：阻塞到刷新结束返回最终状态；`wait=false`：立即返回 202（新任务已接受）
    或 200（`reused=true`，复用了进行中任务）。
- **快照隔离**：已发布快照不可变，新快照构建完成后一次性原子替换。刷新期间所有查询要么看到
  上一次刷新的完整结果、要么看到新一次刷新的完整结果，不会看到构建中的半成品或新旧混杂。
  响应里的 `revision` 可用于确认本次查询基于哪个版本。
- **失败语义**：刷新失败（如根目录不可用）时状态为 `FAILED`，错误被归一化为稳定错误码
  （`ROOT_UNAVAILABLE`、`ROOT_ACCESS_DENIED`、`ROOT_NOT_CONFIGURED`、`INTERNAL_ERROR`），
  不向调用方透传原始异常堆栈；已经发布的旧快照继续可查。

### 刷新状态

`GET /api/index/status` 返回：`state`（`RUNNING` / `SUCCESS` / `FAILED` / `NEVER_RUN`）、
`revision`、开始/结束时间（ISO-8601）、`durationMillis`、`indexedCount`、`skippedCount`、
`unchangedCount`、`reindexedCount`、`deletedCount`，失败时还有 `errorCode` 与 `message`。

错误响应统一为：`{"errorCode": "...", "message": "...", "status": 400, "timestamp": "..."}`。

## 4. HTTP 接口一览

| 方法与路径 | 说明 |
| --- | --- |
| `POST /api/index/refresh?wait=&reuse=` | 触发/复用刷新 |
| `GET  /api/index/status` | 查询刷新任务状态与统计 |
| `GET  /api/index/skipped` | 最近快照的跳过文件明细与处理数/跳过数 |
| `GET  /api/search?q=&mode=KEYWORD%7CSYMBOL&caseSensitive=&limit=` | 检索代码内容 |

## 5. 配置项（`application.properties`，前缀 `code-indexer`）

| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| `root` | 空 | 索引根目录绝对路径；为空时不自动建索引，刷新返回 `ROOT_NOT_CONFIGURED` |
| `auto-refresh-on-startup` | `true` | 启动后自动刷新一次 |
| `max-file-bytes` | `1048576`（1 MiB） | 单文件大小上限，超过跳过 |
| `binary-probe-bytes` | `8192` | 二进制探测的前缀字节数 |
| `fragment-window-chars` | `60` | 命中片段两侧保留字符数 |
| `default-limit` | `200` | 查询默认条数上限 |
| `max-limit` | `1000` | 查询允许的最大条数 |
| `exclude-dir-names` | `.git,target,build,node_modules,.idea,.gradle` | 按目录名整体忽略 |

## 6. 本地验证方式

无需任何外部服务。

```bash
mvn test

mvn spring-boot:run \
  -Dspring-boot.run.arguments=--server.port=8080,--code-indexer.root=$HOME/works/some-repo

curl -X POST 'http://127.0.0.1:8080/api/index/refresh?wait=true'
curl 'http://127.0.0.1:8080/api/index/status'
curl 'http://127.0.0.1:8080/api/index/skipped'
curl 'http://127.0.0.1:8080/api/search?q=SpringApplication&mode=SYMBOL'
```

也可以直接在 `src/main/resources/application.properties` 中固定 `code-indexer.root` 后 `mvn spring-boot:run`。

测试说明：

- 全部测试数据使用 JUnit 5 `@TempDir` 临时目录构造（含不可读文件、二进制文件、超大文件、
  指向目录外的符号链接），不依赖网络与外部服务。
- `RefreshServiceTest` 覆盖：首次建索引、增量复用/重解析/删除、删除后不可检索、
  四类跳过边界、根目录失败归一化、刷新单飞（复用与 409 拒绝）、刷新期间查询的快照一致性。
- `SearchServiceTest` 覆盖：关键字/符号匹配、确定性排序、参数校验、分页截断。
- `IndexApiIntegrationTest` / `IndexApiErrorEnvelopeTest` 覆盖完整 HTTP 契约与稳定错误信封。
- 测试日志由 `TestLoggingExtension` 输出：套件开始处标注业务域，每个用例打印开始、PASSED/FAILED
  与耗时，套件结束打印通过/失败统计。
