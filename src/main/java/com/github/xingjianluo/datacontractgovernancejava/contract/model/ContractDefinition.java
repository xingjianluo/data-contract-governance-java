package com.github.xingjianluo.datacontractgovernancejava.contract.model;

import java.util.List;

/** 契约注册请求载荷。 */
public record ContractDefinition(
        String contractName,
        Integer version,
        CompatibilityMode mode,
        List<FieldDefinition> fields
) {
}
