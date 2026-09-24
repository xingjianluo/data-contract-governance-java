package com.github.xingjianluo.datacontractgovernancejava.lineage.model;

/**
 * 血缘节点，全局唯一标识，格式：contractName:version:fieldName（字段级）。
 */
public record DataNode(
        String id,
        String contractName,
        int version,
        String fieldName,
        SensitivityLevel declaredSensitivity
) {
}
