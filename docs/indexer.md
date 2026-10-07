# 本地代码索引与检索服务

纯本机运行的代码索引服务：扫描配置指定的本地代码目录，按文件建立内存索引，
通过 HTTP 接口对外提供刷新、状态查询与关键字/符号检索。不依赖任何外部服务、
数据库或网络。

## 1. HTTP 接口

服务默认只绑定回环地址 `server.address=127.0.0.1`，不对外暴露。

### 1.1 触发刷新

```
POST /api/index/refresh?wait=true
```

- `wait=true`（默认）：同步等待本次刷新结束，返回最终状态（`succeeded`/`failed`）。
- `wait=false`：刷新在后台执行，立即返回当前状态（通常为 `running`）。

同一时间最多一个刷新任务在执行：

- `local-indexer.refresh-conflict-policy=reject`（默认）：已有刷新进行中时，
  新请求返回 `409 refresh_already_running`，不会产生第二个写索引的任务。
  请改用 `GET /api/index/status` 轮询进度。
- `...=join`：新请求复用进行中的刷新任务，阻塞等待同一份结果，
  响应体 `joinedExisting=true`。无论多少请求 join，底层只扫描一次。

### 1.2 查询刷新状态

```
GET /api/index/status
```

返回字段：

| 字段 | 含义 |
| --- | --- |
| `state` | `idle`（从未刷新）/`running`/`succeeded`/`failed` |
| `generation` | 刷新代次，每次触发（含失败）单调递增 |
| `durationMillis` | 已结束任务的总耗时；进行中为已运行时长 |
| `indexedCount` / `skippedCount` | 最近一次成功快照的已索引文件数 / 跳过文件数 |
| `reprocessedCount` / `reusedCount` | 本次重新解析的文件数 / 未变化而复用的文件数 |
| `skippedFiles` | 跳过明细（路径、稳定原因码、说明，最多 200 条） |
| `errorCode` / `errorMessage` | 失败时的稳定错误码与安全消息，不含原始堆栈 |

### 1.3 检索

```
GET /api/search?q=findTarget&mode=symbol&limit=50
```

- `q`：必填，去空白后不能为空，长度不超过 200，否则 `400 invalid_argument`。
- `mode`：`keyword`（默认，大小写不敏感的子串匹配）或 `symbol`
  （按标识符边界 `\b` 匹配，大小写敏感）。其他值返回 `400 invalid_argument`。
- `limit`：可选正整数，超过配置上限时自动收敛到上限；非正整数返回 `400`。

命中返回 `file`（相对根目录路径）、`lineNumber`（从 1 开始）、
`snippet`（命中点两侧各保留 `local-indexer.snippet-context-chars` 个字符，
截断处以 `...` 表示）、`matchType`、`rankScore`，以及本次查询基于的
`snapshotGeneration`。

索引尚未建立时查询返回 `400 invalid_argument`，提示先刷新。

## 2. 索引与刷新行为约定

### 2.1 增量刷新

每次刷新递归遍历根目录，与上一代快照按“相对路径 + 文件大小 + 修改时间 +
SHA-256 内容指纹”比较：

- 未变化的文件：直接复用上次的解析结果（`reusedCount`），不重复读取/解析；
- 新增、内容变化的文件：重新读取解析（`reprocessedCount`）；
- 已删除的文件：不进入新快照，刷新完成后立刻不可再被检索到；
- 单个文件的任何问题都不会让整次刷新失败，只记录为一条可区分的跳过项。

只有根目录本身不可用（不存在、不是目录）或整目录遍历失败时，刷新才标记为
`failed`；此时旧快照保留，查询仍然可用。

### 2.2 跳过原因码

| reason | 触发条件 |
| --- | --- |
| `outside_root` | 符号链接（文件或目录）解析后指向根目录之外 |
| `symlink_loop` | 符号链接形成环或无法安全解析 |
| `too_large` | 文件大小超过 `local-indexer.max-file-size-bytes`（默认 5 MiB） |
| `binary` | 前 8 KiB 含 NUL 字节，或内容不是合法 UTF-8 文本 |
| `not_regular_file` | 设备文件、套接字等非普通文件 |
| `unreadable` | 权限不足或读取时发生 I/O 异常 |
| `ignored_extension` | 配置了扩展名白名单且该文件扩展名不在白名单内 |

注意：配置白名单时，扩展名检查先于内容检测，因此不在白名单的二进制文件
记为 `ignored_extension`；白名单为空时才会直接记 `binary`。

