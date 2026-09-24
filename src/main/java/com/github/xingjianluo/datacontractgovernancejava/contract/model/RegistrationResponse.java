package com.github.xingjianluo.datacontractgovernancejava.contract.model;

import java.util.List;

/** 注册服务返回结果。 */
public record RegistrationResponse(
        RegistrationOutcome outcome,
        String contractName,
        Integer version,
        RejectionCode rejectionCode,
        String message,
        List<FieldChangeViolation> violations
) {
}
