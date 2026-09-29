# 数据契约与元数据治理

基于 Spring Boot 的本地存储实现，提供数据契约多版本注册、**演进预检（先看结论再决定落库）**、兼容性判定、字段级血缘登记与敏感级别传播能力。所有入口（REST API 与内部调用）共享同一套注册中心与同一份判定函数，保证预检与正式注册结论完全一致、结构演进与并发重放得到一致且可解释的结论。

## 模块结构

```
model/     契约、字段、兼容级别、敏感级别等领域类型
compat/    兼容性判定（CompatibilityChecker），输出可区分的违规依据
registry/  契约注册中心（ContractRegistry）、演进预检（PrecheckResult）、本地多版本存储
lineage/   血缘登记与敏感传播（LineageRegistry）
error/     统一异常 GovernanceException + 可区分拒绝原因 RejectReason
api/       REST 入口与统一异常映射（拒绝原因 -> HTTP 409）
```

## 兼容级别的方向语义

判定严格按兼容级别声明的**读方向**执行：

- **BACKWARD（向后兼容）**：新版本契约（新读者）必须能读取**旧版本数据**。旧数据里有、而新契约里没有的字段，新读者忽略即可，不会读丢；但旧数据没有的必填字段不能加，旧数据中可能缺失的字段不能收紧为必填。
- **FORWARD（向前兼容）**：旧版本契约（旧读者）必须能读取**新版本数据**。新数据中可能缺失/为空的字段（包括**被删掉的必填字段**、由必填放宽为可选的字段），不能让仍按旧契约消费的读者失败；旧读者本就容忍缺失的可选字段则可自由删除。
- **FULL**：双向都必须成立，即同时满足 BACKWARD 与 FORWARD。注意不是把某一个方向的限制直接套到整个级别上——只有真正会让某一侧读方读丢字段的变化才算破坏；例如删除字段时，仅“删除必填字段”会在 FORWARD 一侧（旧读者读新数据）读丢而被拒，删除可选字段双向都能容忍，仍然放行。
- **NONE**：不做任何兼容性约束。

**删除字段的读写方向（按被删字段原本的必填性区分）**：旧数据由生产方按旧契约写出、新数据按新契约写出，读者忽略多余字段、但无法容忍自己仍要求存在、数据里却没有的字段。

| 变更 | BACKWARD | FORWARD | FULL |
|---|---|---|---|
| 删除必填字段 | 允许（新读者读旧数据，旧数据仍带该字段，忽略即可） | **拒绝（旧读者按必填读新数据，字段已不存在 -> 读丢）** | **拒绝（FORWARD 一侧读丢）** |
| 删除可选字段 | 允许（旧数据仍带该字段，忽略即可） | 允许（旧读者本就容忍可选字段缺失） | 允许（双向都能容忍，不得误拒） |
| 新增必填字段 | 拒绝（旧数据无此字段） | 允许 | 拒绝 |
| 新增可选字段 | 允许 | 允许 | 允许 |
| 类型变更 | 拒绝 | 拒绝 | 拒绝 |
| 必填收紧（可选→必填） | 拒绝（旧数据可能缺该字段） | 允许 | 拒绝 |
| 必填放宽（必填→可选） | 允许 | **拒绝（旧读者按必填读新数据，字段可能缺失/为空）** | **拒绝** |
| 重命名 | 视为删除+新增，按上述规则判定（删除侧按被删字段的必填性），结论中附 `FIELD_RENAMED` 提示依据 | 同左 | 同左 |

判定结论为 `CompatibilityReport(compatible, violations)`，每条违规携带 `ChangeType`（FIELD_ADDED / FIELD_REMOVED / TYPE_CHANGED / REQUIRED_TIGHTENED / REQUIRED_RELAXED / FIELD_RENAMED）、字段名与可读描述，拒绝原因彼此可区分。切换兼容级别后，同一结构变化的结论严格跟随该级别的真实读写方向：该拦的破坏性变更一定拦住，合法演进不误伤。

## 演进预检（先看结论，再决定落库）

正式发布前，可拿候选契约做**只读预检**：指定要参照的已有版本和兼容级别（兼容级别以候选契约体内的 `compatibility` 为准），系统回答“现在注册会不会被拒、依据是什么”。

- 预检与正式注册共享同一个判定函数 `decideLocked`，**结论逐条一致**（同一变化不会在预检和正式注册给出两套结果）；
- 预检**绝不写入**任何已注册数据，历史版本数与内容在预检前后不变；
- 预检结论 `PrecheckResult`：
  - `COMPATIBLE`：可以注册，`resolvedVersion` 为将要分配的新版本号；
  - `IDEMPOTENT_REPLAY`：内容已存在，`resolvedVersion` 为命中的历史版本号；
  - `REJECTED`：`violations` 给出全部依据，并通过 `violationsByType` 按 `ChangeType` 分组。

```
POST /api/contracts/precheck?baseVersion=2
```

`baseVersion` 可省略（默认以最新版本为参照）。预检 HTTP 状态始终为 200（结论体表达通过/拒绝）；非法契约或参照版本不存在返回 409（`INVALID_CONTRACT` / `VERSION_CONFLICT`）。

## 注册语义（历史版本重放收敛与并发）

