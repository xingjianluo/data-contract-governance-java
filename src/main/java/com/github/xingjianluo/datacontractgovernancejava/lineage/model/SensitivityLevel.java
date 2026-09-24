package com.github.xingjianluo.datacontractgovernancejava.lineage.model;

/** 数据敏感级别，rank 越大越敏感；传播时取最高级别收敛。 */
public enum SensitivityLevel {
    UNKNOWN(-1),
    PUBLIC(0),
    INTERNAL(1),
    CONFIDENTIAL(2),
    SECRET(3);

    private final int rank;

    SensitivityLevel(int rank) {
        this.rank = rank;
    }

    public int rank() {
        return rank;
    }
}
