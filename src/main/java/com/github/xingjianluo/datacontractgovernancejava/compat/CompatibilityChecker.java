package com.github.xingjianluo.datacontractgovernancejava.compat;

import com.github.xingjianluo.datacontractgovernancejava.model.CompatibilityLevel;
import com.github.xingjianluo.datacontractgovernancejava.model.ContractField;
import com.github.xingjianluo.datacontractgovernancejava.model.DataContract;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 按契约声明的兼容级别，对新旧两版契约做结构演进判定。
 *
 * 方向语义（NONE 一律放行）：
 * - BACKWARD：新版读者必须能读取旧版数据（新读旧）；
 * - FORWARD：旧版读者必须能读取新版数据（旧读新）；
 * - FULL：两个方向都必须成立。
 *
 * 据此推导的判定矩阵：
 * <pre>
 * 变更                         BACKWARD   FORWARD   FULL
 * 删除可选字段                  允许       允许      允许
 * 删除必填字段                  允许       拒绝      拒绝
 * 新增可选字段                  允许       允许      允许
 * 新增必填字段                  拒绝       允许      拒绝
 * 必填收紧（可选 -> 必填）       拒绝       允许      拒绝
 * 必填放宽（必填 -> 可选）       允许       拒绝      拒绝
 * 类型变更                      拒绝       拒绝      拒绝
 * </pre>
 * 重命名视为删除+新增（类型与必填性一致的删除/新增配对），按上述规则判定，
 * 并在结论中附加非阻断的 FIELD_RENAMED 提示依据。
 */
@Component
public class CompatibilityChecker {

    private static final Logger log = LoggerFactory.getLogger(CompatibilityChecker.class);

    /**
     * @param previous 已注册的参照版本；首个版本时传 null（直接兼容）
     * @param next     候选新版本，兼容级别取其自身声明
     */
    public CompatibilityReport check(DataContract previous, DataContract next) {
        return check(previous, next, next.getCompatibility());
    }

    /**
     * 用显式指定的兼容级别判定（预检时可临时指定与候选契约声明不同的级别，
     * 判定逻辑与正式注册完全一致）。
     */
    public CompatibilityReport check(DataContract previous, DataContract next, CompatibilityLevel level) {
        if (previous == null || level == CompatibilityLevel.NONE) {
            CompatibilityReport report = CompatibilityReport.ok();
            log.info("兼容判定: level={}, 参照版本={}, 候选指纹={} -> compatible=true（首个版本或 NONE 放行）",
                    level, previous == null ? "无" : previous.fingerprint(),
                    next == null ? "无" : next.fingerprint());
            return report;
        }

        Map<String, ContractField> oldFields = previous.getFields().stream()
                .collect(Collectors.toMap(ContractField::getName, Function.identity(),
                        (a, b) -> a, LinkedHashMap::new));
        Map<String, ContractField> newFields = next.getFields().stream()
                .collect(Collectors.toMap(ContractField::getName, Function.identity(),
                        (a, b) -> a, LinkedHashMap::new));

        // BACKWARD 校验“新读者读旧数据”；FORWARD 校验“旧读者读新数据”；FULL 两个方向都校验。
        boolean checkBackward = level == CompatibilityLevel.BACKWARD || level == CompatibilityLevel.FULL;
        boolean checkForward = level == CompatibilityLevel.FORWARD || level == CompatibilityLevel.FULL;

        List<CompatibilityViolation> violations = new ArrayList<>();

        // 1) 重命名提示：删除与新增中“类型+必填性”完全一致的配对（非阻断，仅增强可解释性）
        Set<String> removed = new LinkedHashSet<>(oldFields.keySet());
        removed.removeAll(newFields.keySet());
        Set<String> added = new LinkedHashSet<>(newFields.keySet());
        added.removeAll(oldFields.keySet());
        Set<String> renamedNew = new LinkedHashSet<>();
        for (String oldName : removed) {
            ContractField oldField = oldFields.get(oldName);
            for (String newName : added) {
                if (renamedNew.contains(newName)) {
                    continue;
                }
                ContractField newField = newFields.get(newName);
                if (oldField.getType().equals(newField.getType())
                        && oldField.isRequired() == newField.isRequired()) {
                    renamedNew.add(newName);
                    violations.add(new CompatibilityViolation(ChangeType.FIELD_RENAMED, oldName,
                            "字段 '" + oldName + "' 疑似重命名为 '" + newName + "'，按删除+新增处理"));
                    break;
                }
            }
        }

        // 2) 删除字段：仅影响 FORWARD 方向（旧读者读新数据）。
        //    被删字段必填时，新数据缺失它会让旧读者失败；被删字段本就可选时，旧读者已容忍缺失。
        //    BACKWARD 方向旧数据仍带该字段，新读者忽略多余字段即可，故从不阻断。
        if (checkForward) {
            for (String name : removed) {
                ContractField oldField = oldFields.get(name);
                if (oldField.isRequired()) {
                    violations.add(new CompatibilityViolation(ChangeType.FIELD_REMOVED, name,
                            "级别 " + level + " 不允许删除必填字段 '" + name
                                    + "'（旧读者仍按必填读取，新数据缺失该字段会失败）"));
                }
            }
        }

        // 3) 新增字段：仅影响 BACKWARD 方向（新读者读旧数据）。
        //    必填新增时旧数据没有该字段；可选新增时新读者已容忍缺失。
        if (checkBackward) {
            for (String name : added) {
                ContractField field = newFields.get(name);
                if (field.isRequired()) {
                    violations.add(new CompatibilityViolation(ChangeType.FIELD_ADDED, name,
                            "级别 " + level + " 不允许新增必填字段 '" + name
                                    + "'（新读者读取不含该字段的旧数据时会失败）"));
                }
            }
        }

        // 4) 共有字段：类型变更两个方向都破坏；必填性调整只影响其中一个方向
        for (String name : oldFields.keySet()) {
            ContractField oldField = oldFields.get(name);
            ContractField newField = newFields.get(name);
            if (newField == null) {
                continue;
            }
            if (!oldField.getType().equals(newField.getType())) {
                violations.add(new CompatibilityViolation(ChangeType.TYPE_CHANGED, name,
                        "字段 '" + name + "' 类型由 " + oldField.getType() + " 变更为 "
                                + newField.getType() + "，级别 " + level + " 不允许"));
            }
            if (!oldField.isRequired() && newField.isRequired() && checkBackward) {
                violations.add(new CompatibilityViolation(ChangeType.REQUIRED_TIGHTENED, name,
                        "字段 '" + name + "' 必填性由可选收紧为必填，级别 " + level
                                + " 不允许（新读者读取可能缺失该字段的旧数据时会失败）"));
            }
            if (oldField.isRequired() && !newField.isRequired() && checkForward) {
                violations.add(new CompatibilityViolation(ChangeType.REQUIRED_RELAXED, name,
                        "字段 '" + name + "' 必填性由必填放宽为可选，级别 " + level
                                + " 不允许（旧读者仍按必填读取，新数据可能缺失该字段）"));
            }
        }

        List<CompatibilityViolation> blocking = violations.stream()
                .filter(v -> v.changeType() != ChangeType.FIELD_RENAMED)
                .toList();
        CompatibilityReport report = blocking.isEmpty()
                ? CompatibilityReport.ok()
                : CompatibilityReport.rejected(violations);
        log.info("兼容判定: level={}, 参照指纹={}, 候选指纹={} -> compatible={}, 判定依据={}",
                level, previous.fingerprint(), next.fingerprint(), report.compatible(), report.violations());
        return report;
    }
}
