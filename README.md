# 数据契约与元数据治理

基于 Spring Boot 的本地存储实现，提供数据契约多版本注册、兼容性判定、字段级血缘登记与敏感级别传播能力。所有入口（REST API 与内部调用）共享同一套注册中心，保证结构演进、并发注册与敏感数据传播得到一致且可解释的结论。

## 模块结构

```
model/     契约、字段、兼容级别、敏感级别等领域类型
compat/    兼容性判定（CompatibilityChecker），输出可区分的违规依据
registry/  契约注册中心（ContractRegistry），本地多版本存储，预检与注册共享同一评估核心
evolution/ 版本演进预检结论类型（PrecheckResult / PrecheckStatus）
lineage/   血缘登记与敏感传播（LineageRegistry）
error/     统一异常 GovernanceException + 可区分拒绝原因 RejectReason
api/       REST 入口与统一异常映射（拒绝原因 -> HTTP 409）
```

## 兼容级别的方向语义

兼容级别严格声明读写方向，判定只看“该方向上读者能否读到数据”：

- **BACKWARD**：新版读者必须能读取**旧版数据**（新读旧）；
- **FORWARD**：旧版读者必须能读取**新版数据**（旧读新）；
- **FULL**：两个方向都必须同时成立（双向兼容）；
- **NONE**：不做任何兼容约束，一律放行。

按上述方向推导的判定矩阵（`NONE` 一律放行）：

| 变更 | BACKWARD（新读旧） | FORWARD（旧读新） | FULL（双向） |
|---|---|---|---|
| 删除可选字段 | 允许 | 允许 | 允许 |
| 删除必填字段 | 允许 | **拒绝** | **拒绝** |
| 新增可选字段 | 允许 | 允许 | 允许 |
| 新增必填字段 | **拒绝** | 允许 | **拒绝** |
| 必填收紧（可选→必填） | **拒绝** | 允许 | **拒绝** |
| 必填放宽（必填→可选） | 允许 | **拒绝** | **拒绝** |
| 类型变更 | **拒绝** | **拒绝** | **拒绝** |

直觉解释：

- 删除字段只影响 FORWARD 方向——旧读者仍按旧结构读新数据；被删字段**必填**时新数据缺失它才会失败，本就可选时旧读者已容忍缺失。BACKWARD 方向旧数据仍带该字段，新读者忽略多余字段即可。
- 新增字段只影响 BACKWARD 方向——新读者读旧数据；**必填**新增在旧数据中不存在，可选新增时新读者已容忍缺失。
- 必填收紧（可选→必填）影响 BACKWARD（旧数据可能缺该字段）；必填放宽（必填→可选）影响 FORWARD（旧读者仍按必填读新数据，而新数据可能缺字段）。
- 类型变更两个方向都会破坏，BACKWARD/FORWARD/FULL 均拒绝。
- 重命名视为删除+新增（类型与必填性一致的删除/新增字段配对），按上表判定，结论中附非阻断的 `FIELD_RENAMED` 提示依据。

判定结论为 `CompatibilityReport(compatible, violations)`，每条违规携带 `ChangeType`（FIELD_ADDED / FIELD_REMOVED / TYPE_CHANGED / REQUIRED_TIGHTENED / REQUIRED_RELAXED / FIELD_RENAMED）、字段名与可读描述，拒绝原因彼此可区分。预检结论与正式注册被拒时返回的 `violations` 来自同一次判定，逐条一致。

## 演进预检（先看结论，再决定落库）

正式注册之前，可先对候选契约做只读预检（dry-run），系统回答“如果现在按这个版本注册，会不会被拒、依据是什么”：

```
POST /api/evolution/precheck
POST /api/evolution/precheck?referenceVersion=1
POST /api/evolution/precheck?compatibility=FORWARD
POST /api/evolution/precheck?referenceVersion=2&compatibility=FULL
```

- 请求体与正式注册完全相同（候选契约 JSON）；
- `referenceVersion` 可选：指定要参照的**已注册版本**，缺省以最新版本为参照；参照版本不存在返回 409 `VERSION_CONFLICT`；
- `compatibility` 可选：临时指定本次判定使用的兼容级别，缺省采用候选契约自身声明的级别（级别切换后结论随该级别的真实方向语义变化）。

返回 `PrecheckResult`：

| status | 含义 | version | idempotent |
|---|---|---|---|
| `REGISTERED` | 正式提交会被接受，将注册为新版本 | 预计分配的新版本号 | false |
| `IDEMPOTENT_REPLAY` | 内容命中某个已注册历史版本，正式提交会幂等返回该版本 | 已有版本号 | true |
| `REJECTED` | 正式提交会被拒绝，依据见 `violations`（按 ChangeType 区分） | null | false |

关键保证：

- **只读**：预检不写入、不分配版本、不改变任何已注册数据；
- **结论一致**：预检与正式注册共用注册中心内同一个评估核心（历史重放扫描 → 参照版本解析 → 兼容判定），同一输入在两条路径上的接受/拒绝结论、版本号与拒绝依据逐条一致。

## 注册语义（历史重放幂等与并发）

