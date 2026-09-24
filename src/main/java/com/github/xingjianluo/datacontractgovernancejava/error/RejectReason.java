package com.github.xingjianluo.datacontractgovernancejava.error;

/**
 * 可区分的拒绝原因。
 */
public enum RejectReason {
    INCOMPATIBLE_SCHEMA,
    VERSION_CONFLICT,
    INVALID_CONTRACT,
    LINEAGE_CYCLE,
    LINEAGE_DANGLING_REFERENCE,
    LINEAGE_DUPLICATE_EDGE,
    UNKNOWN_CONTRACT
}
