package com.github.xingjianluo.datacontractgovernancejava.registry;

import com.github.xingjianluo.datacontractgovernancejava.compat.CompatibilityReport;

/**
 * 演进判定的内部统一结论：预检（不落库）与正式注册（落库）共用同一份判定产出，
 * 保证两条路径结论完全一致。
 *
 * @param idempotentVersion 命中历史版本重放时为已有版本号，否则 null
 * @param base              实际参照的已注册版本；首个版本时为 null
 * @param nextVersion       允许注册时将要分配的版本号（重放/拒绝场景忽略）
 * @param report            兼容判定报告（重放场景为 ok）
 */
record EvolutionDecision(
        Integer idempotentVersion,
        ContractVersion base,
        int nextVersion,
        CompatibilityReport report) {

    boolean isReplay() {
        return idempotentVersion != null;
    }

    boolean isRejected() {
        return !report.compatible();
    }
}