- **历史版本重放收敛（幂等范围）**：候选内容指纹（名称+级别+字段集合）与**任一已注册历史版本**完全一致时，无论当前最新版本是谁，一律返回 `IDEMPOTENT_REPLAY` 与那个**已有版本号**，不额外生成新版本。CI 重跑、发布系统补投旧版本内容都安全。只有内容与**全部历史版本**都不同时才按新版本处理，并仍需通过兼容判定。
- **收敛优先**：重放判定先于兼容判定——补投旧版本即使与最新版本之间存在破坏性差异，也直接按幂等收敛，不会被误拒。
- **并发**：按契约名加锁串行化，`校验 → 历史重放收敛 → 参照版本解析 → 兼容判定 → 提交` 在同一临界区内完成。并发注册/并发重放不会出现版本号重复、丢失更新或半写入状态，版本号为 1..N 连续序列。
- **失败无残留**：被拒绝（`INCOMPATIBLE_SCHEMA` / `INVALID_CONTRACT` / `VERSION_CONFLICT`）的请求在提交前抛出，不留下任何部分写入状态；重复调用结论可复现。

## 血缘与敏感传播规则

- 血缘边 `from(上游) -> to(下游)` 为字段级（`契约名.字段名`）。
- **悬空引用**：引用的契约未注册（`UNKNOWN_CONTRACT`）或字段不存在（`LINEAGE_DANGLING_REFERENCE`）时拒绝登记。
- **环状依赖**：新边会使 `to` 成为 `from` 的传递上游时拒绝（`LINEAGE_CYCLE`），含自环。
- **重复登记**：相同边幂等返回 `created=false`，不产生重复边。
- **敏感传播**：字段有效敏感级别 = `max(自身声明级别, 全部传递上游的有效级别)`，沿血缘向下游传播。
- **冲突收敛**：多个直接上游级别不一致时，收敛到**最高级别**，结论标记 `conflict=true`，并在 `upstreamContributions` 中保留每个上游的贡献级别作为冲突依据；`conflictSources()` 可列出低于收敛级别的来源。

## REST 入口

```
POST /api/contracts/precheck?baseVersion={n}  演进预检（只读，200 返回 COMPATIBLE/IDEMPOTENT_REPLAY/REJECTED）
POST /api/contracts?baseVersion={n}           正式注册新版本（baseVersion 可省，默认最新；拒绝时返回 409 + reason + violationsByType）
GET  /api/contracts/{name}/versions           查询版本历史
POST /api/lineage                             登记血缘边 {"from":{"contract":"a","field":"x"},"to":{...}}
GET  /api/lineage                             查询全部血缘边
GET  /api/lineage/sensitivity/{contract}/{field}  查询字段有效敏感级别与冲突依据
```

注册/预检请求示例：

```json
{
  "name": "orders",
  "compatibility": "BACKWARD",
  "fields": [
    {"name": "id", "type": "long", "required": true, "sensitivity": "PUBLIC"},
    {"name": "ssn", "type": "string", "required": false, "sensitivity": "SECRET"}
  ]
}
```

预检被拒绝时的结论示例：

```json
{
  "status": "REJECTED",
  "contractName": "orders",
  "baseVersion": 1,
  "resolvedVersion": null,
  "violations": [
    {"changeType": "FIELD_ADDED", "field": "code", "detail": "级别 BACKWARD 不允许新增必填字段（旧数据中不存在该字段）"}
  ],
  "violationsByType": {
    "FIELD_ADDED": [ {"changeType": "FIELD_ADDED", "field": "code", "detail": "..."} ]
  }
}
```

## 本地验证

```bash
mvn test               # 运行全部测试（兼容性、删除字段方向矩阵、演进预检一致性、历史重放、血缘、并发幂等，共 32 例）
mvn spring-boot:run    # 本地启动，默认 8080 端口
```

手工验证预检与重放：

```bash
# 注册 v1
curl -s -XPOST localhost:8080/api/contracts -H 'Content-Type: application/json' -d \
  '{"name":"orders","compatibility":"BACKWARD","fields":[{"name":"id","type":"long","required":true}]}'
# 预检一个破坏性候选（新增必填），观察 REJECTED 且 GET versions 仍是 1 个版本
curl -s -XPOST localhost:8080/api/contracts/precheck -H 'Content-Type: application/json' -d \
  '{"name":"orders","compatibility":"BACKWARD","fields":[{"name":"id","type":"long","required":true},{"name":"code","type":"string","required":true}]}'

# FORWARD 级别注册 v1 后，预检“删除必填字段 id”的候选：应 REJECTED（FIELD_REMOVED），
# 且正式注册同样被拒、版本数不增加；把被删字段换成可选字段，或级别换成 BACKWARD，则应放行
curl -s -XPOST localhost:8080/api/contracts -H 'Content-Type: application/json' -d \
  '{"name":"events","compatibility":"FORWARD","fields":[{"name":"id","type":"long","required":true},{"name":"note","type":"string","required":false}]}'
curl -s -XPOST localhost:8080/api/contracts/precheck -H 'Content-Type: application/json' -d \
  '{"name":"events","compatibility":"FORWARD","fields":[{"name":"note","type":"string","required":false}]}'
```

测试覆盖：

- 兼容性方向语义：删除必填字段/删除可选字段在 BACKWARD/FORWARD/FULL 各级别下的判定矩阵，以及类型变更/必填收紧与放宽的方向判定；
- **删除字段候选在预检与正式注册中结论与拒绝依据逐条一致**（依据按变更种类标 `FIELD_REMOVED`），被拒候选在两个入口都不落库、不留半写入状态；同一份候选切换兼容级别后结论跟随该级别真实读写含义；
- **演进预检与正式注册结论逐条一致**（含拒绝依据按变更种类分组）、预检只读不落库、显式参照版本；
- **历史版本重放收敛**：补投旧版本返回原版本号且不新增，全新内容才升版并仍过兼容判定；
- 12 线程并发重放 v1/v2 只收敛到已有版本；16 线程并发注册版本号唯一连续；
- 环状血缘、悬空引用、重复登记幂等、敏感级别传递传播与多上游冲突收敛。

单测与注册中心日志均打印输入（契约名/级别/参照版本/指纹）与判定依据，结论可区分且可复现。
