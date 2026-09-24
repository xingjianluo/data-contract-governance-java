package com.github.xingjianluo.datacontractgovernancejava.contract.model;

/** 契约中的字段定义。renamedFrom 用于声明重命名来源（旧字段名），可为 null。 */
public record FieldDefinition(
        String name,
        FieldType type,
        boolean required,
        String renamedFrom,
        String description
) {
    public boolean isRename() {
        return renamedFrom != null && !renamedFrom.isBlank();
    }
}
