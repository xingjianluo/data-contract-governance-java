package com.github.xingjianluo.datacontractgovernancejava.lineage;

import com.github.xingjianluo.datacontractgovernancejava.model.SensitivityLevel;

import java.util.List;
import java.util.Map;

/**
 * 敏感级别传播结论：收敛后的级别 + 各上游来源级别（冲突依据）。
 */
public record SensitivityResolution(FieldRef field,
                                    SensitivityLevel effectiveLevel,
                                    boolean conflict,
                                    Map<FieldRef, SensitivityLevel> upstreamContributions) {

    public SensitivityResolution {
        upstreamContributions = upstreamContributions == null ? Map.of() : Map.copyOf(upstreamContributions);
    }

    public List<FieldRef> conflictSources() {
        return upstreamContributions.entrySet().stream()
                .filter(e -> e.getValue() != effectiveLevel)
                .map(Map.Entry::getKey)
                .toList();
    }
}
