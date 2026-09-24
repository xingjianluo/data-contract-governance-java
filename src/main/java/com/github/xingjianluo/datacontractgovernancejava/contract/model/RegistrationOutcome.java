package com.github.xingjianluo.datacontractgovernancejava.contract.model;

/** 注册结论：幂等命中、新建成功或被拒绝。 */
public enum RegistrationOutcome {
    CREATED,
    IDEMPOTENT_REPLAY,
    REJECTED
}
