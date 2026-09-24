package com.github.xingjianluo.datacontractgovernancejava.lineage;

/**
 * 字段引用：契约名 + 字段名，作为血缘图节点。
 */
public record FieldRef(String contract, String field) {

    public static FieldRef of(String contract, String field) {
        return new FieldRef(contract, field);
    }

    @Override
    public String toString() {
        return contract + "." + field;
    }
}
