package com.github.xingjianluo.datacontractgovernancejava.compat;

/**
 * 结构变更类型，作为兼容性判定结论中可区分的依据。
 */
public enum ChangeType {
    FIELD_ADDED,
    FIELD_REMOVED,
    TYPE_CHANGED,
    REQUIRED_TIGHTENED,
    REQUIRED_RELAXED,
    FIELD_RENAMED
}
