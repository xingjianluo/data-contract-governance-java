package com.github.xingjianluo.datacontractgovernancejava.lineage.store;

import com.github.xingjianluo.datacontractgovernancejava.lineage.model.DataNode;
import com.github.xingjianluo.datacontractgovernancejava.lineage.model.LineageEdge;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于 {@link ConcurrentHashMap} 的本地血缘存储。
 *
 * <p>节点以 nodeId 去重；边以 sourceId + '->' + targetId 去重。
 * putIfAbsent 保证并发重复登记同一条边时只有一次写入生效。</p>
 */
@Component
public class InMemoryLineageStore implements LineageStore {

    private final ConcurrentHashMap<String, DataNode> nodes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, LineageEdge> edges = new ConcurrentHashMap<>();

    private static String edgeKey(String sourceId, String targetId) {
        return sourceId + "->" + targetId;
    }

    @Override
    public DataNode putNodeIfAbsent(DataNode node) {
        nodes.putIfAbsent(node.id(), node);
        return nodes.get(node.id());
    }

    @Override
    public Optional<DataNode> findNode(String nodeId) {
        return Optional.ofNullable(nodes.get(nodeId));
    }

    @Override
    public Collection<DataNode> allNodes() {
        return nodes.values();
    }

    @Override
    public LineageEdge putEdgeIfAbsent(LineageEdge edge) {
        edges.putIfAbsent(edgeKey(edge.sourceId(), edge.targetId()), edge);
        return edges.get(edgeKey(edge.sourceId(), edge.targetId()));
    }

    @Override
    public Optional<LineageEdge> findEdge(String sourceId, String targetId) {
        return Optional.ofNullable(edges.get(edgeKey(sourceId, targetId)));
    }

    @Override
    public List<LineageEdge> allEdges() {
        return edges.values().stream()
                .sorted(Comparator.comparing(LineageEdge::registeredAt)
                        .thenComparing(LineageEdge::sourceId)
                        .thenComparing(LineageEdge::targetId))
                .toList();
    }

    @Override
    public void clear() {
        nodes.clear();
        edges.clear();
    }
}
