package com.github.xingjianluo.datacontractgovernancejava.compat;

import com.github.xingjianluo.datacontractgovernancejava.model.CompatibilityLevel;
import com.github.xingjianluo.datacontractgovernancejava.model.ContractField;
import com.github.xingjianluo.datacontractgovernancejava.model.DataContract;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 按契约声明的兼容级别，对新旧两版契约做结构演进判定。
 *
 * 方向语义：
 * - BACKWARD：新契约（新读者）必须能读取旧版本数据；
 * - FORWARD：旧契约（旧读者）必须能读取新版本数据；
 * - FULL：双向都必须成立；NONE 不做约束。
 *
 * 规则（NONE 一律放行）：
 * - 删除字段（严格按读方向，并区分被删字段原本的必填性）：
 *   · BACKWARD 一律允许删除——旧数据携带该字段而新读者不再消费它，读者只会忽略多余字段，不会读丢；
 *   · FORWARD 删除【必填】字段拒绝（旧读者仍按必填消费，新数据不再提供该字段 -> 读丢）；删除可选字段允许；
 *   · FULL 删除必填字段拒绝（FORWARD 一侧读丢），删除可选字段允许（双向都能容忍）；
 * - 新增必填字段：BACKWARD/FULL 拒绝（旧数据无该字段），FORWARD 允许；
 * - 新增可选字段：各级别均允许；
 * - 类型变更：BACKWARD/FORWARD/FULL 均拒绝；
 * - 必填收紧（可选->必填）：BACKWARD/FULL 拒绝（旧数据中该字段可能缺失），FORWARD 允许；
 * - 必填放宽（必填->可选）：FORWARD/FULL 拒绝（旧读者按必填消费新数据，字段可能缺失/为空），BACKWARD 允许；
 * - 重命名：视为删除+新增，按上述规则判定，违规依据中标注 FIELD_RENAMED。
 */
@Component
public class CompatibilityChecker {

