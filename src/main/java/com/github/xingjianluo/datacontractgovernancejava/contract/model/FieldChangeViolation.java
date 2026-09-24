package com.github.xingjianluo.datacontractgovernancejava.contract.model;

/** 单条字段级不兼容判定依据。 */
public record FieldChangeViolation(
        String reason,
        String fieldName,
        String detail
) {
}
