package com.github.xingjianluo.datacontractgovernancejava.lineage.model;

/** 血缘登记服务返回结果。 */
public record LineageRegisterResponse(
        LineageOutcome outcome,
        String sourceId,
        String targetId,
        LineageIssueCode issueCode,
        String message,
        String cyclePath
) {
}
