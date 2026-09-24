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
 * 规则（NONE 一律放行）：
 * - 删除字段：BACKWARD/FULL 拒绝（新读者读旧数据时字段缺失），FORWARD 允许；
 * - 新增必填字段：BACKWARD/FULL 拒绝（旧数据无该字段），FORWARD 允许；
 * - 新增可选字段：各级别均允许；
 * - 类型变更：BACKWARD/FORWARD/FULL 均拒绝；
 * - 必填收紧（可选->必填）：BACKWARD/FULL 拒绝；放宽（必填->可选）各级别允许；
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

        // 4) 删除字段（含重命名的旧名）判定
        boolean forbidRemoval = level == CompatibilityLevel.BACKWARD || level == CompatibilityLevel.FULL;
        if (forbidRemoval) {
            for (String name : removed) {
                violations.add(new CompatibilityViolation(ChangeType.FIELD_REMOVED, name,
                        "级别 " + level + " 不允许删除字段（新读者需能读取旧数据）"));
            }
        }

        // 5) 新增字段判定：必填新增在 BACKWARD/FULL 下拒绝
        for (String name : added) {
            ContractField field = newFields.get(name);
            if (field.isRequired() && forbidRemoval) {
                violations.add(new CompatibilityViolation(ChangeType.FIELD_ADDED, name,
                        "级别 " + level + " 不允许新增必填字段（旧数据中不存在该字段）"));
            }
        }

        // 6) 共有字段：类型变更与必填性调整
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
                if (forbidRemoval) {
                    violations.add(new CompatibilityViolation(ChangeType.REQUIRED_TIGHTENED, name,
                            "必填性由可选收紧为必填，级别 " + level + " 不允许"));
                }
            }
            // 必填->可选（REQUIRED_RELAXED）在所有级别下均视为安全，不产生违规
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
