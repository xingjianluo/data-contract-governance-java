package com.github.xingjianluo.datacontractgovernancejava.contract.service;

import com.github.xingjianluo.datacontractgovernancejava.contract.model.CompatibilityMode;
import com.github.xingjianluo.datacontractgovernancejava.contract.model.CompatibilityReport;
import com.github.xingjianluo.datacontractgovernancejava.contract.model.ContractDefinition;
import com.github.xingjianluo.datacontractgovernancejava.contract.model.ContractVersion;
import com.github.xingjianluo.datacontractgovernancejava.contract.model.FieldChangeViolation;
import com.github.xingjianluo.datacontractgovernancejava.contract.model.FieldDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 依据声明的兼容级别判定新旧契约之间的字段级差异。
 *
 * <p>兼容语义（与 Avro/Confluent 的常见约定一致）：</p>
 * <ul>
 *   <li>BACKWARD：新 schema 能读旧数据（新消费者读旧生产者数据）。</li>
 *   <li>FORWARD：旧 schema 能读新数据（旧消费者读新生产者数据）。</li>
 *   <li>FULL：同时满足 BACKWARD 与 FORWARD。</li>
 *   <li>NONE：不做兼容性约束。</li>
 * </ul>
 *
 * <p>字段级规则：</p>
 * <ul>
 *   <li>新增必填字段违反 BACKWARD/FULL（旧数据缺该字段）；新增可选字段兼容；FORWARD 下新增字段总兼容。</li>
 *   <li>删除必填字段违反 FORWARD/FULL（旧消费者必填读不到）；删除可选字段兼容；BACKWARD 下删除总兼容。</li>
 *   <li>类型变更（不支持隐式提升）在 BACKWARD/FORWARD/FULL 下均不兼容。</li>
 *   <li>可选→必填（收紧）违反 BACKWARD/FULL；必填→可选（放宽）违反 FORWARD/FULL。</li>
 *   <li>重命名（renamedFrom 声明且旧字段存在）：类型必须保持一致；
 *       BACKWARD 要求新字段可选（旧数据只有旧名字），FORWARD 要求旧字段可选（旧消费者只认旧名字），
 *       FULL 要求双方均可选。</li>
 * </ul>
 *
 * <p>结论确定性：字段按名称排序处理，violation 按 (fieldName, reason) 排序，重复输入必得相同结论。</p>
 */
@Component
public class ContractCompatibilityChecker {

    private static final Logger log = LoggerFactory.getLogger(ContractCompatibilityChecker.class);

