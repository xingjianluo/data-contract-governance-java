package com.github.xingjianluo.datacontractgovernancejava.lineage.model;

/** 血缘登记被拒绝/标记的可区分原因码。 */
public enum LineageIssueCode {
    SELF_LOOP,
    CYCLE_DETECTED,
    DANGLING_REFERENCE,
    DUPLICATE_REGISTRATION,
    INVALID_EDGE
}
