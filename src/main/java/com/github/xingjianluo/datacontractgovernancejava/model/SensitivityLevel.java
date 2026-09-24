package com.github.xingjianluo.datacontractgovernancejava.model;

/**
 * 敏感级别，ordinal 越大越敏感，传播冲突时按最高级别收敛。
 */
public enum SensitivityLevel {
    PUBLIC,
    INTERNAL,
    CONFIDENTIAL,
    SECRET;

    public static SensitivityLevel max(SensitivityLevel a, SensitivityLevel b) {
        return a.ordinal() >= b.ordinal() ? a : b;
    }
}