    public CompatibilityReport check(ContractVersion previous, ContractDefinition candidate) {
        CompatibilityMode mode = candidate.mode() == null ? CompatibilityMode.NONE : candidate.mode();
        List<FieldChangeViolation> violations = new ArrayList<>();

        if (mode == CompatibilityMode.NONE) {
            log.info("[compat] mode=NONE contract={} version={} -> skip compatibility check",
                    candidate.contractName(), candidate.version());
            return new CompatibilityReport(true, mode, List.of());
        }
        if (previous == null) {
            log.info("[compat] first version contract={} version={} mode={} -> compatible",
                    candidate.contractName(), candidate.version(), mode);
            return new CompatibilityReport(true, mode, List.of());
        }

        boolean backward = mode == CompatibilityMode.BACKWARD || mode == CompatibilityMode.FULL;
        boolean forward = mode == CompatibilityMode.FORWARD || mode == CompatibilityMode.FULL;

        Map<String, FieldDefinition> oldByName = new HashMap<>();
        previous.fields().forEach(f -> oldByName.put(f.name(), f));

        // 新字段按名字排序，保证重命名冲突时的消费顺序确定。
        List<FieldDefinition> newSorted = candidate.fields().stream()
                .sorted(Comparator.comparing(FieldDefinition::name))
                .toList();

        Set<String> consumedOld = new HashSet<>();
        // newName -> 配对到的旧字段（可能为 null 表示新增）
        Map<String, FieldDefinition> paired = new LinkedHashMap<>();
        for (FieldDefinition nf : newSorted) {
            FieldDefinition mapped = null;
            if (nf.isRename() && oldByName.containsKey(nf.renamedFrom())
                    && !consumedOld.contains(nf.renamedFrom())) {
                mapped = oldByName.get(nf.renamedFrom());
                consumedOld.add(nf.renamedFrom());
            } else if (oldByName.containsKey(nf.name()) && !consumedOld.contains(nf.name())) {
                mapped = oldByName.get(nf.name());
                consumedOld.add(nf.name());
            }
            paired.put(nf.name(), mapped);
        }

        for (FieldDefinition nf : newSorted) {
            FieldDefinition of = paired.get(nf.name());
            if (of == null) {
                // 新增字段
                if (backward && nf.required()) {
                    violations.add(new FieldChangeViolation("ADDED_REQUIRED_FIELD", nf.name(),
                            "new required field '" + nf.name() + "' has no value in old data"
                                    + " (violates " + mode + ")"));
                }
                continue;
            }

            boolean renamed = nf.isRename() && of.name().equals(nf.renamedFrom());

            // 类型必须一致，不做隐式类型提升
            if (of.type() != nf.type()) {
                violations.add(new FieldChangeViolation("FIELD_TYPE_CHANGED", nf.name(),
                        "field '" + displayName(of.name(), renamed) + "' type changed from "
                                + of.type() + " to " + nf.type()));
            }

            if (renamed) {
                if (backward && nf.required()) {
                    violations.add(new FieldChangeViolation("RENAME_TARGET_REQUIRED", nf.name(),
                            "field renamed from '" + of.name() + "' to '" + nf.name()
                                    + "' but new field is required; old data only carries name '"
                                    + of.name() + "' (violates BACKWARD)"));
                }
                if (forward && of.required()) {
                    violations.add(new FieldChangeViolation("RENAME_SOURCE_REQUIRED", nf.name(),
                            "field renamed from '" + of.name() + "' to '" + nf.name()
                                    + "' but old field is required; old consumers reading new data"
                                    + " cannot find '" + of.name() + "' (violates FORWARD)"));
                }
            } else {
                // 必填性调整
                if (!of.required() && nf.required() && backward) {
                    violations.add(new FieldChangeViolation("REQUIRED_CONSTRAINT_TIGHTENED", nf.name(),
                            "field '" + nf.name() + "' changed from optional to required"
                                    + " (violates BACKWARD)"));
                }
                if (of.required() && !nf.required() && forward) {
                    violations.add(new FieldChangeViolation("REQUIRED_CONSTRAINT_RELAXED", nf.name(),
                            "field '" + nf.name() + "' changed from required to optional"
                                    + " (violates FORWARD)"));
                }
            }
        }

        // 删除字段（未被任何新字段消费），按名字排序
        oldByName.values().stream()
                .filter(f -> !consumedOld.contains(f.name()))
                .sorted(Comparator.comparing(FieldDefinition::name))
                .forEach(of -> {
                    if (forward && of.required()) {
                        violations.add(new FieldChangeViolation("REMOVED_REQUIRED_FIELD", of.name(),
                                "required field '" + of.name()
                                        + "' is removed; old consumers reading new data lose it"
                                        + " (violates FORWARD)"));
                    }
                });

        violations.sort(Comparator.comparing(FieldChangeViolation::fieldName)
                .thenComparing(FieldChangeViolation::reason));

        boolean compatible = violations.isEmpty();
        log.info("[compat] contract={} v{} -> v{} mode={} compatible={} violations={}",
                candidate.contractName(), previous.version(), candidate.version(),
                mode, compatible, violations);
        return new CompatibilityReport(compatible, mode, List.copyOf(violations));
    }

    private static String displayName(String oldName, boolean renamed) {
        return renamed ? oldName + " (renamed)" : oldName;
    }
}
