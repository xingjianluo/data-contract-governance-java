package com.github.xingjianluo.datacontractgovernancejava.contract.model;

import java.util.List;

/** 兼容性判定结论。 */
public record CompatibilityReport(
        boolean compatible,
        CompatibilityMode mode,
        List<FieldChangeViolation> violations
) {
}
