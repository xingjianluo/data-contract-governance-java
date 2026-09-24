package com.github.xingjianluo.datacontractgovernancejava.lineage;

/**
 * 字段级血缘边：from（上游） -> to（下游）。
 */
public record LineageEdge(FieldRef from, FieldRef to) {
}
