# 数据契约与元数据治理

基于 Spring Boot 的本地存储实现，提供数据契约多版本注册、兼容性判定、字段级血缘登记与敏感级别传播能力。所有入口（REST API 与内部调用）共享同一套注册中心，保证结构演进、并发注册与敏感数据传播得到一致且可解释的结论。

## 模块结构

```
model/     契约、字段、兼容级别、敏感级别等领域类型
compat/    兼容性判定（CompatibilityChecker），输出可区分的违规依据
registry/  契约注册中心（ContractRegistry），本地多版本存储
lineage/   血缘登记与敏感传播（LineageRegistry）
error/     统一异常 GovernanceException + 可区分拒绝原因 RejectReason
api/       REST 入口与统一异常映射（拒绝原因 -> HTTP 409）
```

## 兼容性规则

按契约声明的 `compatibility` 级别判定（`NONE` 一律放行）：

| 变更 | BACKWARD | FORWARD | FULL |
|---|---|---|---|
| 删除字段 | 拒绝 | 允许 | 拒绝 |
| 新增必填字段 | 拒绝 | 允许 | 拒绝 |
| 新增可选字段 | 允许 | 允许 | 允许 |
| 类型变更 | 拒绝 | 拒绝 | 拒绝 |
| 必填收紧（可选→必填） | 拒绝 | 允许 | 拒绝 |
| 必填放宽（必填→可选） | 允许 | 允许 | 允许 |
| 重命名 | 视为删除+新增，按上述规则判定，结论中附 `FIELD_RENAMED` 提示依据 |

判定结论为 `CompatibilityReport(compatible, violations)`，每条违规携带 `ChangeType`（FIELD_ADDED / FIELD_REMOVED / TYPE_CHANGED / REQUIRED_TIGHTENED / FIELD_RENAMED 等）、字段名与可读描述，拒绝原因彼此可区分。

## 注册语义（幂等与并发）

- **幂等**：重复提交与最新版本内容指纹（名称+级别+字段集合）完全一致的契约，返回 `IDEMPOTENT` 状态与现有版本号，不产生新版本。
- **并发**：按契约名加锁串行化，临界区内完成 校验 → 兼容判定 → 提交。同一契约并发注册不会出现版本号重复或丢失更新，版本号为 1..N 连续序列。
- **失败无残留**：被拒绝（`INCOMPATIBLE_SCHEMA` / `INVALID_CONTRACT`）或失败的请求在提交前抛出，不留下任何部分写入状态；重复调用结论可复现。

## 血缘与敏感传播规则

- 血缘边 `from(上游) -> to(下游)` 为字段级（`契约名.字段名`）。
- **悬空引用**：引用的契约未注册（`UNKNOWN_CONTRACT`）或字段不存在（`LINEAGE_DANGLING_REFERENCE`）时拒绝登记。
- **环状依赖**：新边会使 `to` 成为 `from` 的传递上游时拒绝（`LINEAGE_CYCLE`），含自环。
- **重复登记**：相同边幂等返回 `created=false`，不产生重复边。
- **敏感传播**：字段有效敏感级别 = `max(自身声明级别, 全部传递上游的有效级别)`，沿血缘向下游传播。
- **冲突收敛**：多个直接上游级别不一致时，收敛到**最高级别**，结论标记 `conflict=true`，并在 `upstreamContributions` 中保留每个上游的贡献级别作为冲突依据；`conflictSources()` 可列出低于收敛级别的来源。

## REST 入口

```
POST /api/contracts                     注册契约新版本（拒绝时返回 409 + reason）
GET  /api/contracts/{name}/versions     查询版本历史
POST /api/lineage                       登记血缘边 {"from":{"contract":"a","field":"x"},"to":{...}}
GET  /api/lineage                       查询全部血缘边
GET  /api/lineage/sensitivity/{contract}/{field}  查询字段有效敏感级别与冲突依据
```

注册请求示例：

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

## 本地验证

```bash
mvn test          # 运行全部测试（兼容性、血缘、并发、幂等，共 19 例）
mvn spring-boot:run   # 本地启动，默认 8080 端口
```

测试覆盖：不兼容变更（删字段/类型变更/必填收紧/重命名，按级别区分结论）、环状血缘、悬空引用、重复登记幂等、敏感级别传递传播与多上游冲突收敛、16 线程并发注册（版本号唯一连续、不丢失更新）、相同内容并发提交幂等收敛。单测日志打印输入与判定依据，结论可区分且可复现。
