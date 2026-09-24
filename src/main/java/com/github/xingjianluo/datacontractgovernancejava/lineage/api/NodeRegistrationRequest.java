package com.github.xingjianluo.datacontractgovernancejava.lineage.api;

import com.github.xingjianluo.datacontractgovernancejava.lineage.model.SensitivityLevel;

/** 节点登记请求。 */
public record NodeRegistrationRequest(
        String contractName,
        Integer version,
        String fieldName,
        SensitivityLevel declaredSensitivity
) {
}
