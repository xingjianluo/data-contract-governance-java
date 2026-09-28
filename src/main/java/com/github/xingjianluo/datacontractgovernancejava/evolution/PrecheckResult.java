package com.github.xingjianluo.datacontractgovernancejava.evolution;

import com.github.xingjianluo.datacontractgovernancejava.compat.CompatibilityViolation;
import com.github.xingjianluo.datacontractgovernancejava.model.CompatibilityLevel;

import java.util.List;

/**
 * 演进预检结论。预检只读不写：该结论与真正提交时的结论完全一致。
 *
 * @param status             预检状态
 * @param contractName       契约名
 * @param version            接受时为预计分配的新版本号；幂等命中时为已有版本号；拒绝时为 null
 * @param referenceVersion   实际参照的已注册版本号；首个版本时为 0；幂等命中时为命中的版本号
 * @param compatibilityLevel 本次判定实际使用的兼容级别（预检可临时指定）
 * @param idempotent         是否为历史内容重放的幂等结论
 * @param violations         拒绝依据（按 ChangeType 区分）；非拒绝时为空列表
 */
public record PrecheckResult(
        PrecheckStatus status,
        String contractName,
        Integer version,
        int referenceVersion,
        CompatibilityLevel compatibilityLevel,
        boolean idempotent,
        List<CompatibilityViolation> violations
) {

    public static PrecheckResult registered(String name, int nextVersion, int referenceVersion,
                                            CompatibilityLevel level) {
        return new PrecheckResult(PrecheckStatus.REGISTERED, name, nextVersion, referenceVersion,
                level, false, List.of());
    }

    public static PrecheckResult idempotentReplay(String name, int existingVersion, CompatibilityLevel level) {
        return new PrecheckResult(PrecheckStatus.IDEMPOTENT_REPLAY, name, existingVersion, existingVersion,
                level, true, List.of());
    }

    public static PrecheckResult rejected(String name, int referenceVersion, CompatibilityLevel level,
                                          List<CompatibilityViolation> violations) {
        return new PrecheckResult(PrecheckStatus.REJECTED, name, null, referenceVersion,
                level, false, List.copyOf(violations));
    }
}
