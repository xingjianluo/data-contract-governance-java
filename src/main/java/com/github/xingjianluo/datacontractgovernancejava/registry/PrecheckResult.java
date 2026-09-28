package com.github.xingjianluo.datacontractgovernancejava.registry;

import com.github.xingjianluo.datacontractgovernancejava.compat.CompatibilityViolation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 演进预检结论（只读，不落库）。与正式注册共享同一份判定逻辑，结论完全一致。
 *
 * @param status           COMPATIBLE / IDEMPOTENT_REPLAY / REJECTED
 * @param contractName     候选契约名
 * @param baseVersion      实际参照的已注册版本号；首个版本时为 null
 * @param resolvedVersion  COMPATIBLE 时为将要分配的新版本号；IDEMPOTENT_REPLAY 时为命中的历史版本号；REJECTED 时为 null
 * @param violations       REJECTED 时的拒绝依据（按变更种类可区分）；其他状态为空
 * @param violationsByType 拒绝依据按 {@link com.github.xingjianluo.datacontractgovernancejava.compat.ChangeType} 分组
 */
public record PrecheckResult(
        PrecheckStatus status,
        String contractName,
        Integer baseVersion,
        Integer resolvedVersion,
        List<CompatibilityViolation> violations,
        Map<String, List<CompatibilityViolation>> violationsByType) {

    public static PrecheckResult compatible(String contractName, Integer baseVersion, int nextVersion) {
        return new PrecheckResult(PrecheckStatus.COMPATIBLE, contractName, baseVersion,
                nextVersion, List.of(), Map.of());
    }

    public static PrecheckResult idempotent(String contractName, Integer baseVersion, int existingVersion) {
        return new PrecheckResult(PrecheckStatus.IDEMPOTENT_REPLAY, contractName, baseVersion,
                existingVersion, List.of(), Map.of());
    }

    public static PrecheckResult rejected(String contractName, Integer baseVersion,
                                          List<CompatibilityViolation> violations) {
        List<CompatibilityViolation> copy = List.copyOf(violations);
        Map<String, List<CompatibilityViolation>> grouped = new LinkedHashMap<>();
        for (CompatibilityViolation v : copy) {
            grouped.computeIfAbsent(v.changeType().name(), k -> new java.util.ArrayList<>()).add(v);
        }
        return new PrecheckResult(PrecheckStatus.REJECTED, contractName, baseVersion,
                null, copy, Map.copyOf(grouped));
    }
}
