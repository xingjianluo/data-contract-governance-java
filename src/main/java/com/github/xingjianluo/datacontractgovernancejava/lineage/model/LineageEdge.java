package com.github.xingjianluo.datacontractgovernancejava.lineage.model;

import java.time.Instant;

/** 字段级血缘边：上游 source 字段流向 下游 target 字段。 */
public record LineageEdge(
        String sourceId,
        String targetId,
        String transform,
        Instant registeredAt
) {
}
