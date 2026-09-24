package com.github.xingjianluo.datacontractgovernancejava.lineage.store;

import com.github.xingjianluo.datacontractgovernancejava.lineage.model.DataNode;
import com.github.xingjianluo.datacontractgovernancejava.lineage.model.LineageEdge;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** 血缘节点与边的本地存储抽象。 */
public interface LineageStore {

    DataNode putNodeIfAbsent(DataNode node);

    Optional<DataNode> findNode(String nodeId);

    Collection<DataNode> allNodes();

    /** 原子登记边：已存在等价边时返回既有边。 */
    LineageEdge putEdgeIfAbsent(LineageEdge edge);

    Optional<LineageEdge> findEdge(String sourceId, String targetId);

    List<LineageEdge> allEdges();

    void clear();
}
