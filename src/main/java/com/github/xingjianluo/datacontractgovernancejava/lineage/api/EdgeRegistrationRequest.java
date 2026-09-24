package com.github.xingjianluo.datacontractgovernancejava.lineage.api;

/** 血缘边登记请求。 */
public record EdgeRegistrationRequest(
        String sourceId,
        String targetId,
        String transform
) {
}
