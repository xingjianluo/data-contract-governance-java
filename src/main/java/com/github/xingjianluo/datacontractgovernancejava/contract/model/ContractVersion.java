package com.github.xingjianluo.datacontractgovernancejava.contract.model;

import java.time.Instant;
import java.util.List;

/** 已落库的不可变契约版本。 */
public record ContractVersion(
        String contractName,
        int version,
        CompatibilityMode mode,
        List<FieldDefinition> fields,
        Instant registeredAt
) {
}
