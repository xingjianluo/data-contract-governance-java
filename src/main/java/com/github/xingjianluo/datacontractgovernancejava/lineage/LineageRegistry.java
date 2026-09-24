package com.github.xingjianluo.datacontractgovernancejava.lineage;

import com.github.xingjianluo.datacontractgovernancejava.error.GovernanceException;
import com.github.xingjianluo.datacontractgovernancejava.error.RejectReason;
import com.github.xingjianluo.datacontractgovernancejava.model.ContractField;
import com.github.xingjianluo.datacontractgovernancejava.model.SensitivityLevel;
import com.github.xingjianluo.datacontractgovernancejava.registry.ContractRegistry;
import com.github.xingjianluo.datacontractgovernancejava.registry.ContractVersion;
import org.springframework.stereotype.Repository;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 血缘登记与敏感级别传播（本地存储）。
 *
 * 一致性语义：所有登记在单锁内完成 校验 -> 环检测 -> 提交，
 * 被拒绝的登记不留下任何部分写入；重复登记幂等返回 false。
 *
 * 传播规则：字段有效敏感级别 = max(自身声明级别, 全部传递上游的有效级别)；
 * 多个上游级别不一致时收敛到最高级别，并在结论中保留各上游贡献作为冲突依据。
 */
@Repository
public class LineageRegistry {

    private final ContractRegistry contractRegistry;
    private final Object mu = new Object();
    /** 下游字段 -> 直接上游字段集合（按登记顺序，保证结论可复现）。 */
    private final Map<FieldRef, List<FieldRef>> upstreams = new LinkedHashMap<>();

    public LineageRegistry(ContractRegistry contractRegistry) {
        this.contractRegistry = contractRegistry;
    }

    /**
     * 登记一条血缘边 from(上游) -> to(下游)。
     *
     * @return true 新登记；false 重复登记（幂等，不产生重复边）
     * @throws GovernanceException 悬空引用（LINEAGE_DANGLING_REFERENCE）或环状依赖（LINEAGE_CYCLE）
     */
    public boolean registerEdge(LineageEdge edge) {
        synchronized (mu) {
            requireField(edge.from());
            requireField(edge.to());
            List<FieldRef> existing = upstreams.getOrDefault(edge.to(), List.of());
            if (existing.contains(edge.from())) {
                return false;
            }
            if (createsCycle(edge.from(), edge.to())) {
                throw new GovernanceException(RejectReason.LINEAGE_CYCLE,
                        "登记 " + edge.from() + " -> " + edge.to() + " 会形成环状依赖");
            }
            upstreams.computeIfAbsent(edge.to(), k -> new ArrayList<>()).add(edge.from());
            return true;
        }
    }

    private void requireField(FieldRef ref) {
        Optional<ContractVersion> latest = contractRegistry.latest(ref.contract());
        if (latest.isEmpty()) {
            throw new GovernanceException(RejectReason.UNKNOWN_CONTRACT,
                    "契约 '" + ref.contract() + "' 未注册，血缘引用悬空: " + ref);
        }
        ContractField field = latest.get().contract().field(ref.field());
        if (field == null) {
            throw new GovernanceException(RejectReason.LINEAGE_DANGLING_REFERENCE,
                    "字段 " + ref + " 在契约最新版本中不存在，血缘引用悬空");
        }
    }

    /** 若 from 的传递上游中已包含 to，则新增 from->to（from 成为 to 的上游）将成环。 */
    private boolean createsCycle(FieldRef from, FieldRef to) {
        if (from.equals(to)) {
            return true;
        }
        Deque<FieldRef> stack = new ArrayDeque<>();
        Set<FieldRef> visited = new HashSet<>();
        stack.push(from);
        while (!stack.isEmpty()) {
            FieldRef cur = stack.pop();
            if (cur.equals(to)) {
                return true;
            }
            if (visited.add(cur)) {
                upstreams.getOrDefault(cur, List.of()).forEach(stack::push);
            }
        }
        return false;
    }

    public List<LineageEdge> edges() {
        synchronized (mu) {
            List<LineageEdge> all = new ArrayList<>();
            upstreams.forEach((to, froms) -> froms.forEach(f -> all.add(new LineageEdge(f, to))));
            return List.copyOf(all);
        }
    }

    /**
     * 计算字段的有效敏感级别：自身声明级别与全部传递上游级别取最高；
     * 多上游冲突时收敛到最高级别，并保留各直接上游的贡献作为冲突依据。
     */
    public SensitivityResolution resolveSensitivity(FieldRef field) {
        synchronized (mu) {
            requireField(field);
            SensitivityLevel effective = declaredLevel(field);
            Map<FieldRef, SensitivityLevel> contributions = new LinkedHashMap<>();
            for (FieldRef directUpstream : upstreams.getOrDefault(field, List.of())) {
                SensitivityLevel upstreamLevel = transitiveLevel(directUpstream, new HashSet<>());
                contributions.put(directUpstream, upstreamLevel);
                effective = SensitivityLevel.max(effective, upstreamLevel);
            }
            boolean conflict = contributions.values().stream().distinct().count() > 1;
            return new SensitivityResolution(field, effective, conflict, contributions);
        }
    }

    /** 传递闭包内（含自身）的最高敏感级别；图无环（登记时已保证），visited 仅作防御。 */
    private SensitivityLevel transitiveLevel(FieldRef field, Set<FieldRef> visited) {
        if (!visited.add(field)) {
            return SensitivityLevel.PUBLIC;
        }
        SensitivityLevel level = declaredLevel(field);
        for (FieldRef up : upstreams.getOrDefault(field, List.of())) {
            level = SensitivityLevel.max(level, transitiveLevel(up, visited));
        }
        return level;
    }

    private SensitivityLevel declaredLevel(FieldRef ref) {
        return contractRegistry.latest(ref.contract())
                .map(v -> v.contract().field(ref.field()))
                .map(ContractField::getSensitivity)
                .orElse(SensitivityLevel.PUBLIC);
    }
}
