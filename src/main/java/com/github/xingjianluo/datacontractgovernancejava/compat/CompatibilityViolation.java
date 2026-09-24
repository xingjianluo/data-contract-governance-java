package com.github.xingjianluo.datacontractgovernancejava.compat;

/**
 * 一条不兼容判定依据：变更类型 + 涉及字段 + 可解释描述。
 */
public record CompatibilityViolation(ChangeType changeType, String field, String detail) {

    @Override
    public String toString() {
        return changeType + "(" + field + "): " + detail;
    }
}