- **历史版本重放收敛**：候选契约内容指纹（名称+兼容级别+字段集合，字段顺序无关）与**任意一个已注册历史版本**完全一致时，无论当前最新版本是谁，都幂等返回该历史版本号并标记 `IDEMPOTENT_REPLAY`，不再做兼容判定，也不额外生成新版本。这覆盖 CI 重跑、发布系统补投旧版本等场景。幂等范围仅针对“内容逐字节等价”的提交；内容与所有历史版本都不同即按新版本处理，并且**仍必须通过兼容判定**。
- **并发**：按契约名加锁串行化所有演进判定，临界区内完成 历史重放扫描 → 参照解析 → 兼容判定 → 提交。同一契约并发注册不会出现版本号重复或丢失更新，版本号为 1..N 连续序列；并发重放同一历史内容收敛到同一版本号。
- **失败无残留**：被拒绝（`INCOMPATIBLE_SCHEMA` / `INVALID_CONTRACT` / `VERSION_CONFLICT`）或失败的请求在提交前抛出，不留下任何部分写入状态；重复调用结论可复现。

也可以在正式注册时显式指定参照版本：`POST /api/contracts?referenceVersion=1`（缺省参照最新版本）。

## 血缘与敏感传播规则

- 血缘边 `from(上游) -> to(下游)` 为字段级（`契约名.字段名`）。
- **悬空引用**：引用的契约未注册（`UNKNOWN_CONTRACT`）或字段不存在（`LINEAGE_DANGLING_REFERENCE`）时拒绝登记。
- **环状依赖**：新边会使 `to` 成为 `from` 的传递上游时拒绝（`LINEAGE_CYCLE`），含自环。
- **重复登记**：相同边幂等返回 `created=false`，不产生重复边。
- **敏感传播**：字段有效敏感级别 = `max(自身声明级别, 全部传递上游的有效级别)`，沿血缘向下游传播。
- **冲突收敛**：多个直接上游级别不一致时，收敛到**最高级别**，结论标记 `conflict=true`，并在 `upstreamContributions` 中保留每个上游的贡献级别作为冲突依据；`conflictSources()` 可列出低于收敛级别的来源。

## REST 入口

```
POST /api/contracts                     注册契约新版本（缺省参照最新版本；拒绝时返回 409 + reason + violations）
POST /api/contracts?referenceVersion=1  以指定历史版本为参照注册
GET  /api/contracts/{name}/versions     查询版本历史
POST /api/evolution/precheck            演进预检（只读），可带 referenceVersion / compatibility 查询参数
POST /api/lineage                       登记血缘边 {"from":{"contract":"a","field":"x"},"to":{...}}
GET  /api/lineage                       查询全部血缘边
GET  /api/lineage/sensitivity/{contract}/{field}  查询字段有效敏感级别与冲突依据
```

注册/预检请求体相同，预检示例：

```bash
# 1) 先预检（不会落库）；指定参照版本并临时以 FORWARD 判定
curl -s -X POST 'http://localhost:8080/api/evolution/precheck?referenceVersion=1&compatibility=FORWARD' \
  -H 'Content-Type: application/json' -d '{
    "name": "orders",
    "compatibility": "BACKWARD",
    "fields": [
      {"name": "id", "type": "long", "required": true, "sensitivity": "PUBLIC"},
      {"name": "ssn", "type": "string", "required": false, "sensitivity": "SECRET"}
    ]
  }'

# 2) 看结论可接受后，再正式注册（结论与预检完全一致）
curl -s -X POST http://localhost:8080/api/contracts \
  -H 'Content-Type: application/json' -d '{ ...同上... }'

# 3) 历史版本重放：原样补投任一旧版本内容，返回 IDEMPOTENT_REPLAY 与已有版本号
```

被拒绝时正式注册返回 HTTP 409，响应体携带可区分原因与逐条依据：

```json
{
  "reason": "INCOMPATIBLE_SCHEMA",
  "message": "契约 'orders' 不兼容变更被拒绝: [...]",
  "violations": [
    {"changeType": "FIELD_ADDED", "field": "code",
     "detail": "级别 BACKWARD 不允许新增必填字段 'code'（新读者读取不含该字段的旧数据时会失败）"}
  ]
}
```

## 本地验证

```bash
mvn test                # 运行全部单测（共 27 例）
mvn spring-boot:run     # 本地启动，默认 8080 端口，可用上面的 curl 手动验证预检/注册
```

测试覆盖（含本轮新增 7 例演进管理用例）：

- 兼容方向语义：删除可选/必填字段、新增可选/必填字段、必填收紧与放宽在 BACKWARD/FORWARD/FULL 各级别下的结论（该拦的破坏性变更必拦，合法演进不误伤），类型变更、重命名、NONE 放行；
- **历史版本重放收敛**：多版本之后补投任一旧版本内容，命中对应历史版本号并标记幂等，不生成新版本；全新内容仍按新版本并过兼容判定；重放优先于相对最新版本的兼容判定；
- **预检与正式注册一致**：接受（预测版本号一致）、幂等、拒绝（`violations` 逐条相等）三条路径一致，且预检只读、拒绝无残留；
- 参照版本解析（指定历史版本结论随参照变化）、兼容级别切换后结论随级别方向翻转、参照版本不存在预检与注册一致返回 `VERSION_CONFLICT`；
- 并发：16 线程并发注册版本号唯一连续、不丢失更新；相同内容并发提交幂等收敛；多份旧版本内容并发混投收敛到各自已有版本号、无重复版本或半写入；
- 既有血缘能力不回归：环状血缘、悬空引用、重复登记幂等、敏感级别传递传播与多上游冲突收敛。

单测日志会打印输入契约指纹/字段与每一步的判定依据（兼容判定、预检结论、正式注册结论、并发收敛结果），结论可区分且可复现。
