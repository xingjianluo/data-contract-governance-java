package com.github.xingjianluo.datacontractgovernancejava.compat;

import java.util.List;

/**
 * 兼容性判定结论：是否兼容 + 全部违规依据（可区分、可复现）。
 */
public record CompatibilityReport(boolean compatible, List<CompatibilityViolation> violations) {

    public static CompatibilityReport ok() {
        return new CompatibilityReport(true, List.of());
    }

    public static CompatibilityReport rejected(List<CompatibilityViolation> violations) {
        return new CompatibilityReport(false, List.copyOf(violations));
    }
}