## 3. 查询与排序规则

排序完全确定，与文件系统遍历顺序无关；同一查询多次执行结果序列一致：

1. `rankScore` 降序；
2. 分值相同，按文件相对路径字典序升序（快照内文件表为有序映射）；
3. 文件相同，按行号升序。

分值定义：

- symbol 模式：整行去空白后恰为查询 = 100；查询位于行首 = 80；
  典型定义/声明形态（`修饰符/类型 符号(`、`符号;`、`符号 =` 等）= 75；
  其他标识符边界命中 = 60。
- keyword 模式：命中位于去空白行首 = 50；位于词边界 = 30；普通子串 = 10。

## 4. 并发与一致性语义

- 刷新在独立单线程（`local-indexer-refresh`，守护线程）中执行；
  刷新许可（单许可信号量）跨越整个扫描周期持有，保证同一时间只有一个刷新。
- 查询与刷新互不阻塞：每次查询获取当前 `IndexSnapshot` 的不可变引用，
  在整次查询过程中只读这一份快照。快照在刷新成功后原子替换，
  因此查询永远只看到某一次刷新的完整结果——不会新旧混杂，也不会读到
  构建中的半成品；刷新期间查询继续返回旧快照。
- 刷新失败不清空旧快照，`state=failed` 的同时检索仍基于上一代成功快照。

## 5. 错误码（对外稳定，不透传原始异常）

| HTTP | errorCode | 场景 |
| --- | --- | --- |
| 400 | `invalid_argument` | 空查询、非法 mode、非法 limit、未建索引即查询 |
| 409 | `refresh_already_running` | reject 策略下刷新并发冲突 |
| 422 | `root_not_found` / `root_not_directory` | 配置的根目录不存在或不是目录 |
| 500 | `scan_failed` / `refresh_failed` | 扫描或刷新整体失败（详情只进服务端日志） |
| 500 | `internal_error` | 未预期异常的兜底归一化 |

响应体形如：

```json
{"errorCode":"invalid_argument","message":"查询关键字 q 不能为空","path":"/api/search","timestamp":"..."}
```

## 6. 可配置项

| 配置键 | 默认值 | 说明 |
| --- | --- | --- |
| `local-indexer.root-path` | `.` | 待索引的本地目录（绝对或相对工作目录） |
| `local-indexer.max-file-size-bytes` | `5242880` | 单文件大小上限，超出跳过 |
| `local-indexer.snippet-context-chars` | `40` | 命中片段两侧保留字符数 |
| `local-indexer.max-results` | `200` | 单次查询返回命中上限 |
| `local-indexer.include-extensions` | 空 | 扩展名白名单（不含点的小写扩展名，逗号分隔）；空表示不过滤 |
| `local-indexer.refresh-conflict-policy` | `reject` | 刷新并发冲突策略：`reject` 或 `join` |
| `server.address` | `127.0.0.1` | 只绑定本机回环地址 |
| `server.port` | `0` | 默认随机端口；实际使用可固定，如 `8080` |

## 7. 本地验证

运行全部单测（控制台会打印每个用例的业务分组与通过结果）：

```bash
mvn test
```

打包并对一个本地目录启动服务：

```bash
mvn package -DskipTests
java -jar target/local-code-indexer-java-1.0-SNAPSHOT.jar \
     --server.port=8080 \
     --local-indexer.root-path=/path/to/your/repo
```

手工调用：

```bash
curl -X POST "http://127.0.0.1:8080/api/index/refresh?wait=true"   # 首次建索引
curl "http://127.0.0.1:8080/api/index/status"                       # 状态与跳过明细/统计
curl "http://127.0.0.1:8080/api/search?q=findTarget&mode=symbol"   # 符号检索
```

### 单测覆盖的业务场景

- `CodeScannerTest`：新增/修改/删除/无变化复用、二进制、超大、扩展名过滤、
  越界符号链接、不可读文件（POSIX 权限，root 环境下自动跳过该用例）；
- `SearchServiceTest`：关键字大小写不敏感、符号边界匹配、片段/行号、
  排序确定性（重复查询结果一致）、空查询与非法参数拒绝；
- `IndexManagerTest`：reject 拒绝并发刷新（409）、join 复用进行中任务、
  刷新期间查询只见完整旧快照、失败归一化且旧快照保留、删除文件后不可检索；
- `IndexerHttpApiTest`：端到端 HTTP 行为与稳定错误码。

所有测试数据均由 JUnit `@TempDir` 在临时目录构造，不依赖外部服务或网络。
