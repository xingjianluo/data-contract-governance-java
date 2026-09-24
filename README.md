# 数据契约与元数据治理（data-contract-governance）

基于 Spring Boot 3 / Java 21 的本地存储版数据契约与字段级血缘治理服务。无需任何外部数据库，
契约版本、血缘节点/边均保存在进程内的并发存储中；支持多版本契约的兼容性判定、字段级血缘登记、
敏感级传播与冲突收敛，并保证并发与异常链路下结论一致、可复现、无部分写入。

## 1. 能力总览

| 能力 | 入口 | 结论 |
| --- | --- | --- |
| 契约多版本注册 / 兼容判定 | `POST /api/contracts/register` | `CREATED` / `IDEMPOTENT_REPLAY` / 拒绝（原因码） |
| 契约版本历史 | `GET /api/contracts/{name}/versions` | 按版本号升序 |
| 最新版本 | `GET /api/contracts/{name}/latest` | 不存在返回 404 |
| 血缘节点登记 | `POST /api/lineage/nodes` | 节点（按 id 幂等） |
| 血缘边登记 | `POST /api/lineage/edges` | `REGISTERED` / `DUPLICATE_REPLAY` / 拒绝（原因码） |
| 敏感级收敛查询 | `GET /api/lineage/nodes/{id}/sensitivity` | 有效级别 + 是否冲突 + 全部来源依据 |
| 血缘边列表 | `GET /api/lineage/edges` | 按登记时间排序 |

节点全局 id 形如 `contractName:version:fieldName`，例如 `orders:2:buyer_id`。

## 2. 契约兼容规则

注册时在请求体中声明兼容级别 `mode`：

- `BACKWARD`：新版本可读取旧数据（新消费者读旧生产者写出的数据）。
- `FORWARD`：旧版本可读取新数据（旧消费者读新生产者写出的数据）。
- `FULL`：同时满足 BACKWARD 与 FORWARD。
- `NONE`：不做兼容性约束，任何变更都允许登记（仍做字段名重复等结构校验）。

判定总是以「当前最新版本」为基线（首个版本天然兼容）。字段级规则如下：

| 变更 | BACKWARD | FORWARD | FULL | 拒绝原因码 |
| --- | --- | --- | --- | --- |
| 新增**可选**字段 | 兼容 | 兼容 | 兼容 | — |
| 新增**必填**字段 | 拒绝 | 兼容 | 拒绝 | `ADDED_REQUIRED_FIELD` |
| 删除**可选**字段 | 兼容 | 兼容 | 兼容 | — |
| 删除**必填**字段 | 兼容 | 拒绝 | 拒绝 | `REMOVED_REQUIRED_FIELD` |
| 字段类型变更（不做隐式提升） | 拒绝 | 拒绝 | 拒绝 | `FIELD_TYPE_CHANGED` |
| 可选 → 必填（收紧） | 拒绝 | 兼容 | 拒绝 | `REQUIRED_CONSTRAINT_TIGHTENED` |
| 必填 → 可选（放宽） | 兼容 | 拒绝 | 拒绝 | `REQUIRED_CONSTRAINT_RELAXED` |
| 重命名：新字段必填 | 拒绝 | 视旧字段 | 拒绝 | `RENAME_TARGET_REQUIRED` |
| 重命名：旧字段必填 | 视新字段 | 拒绝 | 拒绝 | `RENAME_SOURCE_REQUIRED` |

重命名通过新字段的 `renamedFrom` 显式声明（例如新字段 `full_name` 声明
`renamedFrom: "name"`）。重命名仅在旧字段存在且尚未被其他字段消费时配对成功，否则该字段按
「新增」处理；重命名不允许改变类型。

其他拒绝原因码：

- `DUPLICATE_FIELD_NAME`：同一版本内字段名重复。
- `VERSION_ALREADY_EXISTS`：同版本号已存在且载荷不同（幂等要求内容一致）。
- `VERSION_OUT_OF_ORDER`：新版本号不大于当前最大版本号（允许跳号，不允许回退/重号）。
- `INCOMPATIBLE_CHANGE`：兼容性判定未通过，响应消息中附带全部字段级违规项。

结论确定性：字段按名称排序处理，违规列表按 `(fieldName, reason)` 排序，
因此同一份「上一版本 + 候选版本」无论提交多少次、何种并发交错，结论与违规列表完全一致。

### 幂等

对 `(contractName, version, mode, fields)` 完全相同的重复提交返回
`IDEMPOTENT_REPLAY`，不再新增版本；同版本号但内容不同则以 `VERSION_ALREADY_EXISTS`
拒绝，原版本保持不变。

## 3. 血缘规则

### 3.1 登记校验（按顺序短路，原因可区分）

1. 入参为空 / source 等于 target → `INVALID_EDGE`(400) / `SELF_LOOP`(409)。
2. 上游或下游节点未登记 → `DANGLING_REFERENCE`(409)，边不写入。
3. 同一 `source → target` 已存在 → `DUPLICATE_REPLAY`：
   - transform 相同：纯幂等回放；
   - transform 不同：保留首次登记的边与 transform，响应带 `DUPLICATE_REGISTRATION`
     标记，不新增边、不覆盖原边。
