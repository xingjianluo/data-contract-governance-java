package com.github.xingjianluo.datacontractgovernancejava.lineage.service;

import com.github.xingjianluo.datacontractgovernancejava.lineage.exception.LineageRejectedException;
import com.github.xingjianluo.datacontractgovernancejava.lineage.model.DataNode;
import com.github.xingjianluo.datacontractgovernancejava.lineage.model.LineageEdge;
import com.github.xingjianluo.datacontractgovernancejava.lineage.model.LineageIssueCode;
import com.github.xingjianluo.datacontractgovernancejava.lineage.model.LineageOutcome;
import com.github.xingjianluo.datacontractgovernancejava.lineage.model.LineageRegisterResponse;
import com.github.xingjianluo.datacontractgovernancejava.lineage.model.SensitivityContribution;
import com.github.xingjianluo.datacontractgovernancejava.lineage.model.SensitivityLevel;
import com.github.xingjianluo.datacontractgovernancejava.lineage.model.SensitivityResolution;
import com.github.xingjianluo.datacontractgovernancejava.lineage.store.LineageStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 血缘节点/边登记、环检测与敏感级传播入口。
 *
 * <p>登记校验顺序（原因可区分）：空入参/自环 → 悬空引用（上下游节点须先存在）→
 * 重复登记（同 source→target 已存在即幂等回放）→ 成环检测（新增后不允许出现从 target
 * 可达 source 的路径）。任何一步拒绝都不会落盘，无部分写入。</p>
 *
 * <p>敏感级传播：沿“上游→下游”方向传递，节点最终级别 = max(自身声明, 全部上游来源级别)。
 * 当最高级别由多个互相不同的“根来源”（自身声明或无上游的节点）同时贡献时标记 conflict=true，
 * 并在 contributions 中保留每个来源的级别与传播路径，作为冲突依据；结论对同一份存储可复现。</p>
 */
@Service
public class LineageService {

    private static final Logger log = LoggerFactory.getLogger(LineageService.class);

    private final LineageStore store;
    private final Object edgeLock = new Object();

    public LineageService(LineageStore store) {
        this.store = store;
    }

    public static String nodeId(String contractName, int version, String fieldName) {
        return contractName + ":" + version + ":" + fieldName;
    }

    public DataNode registerNode(String contractName, int version, String fieldName,
                                 SensitivityLevel declared) {
        if (contractName == null || contractName.isBlank()
                || fieldName == null || fieldName.isBlank() || version <= 0) {
            throw new LineageRejectedException(LineageIssueCode.INVALID_EDGE,
                    "contractName/version/fieldName are required");
        }
        SensitivityLevel level = declared == null ? SensitivityLevel.UNKNOWN : declared;
        DataNode node = new DataNode(nodeId(contractName, version, fieldName),
                contractName, version, fieldName, level);
        DataNode existing = store.putNodeIfAbsent(node);
        log.info("[lineage] node upsert id={} declared={} created={}", node.id(), level, existing == node);
        return existing;
    }

    public LineageRegisterResponse registerEdge(String sourceId, String targetId, String transform) {
        if (sourceId == null || sourceId.isBlank() || targetId == null || targetId.isBlank()) {
            throw new LineageRejectedException(LineageIssueCode.INVALID_EDGE,
                    "sourceId and targetId are required");
        }
        if (sourceId.equals(targetId)) {
            log.warn("[lineage] reject edge {} -> {} reason=SELF_LOOP", sourceId, targetId);
            throw new LineageRejectedException(LineageIssueCode.SELF_LOOP,
                    "self loop is not allowed: " + sourceId + " -> " + targetId);
        }

        synchronized (edgeLock) {
            DataNode source = store.findNode(sourceId).orElseThrow(() -> {
                log.warn("[lineage] reject edge {} -> {} reason=DANGLING_REFERENCE (missing source)",
                        sourceId, targetId);
                return new LineageRejectedException(LineageIssueCode.DANGLING_REFERENCE,
                        "source node '" + sourceId + "' is not registered");
            });
            DataNode target = store.findNode(targetId).orElseThrow(() -> {
                log.warn("[lineage] reject edge {} -> {} reason=DANGLING_REFERENCE (missing target)",
                        sourceId, targetId);
                return new LineageRejectedException(LineageIssueCode.DANGLING_REFERENCE,
                        "target node '" + targetId + "' is not registered");
            });

            Optional<LineageEdge> existing = store.findEdge(sourceId, targetId);
            if (existing.isPresent()) {
                LineageEdge e = existing.get();
                boolean sameTransform = Objects.equals(e.transform(), transform);
                log.info("[lineage] duplicate edge {} -> {} replay={} transformMatched={}",
                        sourceId, targetId, true, sameTransform);
                // 同一条边重复登记幂等回放；transform 不一致时作为冲突标记返回，不新增边
                return new LineageRegisterResponse(LineageOutcome.DUPLICATE_REPLAY,
                        sourceId, targetId,
                        sameTransform ? null : LineageIssueCode.DUPLICATE_REGISTRATION,
                        sameTransform ? "identical edge already registered; replayed idempotently"
                                : "edge already exists with a different transform; kept original and flagged",
                        null);
            }

            // 环检测：若 target 已可到达 source，新增 source->target 即成环
            List<String> cyclePath = findPath(targetId, sourceId);
            if (cyclePath != null) {
                List<String> full = new ArrayList<>(cyclePath);
                full.add(targetId);
                log.warn("[lineage] reject edge {} -> {} reason=CYCLE_DETECTED path={}",
                        sourceId, targetId, full);
                throw new LineageRejectedException(LineageIssueCode.CYCLE_DETECTED,
                        "edge would create a cycle: " + String.join(" -> ", full));
            }

            LineageEdge edge = new LineageEdge(sourceId, targetId, transform, Instant.now());
            store.putEdgeIfAbsent(edge);
            log.info("[lineage] edge registered {} -> {} transform={}", sourceId, targetId, transform);
            return new LineageRegisterResponse(LineageOutcome.REGISTERED,
                    sourceId, targetId, null, "registered", null);
        }
    }

