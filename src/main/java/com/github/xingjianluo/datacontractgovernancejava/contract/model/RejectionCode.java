package com.github.xingjianluo.datacontractgovernancejava.contract.model;

/** 注册被拒绝时的可区分原因码。 */
public enum RejectionCode {
    INVALID_CONTRACT,
    VERSION_ALREADY_EXISTS,
    VERSION_OUT_OF_ORDER,
    INCOMPATIBLE_CHANGE,
    DUPLICATE_FIELD_NAME
}