4. 新增边后若形成环（target 已可沿既有边到达 source）→ `CYCLE_DETECTED`(409)，
   响应消息给出成环路径，边不写入，既有边不受影响。

任何拒绝都不会留下部分写入：节点与边仅在全部校验通过后才写入并发存储。

### 3.2 敏感级传播与冲突收敛

敏感级别（由低到高）：`PUBLIC < INTERNAL < CONFIDENTIAL < SECRET`，另有
`UNKNOWN` 表示未声明（不参与取大）。

- 传播方向：沿血缘边由上游向下游传播（可多级传递，例如
  `SECRET 源 → mid → sink`，则 mid、sink 的有效级别均为 SECRET）。
- 收敛规则：节点有效级别 = max(自身声明, 全部上游根来源的级别)。
- 冲突：当有效级别由**多个互不相同的根来源**（自身声明或无上游的源头节点）同时贡献时，
  置 `conflict=true`。级别本身仍按「取最高」稳定收敛，不会因冲突而不确定；
  `contributions` 保留每个根来源的节点 id、级别以及从当前节点到该来源的完整传播路径，
  作为冲突依据供人工裁决。最高级来源唯一时不标记冲突。
- 可复现：查询对当前存储快照做一次只读 DFS，入边按 sourceId 排序、结论按根来源排序，
  对同一份存储重复调用得到完全相同的结果。

## 4. 并发与异常链路语义

- 契约注册：同一 `contractName` 的注册在该名称的锁上串行化（不同名称互不阻塞）；
  版本序检查、兼容性判定全部先于落盘，被拒绝/抛异常的请求不产生任何写入。
  底层 `putIfAbsent`（ConcurrentHashMap）是原子去重的最后防线——即使并发提交同一版本，
  也恰好一个 `CREATED`，其余为 `IDEMPOTENT_REPLAY` 或 `VERSION_ALREADY_EXISTS`，
  不会出现重复版本号或丢失更新。
- 血缘边登记：全局边锁 + `putIfAbsent`，保证环检测与写入的原子性；并发重复登记同一条边
  只会有一次写入。
- 可复现：所有判定纯函数化、集合遍历确定排序，重复调用结论一致。

## 5. HTTP 示例

```bash
# 注册契约 v1（BACKWARD）
curl -s -X POST localhost:8080/api/contracts/register \
  -H 'Content-Type: application/json' \
  -d '{"contractName":"orders","version":1,"mode":"BACKWARD",
       "fields":[{"name":"id","type":"LONG","required":true},
                 {"name":"note","type":"STRING","required":false}]}'

# 注册血缘节点（声明敏感级）
curl -s -X POST localhost:8080/api/lineage/nodes -H 'Content-Type: application/json' \
  -d '{"contractName":"orders","version":1,"fieldName":"id","declaredSensitivity":"SECRET"}'

# 登记字段级血缘边
curl -s -X POST localhost:8080/api/lineage/edges -H 'Content-Type: application/json' \
  -d '{"sourceId":"orders:1:id","targetId":"dwh:1:order_id","transform":"copy"}'

# 查询下游字段的敏感级收敛结论
curl -s localhost:8080/api/lineage/nodes/dwh:1:order_id/sensitivity
```

拒绝响应统一形如：

```json
{ "timestamp": "...", "rejectionCode": "INCOMPATIBLE_CHANGE", "message": "...违规明细..." }
```

业务拒绝返回 409，非法入参返回 400。

## 6. 本地验证

前置：JDK 21、Maven（仓库自带 Spring Boot Wrapper 依赖由 Maven 解析）。

```bash
mvn compile          # 编译
mvn test             # 运行全部测试（27 个）
mvn spring-boot:run  # 本地启动，默认端口 8080
```

按边界场景组织的测试：

- `ContractCompatibilityCheckerTest`：新增/删除、类型变更、必填性收紧/放宽、
  重命名在 BACKWARD/FORWARD/FULL/NONE 下的结论，以及结论确定性（8 个）。
- `ContractServiceTest`：不兼容拒绝不落盘、幂等重放、同版本异载荷拒绝、版本乱序拒绝，
  以及两个并发场景——同版本并发注册（恰好一个 CREATED）、多线程争抢下一版本号
  （不重复、不丢失）（7 个）。
- `LineageServiceTest`：悬空引用、自环、成环拒绝且边不落盘、重复登记幂等/冲突标记，
  敏感级多级传播、多根来源冲突收敛与依据保留、结论可复现（9 个）。
- `ApiIntegrationTest`：通过 HTTP 入口验证注册→幂等→不兼容 409、成环 409 与
  敏感级接口传播（2 个）。

测试日志在每个用例打印输入（`[CASE] ... input=...`）与判定依据
（`[compat] ... violations=...`、`[register] rejected ... reason=...`、
`[lineage] reject ... reason=...`、`[sensitivity] ... effective=... contributions=...`），
可在 `target/surefire-reports/` 下查看。