    /** 广度优先查找从 from 到 to 的有向路径（边方向 source->target），不存在返回 null。 */
    private List<String> findPath(String from, String to) {
        if (from.equals(to)) {
            return new ArrayList<>(List.of(from));
        }
        Map<String, String> parent = new HashMap<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(from);
        parent.put(from, null);
        while (!queue.isEmpty()) {
            String cur = queue.poll();
            List<String> nexts = store.allEdges().stream()
                    .filter(e -> e.sourceId().equals(cur))
                    .map(LineageEdge::targetId)
                    .sorted()
                    .toList();
            for (String nxt : nexts) {
                if (parent.containsKey(nxt)) {
                    continue;
                }
                parent.put(nxt, cur);
                if (nxt.equals(to)) {
                    List<String> path = new ArrayList<>();
                    for (String x = to; x != null; x = parent.get(x)) {
                        path.add(x);
                    }
                    java.util.Collections.reverse(path);
                    return path;
                }
                queue.add(nxt);
            }
        }
        return null;
    }

    /**
     * 敏感级收敛结论。对节点图快照做一次性 DFS 计算，边在计算过程中不变，
     * 因此对同一份存储重复调用结论一致、可复现。
     */
    public SensitivityResolution resolveSensitivity(String nodeId) {
        DataNode root = store.findNode(nodeId).orElseThrow(() ->
                new LineageRejectedException(LineageIssueCode.DANGLING_REFERENCE,
                        "node '" + nodeId + "' is not registered"));

        List<SensitivityContribution> contributions = new ArrayList<>();
        // 防止图中既存环（理论上登记时已拒绝，这里防御）
        collect(root, new ArrayDeque<>(), contributions);

        SensitivityLevel effective = contributions.stream()
                .map(SensitivityContribution::level)
                .max(Comparator.comparingInt(SensitivityLevel::rank))
                .orElse(SensitivityLevel.UNKNOWN);

        // 冲突：有效级别（>UNKNOWN）由多个不同根来源同时贡献
        List<String> topRoots = contributions.stream()
                .filter(c -> c.level() == effective && effective.rank() >= 0)
                .map(SensitivityContribution::fromNodeId)
                .distinct()
                .sorted()
                .toList();
        boolean conflict = topRoots.size() > 1;

        contributions.sort(Comparator.comparing(SensitivityContribution::fromNodeId));
        log.info("[sensitivity] node={} effective={} conflict={} roots={} contributions={}",
                nodeId, effective, conflict, topRoots,
                contributions.stream().map(c -> c.fromNodeId() + "=" + c.level()).toList());
        return new SensitivityResolution(nodeId, effective, conflict, List.copyOf(contributions));
    }

    private void collect(DataNode node, Deque<String> visiting,
                         List<SensitivityContribution> out) {
        if (visiting.contains(node.id())) {
            // 防御性环处理：记录依据但不再递归，避免死循环
            out.add(new SensitivityContribution(node.id(), SensitivityLevel.UNKNOWN,
                    List.copyOf(visiting)));
            return;
        }
        visiting.addLast(node.id());

        // 自身声明始终是一个根来源
        if (node.declaredSensitivity() != null
                && node.declaredSensitivity() != SensitivityLevel.UNKNOWN) {
            out.add(new SensitivityContribution(node.id(), node.declaredSensitivity(),
                    List.copyOf(visiting)));
        }

        List<LineageEdge> incoming = store.allEdges().stream()
                .filter(e -> e.targetId().equals(node.id()))
                .sorted(Comparator.comparing(LineageEdge::sourceId))
                .toList();
        for (LineageEdge e : incoming) {
            store.findNode(e.sourceId()).ifPresent(parentNode ->
                    collect(parentNode, visiting, out));
        }
        visiting.removeLast();
    }
}