    /**
     * @param previous 已注册的最新版本；首个版本时传 null（直接兼容）
     * @param next     待注册的新版本
     */
    public CompatibilityReport check(DataContract previous, DataContract next) {
        if (previous == null || next.getCompatibility() == CompatibilityLevel.NONE) {
            return CompatibilityReport.ok();
        }
        CompatibilityLevel level = next.getCompatibility();
        Map<String, ContractField> oldFields = previous.getFields().stream()
                .collect(Collectors.toMap(ContractField::getName, Function.identity()));
        Map<String, ContractField> newFields = next.getFields().stream()
                .collect(Collectors.toMap(ContractField::getName, Function.identity()));

        List<CompatibilityViolation> violations = new ArrayList<>();

        // 1) 删除的字段
        Set<String> removed = new HashSet<>(oldFields.keySet());
        removed.removeAll(newFields.keySet());

        // 2) 新增的字段
        Set<String> added = new HashSet<>(newFields.keySet());
        added.removeAll(oldFields.keySet());

        // 3) 重命名检测：删除与新增字段中类型+必填性完全一致的配对，视为重命名
        Set<String> renamedOld = new HashSet<>();
        Set<String> renamedNew = new HashSet<>();
        for (String oldName : removed) {
            ContractField oldField = oldFields.get(oldName);
            for (String newName : added) {
                if (renamedNew.contains(newName)) {
                    continue;
                }
                ContractField newField = newFields.get(newName);
                if (oldField.getType().equals(newField.getType())
                        && oldField.isRequired() == newField.isRequired()) {
                    renamedOld.add(oldName);
                    renamedNew.add(newName);
                    violations.add(new CompatibilityViolation(ChangeType.FIELD_RENAMED, oldName,
                            "字段 '" + oldName + "' 疑似重命名为 '" + newName + "'，按删除+新增处理"));
                    break;
                }
            }
        }

        // 4) 删除字段（含重命名的旧名）判定——严格跟随级别的读写方向：
        boolean forwardSafe = level == CompatibilityLevel.FORWARD || level == CompatibilityLevel.FULL;
        // FORWARD：旧读者必须能读新数据；若删掉的是必填字段，旧契约仍要求该字段存在，
        //          而新数据已不再提供 -> 旧读者读丢，必须拦住；删掉的若是可选字段，
        //          旧读者本就要容忍其缺失，合法放行。
        // BACKWARD：新读者读旧数据；旧数据仍携带被删字段，新读者忽略多余字段即可，
        //          删除必填/可选都不会读丢，一律放行。
        // FULL：等价于双向同时成立，因此仅 FORWARD 一侧会读丢的“删除必填”拦住，
        //       删除可选双向都能容忍，不得误拒。
        if (forwardSafe) {
            for (String name : removed) {
                ContractField removedField = oldFields.get(name);
                if (removedField.isRequired()) {
                    violations.add(new CompatibilityViolation(ChangeType.FIELD_REMOVED, name,
                            "删除必填字段 '" + name + "'：级别 " + level
                                    + " 要求旧读者能读取新数据，而新契约不再提供该必填字段，旧读者会读丢"));
                }
            }
        }

        // 5) 新增字段判定：必填新增在 BACKWARD/FULL 下拒绝（旧数据中不存在该字段）
        boolean backwardSafe = level == CompatibilityLevel.BACKWARD || level == CompatibilityLevel.FULL;
        for (String name : added) {
            ContractField field = newFields.get(name);
            if (field.isRequired() && backwardSafe) {
                violations.add(new CompatibilityViolation(ChangeType.FIELD_ADDED, name,
                        "级别 " + level + " 不允许新增必填字段（旧数据中不存在该字段）"));
            }
        }

        // 6) 共有字段：类型变更与必填性调整
        // FORWARD/FULL：旧读者必须能读新数据
        for (String name : oldFields.keySet()) {
            ContractField oldField = oldFields.get(name);
            ContractField newField = newFields.get(name);
            if (newField == null) {
                continue;
            }
            if (!oldField.getType().equals(newField.getType())) {
                violations.add(new CompatibilityViolation(ChangeType.TYPE_CHANGED, name,
                        "类型由 " + oldField.getType() + " 变更为 " + newField.getType()
                                + "，级别 " + level + " 不允许"));
            }
            if (!oldField.isRequired() && newField.isRequired()) {
                // 可选->必填：旧数据中该字段可能缺失，BACKWARD/FULL 下新读者无法保证读到
                if (backwardSafe) {
                    violations.add(new CompatibilityViolation(ChangeType.REQUIRED_TIGHTENED, name,
                            "必填性由可选收紧为必填（旧数据中该字段可能缺失），级别 " + level + " 不允许"));
                }
            }
            if (oldField.isRequired() && !newField.isRequired()) {
                // 必填->可选：新数据中该字段可能缺失，FORWARD/FULL 下旧读者按必填消费会失败
                if (forwardSafe) {
                    violations.add(new CompatibilityViolation(ChangeType.REQUIRED_RELAXED, name,
                            "必填性由必填放宽为可选（旧读者按必填消费，新数据该字段可能缺失），级别 "
                                    + level + " 不允许"));
                }
            }
        }

        // 重命名仅作为提示性依据：若其删除/新增本身未违规（如 FORWARD 下），不应导致拒绝
        if (violations.stream().allMatch(v -> v.changeType() == ChangeType.FIELD_RENAMED)) {
            return CompatibilityReport.ok();
        }
        List<CompatibilityViolation> blocking = violations.stream()
                .filter(v -> v.changeType() != ChangeType.FIELD_RENAMED)
                .toList();
        return blocking.isEmpty()
                ? CompatibilityReport.ok()
                : CompatibilityReport.rejected(mergeRenames(violations));
    }

    /** 把提示性的重命名依据附加到拒绝结论中，保证结论可解释。 */
    private List<CompatibilityViolation> mergeRenames(List<CompatibilityViolation> violations) {
        return List.copyOf(violations);
    }
}
