package com.github.xingjianluo.datacontractgovernancejava.tests;

import com.github.xingjianluo.datacontractgovernancejava.compat.ChangeType;
import com.github.xingjianluo.datacontractgovernancejava.compat.CompatibilityChecker;
import com.github.xingjianluo.datacontractgovernancejava.compat.CompatibilityReport;
import com.github.xingjianluo.datacontractgovernancejava.model.CompatibilityLevel;
import com.github.xingjianluo.datacontractgovernancejava.model.ContractField;
import com.github.xingjianluo.datacontractgovernancejava.model.DataContract;
import com.github.xingjianluo.datacontractgovernancejava.model.SensitivityLevel;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompatibilityCheckerTest {

    private static final Logger log = LoggerFactory.getLogger(CompatibilityCheckerTest.class);

    private final CompatibilityChecker checker = new CompatibilityChecker();

    private static ContractField f(String name, String type, boolean required) {
        return new ContractField(name, type, required, SensitivityLevel.PUBLIC);
    }

    private static DataContract contract(CompatibilityLevel level, ContractField... fields) {
        return new DataContract("orders", level, List.of(fields));
    }

    private CompatibilityReport checkAndLog(String caseName, DataContract prev, DataContract next) {
        CompatibilityReport report = checker.check(prev, next);
        log.info("[{}] 输入: level={}, 旧字段={}, 新字段={} -> 判定: compatible={}, 依据={}",
                caseName, next.getCompatibility(),
                prev.getFields().stream().map(ContractField::fingerprint).toList(),
                next.getFields().stream().map(ContractField::fingerprint).toList(),
                report.compatible(), report.violations());
        return report;
    }

    @Test
    void firstVersionIsAlwaysCompatible() {
        DataContract v1 = contract(CompatibilityLevel.FULL, f("id", "long", true));
        assertTrue(checker.check(null, v1).compatible());
    }

    @Test
    void backwardAllowsFieldRemovalEvenWhenRequired() {
        // BACKWARD：新读者读旧数据。旧数据仍携带被删字段，新读者忽略多余字段即可，
        // 无论被删字段原本必填还是可选都不会读丢，合法放行，不得误伤。
        DataContract v1 = contract(CompatibilityLevel.BACKWARD,
                f("id", "long", true), f("note", "string", false));

        // 删除可选字段
        DataContract dropOptional = contract(CompatibilityLevel.BACKWARD, f("id", "long", true));
        assertTrue(checkAndLog("BACKWARD删除可选字段", v1, dropOptional).compatible(),
                "BACKWARD 下删除可选字段是合法演进，不得误伤");

        // 删除必填字段
        DataContract dropRequired = contract(CompatibilityLevel.BACKWARD, f("note", "string", false));
        assertTrue(checkAndLog("BACKWARD删除必填字段", v1, dropRequired).compatible(),
                "BACKWARD 下删除必填字段同样合法（旧数据仍带该字段，新读者忽略即可）");
    }

    @Test
    void forwardRejectsRemovingRequiredFieldButAllowsOptional() {
        // FORWARD：旧读者必须能读新数据。
        DataContract v1 = contract(CompatibilityLevel.FORWARD,
                f("id", "long", true), f("note", "string", false));

        // 删掉可选字段：旧读者本就容忍其缺失，放行
        DataContract dropOptional = contract(CompatibilityLevel.FORWARD, f("id", "long", true));
        assertTrue(checkAndLog("FORWARD删除可选字段", v1, dropOptional).compatible(),
                "FORWARD 下删除可选字段合法，不得误伤");

        // 删掉必填字段：旧读者按必填消费，新数据不再提供 -> 读丢，必须拦住
        DataContract dropRequired = contract(CompatibilityLevel.FORWARD, f("note", "string", false));
        CompatibilityReport report = checkAndLog("FORWARD删除必填字段", v1, dropRequired);
        assertFalse(report.compatible(), "FORWARD 下删除必填字段是破坏性变更，必须拦住");
        assertTrue(report.violations().stream().anyMatch(v -> v.changeType() == ChangeType.FIELD_REMOVED),
                "拒绝依据必须按变更种类标注为 FIELD_REMOVED");
    }

    @Test
    void fieldRemovalMatrixAcrossLevelsAndRequiredness() {
        // 删除字段结论矩阵（行=兼容级别，列=被删字段原本的必填性）：
        //                 删除必填   删除可选
        //  BACKWARD         允许       允许
        //  FORWARD          拒绝       允许
        //  FULL             拒绝       允许
        for (CompatibilityLevel level : List.of(CompatibilityLevel.BACKWARD,
                CompatibilityLevel.FORWARD, CompatibilityLevel.FULL)) {
            DataContract prev = contract(level,
                    f("id", "long", true), f("note", "string", false));

            CompatibilityReport dropRequired = checkAndLog(level + "-删除必填",
                    prev, contract(level, f("note", "string", false)));
            boolean requiredShouldReject = level != CompatibilityLevel.BACKWARD;
            assertEqualsCompat(requiredShouldReject, dropRequired.compatible(), level, "删除必填字段");
            if (requiredShouldReject) {
                assertTrue(dropRequired.violations().stream()
                        .anyMatch(v -> v.changeType() == ChangeType.FIELD_REMOVED));
            }

            CompatibilityReport dropOptional = checkAndLog(level + "-删除可选",
                    prev, contract(level, f("id", "long", true)));
            assertTrue(dropOptional.compatible(),
                    level + " 下删除可选字段双向都可容忍，必须放行，不得误伤");
        }
    }

    private static void assertEqualsCompat(boolean expectedRejected, boolean actualCompatible,
                                           CompatibilityLevel level, String change) {
        if (expectedRejected) {
            assertFalse(actualCompatible, level + " 下" + change + "必须拦住");
        } else {
            assertTrue(actualCompatible, level + " 下" + change + "必须放行");
        }
    }

    @Test
    void backwardRejectsNewRequiredFieldButAllowsOptional() {
        DataContract v1 = contract(CompatibilityLevel.BACKWARD, f("id", "long", true));
        DataContract v2Required = contract(CompatibilityLevel.BACKWARD, f("id", "long", true), f("code", "string", true));
        CompatibilityReport report = checkAndLog("BACKWARD新增必填", v1, v2Required);
        assertFalse(report.compatible());
        assertTrue(report.violations().stream().anyMatch(v -> v.changeType() == ChangeType.FIELD_ADDED));

        DataContract v2Optional = contract(CompatibilityLevel.BACKWARD, f("id", "long", true), f("code", "string", false));
        assertTrue(checkAndLog("BACKWARD新增可选", v1, v2Optional).compatible());
    }

    @Test
    void typeChangeRejectedUnderAllStrictLevels() {
        for (CompatibilityLevel level : List.of(CompatibilityLevel.BACKWARD, CompatibilityLevel.FORWARD, CompatibilityLevel.FULL)) {
            DataContract v1 = contract(level, f("id", "long", true));
            DataContract v2 = contract(level, f("id", "string", true));
            CompatibilityReport report = checkAndLog(level + "类型变更", v1, v2);
            assertFalse(report.compatible(), level + " 应拒绝类型变更");
            assertTrue(report.violations().stream().anyMatch(v -> v.changeType() == ChangeType.TYPE_CHANGED));
        }
    }

    @Test
    void noneLevelAllowsAnyChange() {
        DataContract v1 = contract(CompatibilityLevel.NONE, f("id", "long", true));
        DataContract v2 = contract(CompatibilityLevel.NONE, f("id", "string", true));
        assertTrue(checkAndLog("NONE任意变更", v1, v2).compatible());
    }

    @Test
    void requiredTighteningRejectedUnderBackwardButRelaxationAllowed() {
        DataContract v1 = contract(CompatibilityLevel.BACKWARD, f("id", "long", false));
        DataContract v2 = contract(CompatibilityLevel.BACKWARD, f("id", "long", true));
        CompatibilityReport report = checkAndLog("BACKWARD必填收紧", v1, v2);
        assertFalse(report.compatible());
        assertTrue(report.violations().stream().anyMatch(v -> v.changeType() == ChangeType.REQUIRED_TIGHTENED));

        DataContract v3 = contract(CompatibilityLevel.BACKWARD, f("id", "long", true));
        DataContract v4 = contract(CompatibilityLevel.BACKWARD, f("id", "long", false));
        assertTrue(checkAndLog("BACKWARD必填放宽", v3, v4).compatible());
    }

    @Test
    void renameDetectedAndRejectedUnderBackward() {
        DataContract v1 = contract(CompatibilityLevel.BACKWARD, f("user_id", "long", true));
        DataContract v2 = contract(CompatibilityLevel.BACKWARD, f("uid", "long", true));
        CompatibilityReport report = checkAndLog("BACKWARD重命名", v1, v2);
        assertFalse(report.compatible());
        assertTrue(report.violations().stream().anyMatch(v -> v.changeType() == ChangeType.FIELD_RENAMED),
                "判定依据中应包含重命名提示");
    }

    @Test
    void requiredRelaxationDirectionFollowsCompatibilityLevel() {
        // 必填 -> 可选：
        // BACKWARD 允许（新读者能容忍旧数据里该字段一直存在）
        DataContract backOld = contract(CompatibilityLevel.BACKWARD, f("id", "long", true));
        DataContract backNew = contract(CompatibilityLevel.BACKWARD, f("id", "long", false));
        assertTrue(checkAndLog("BACKWARD必填放宽", backOld, backNew).compatible(),
                "BACKWARD 下必填->可选是合法演进，不得误伤");

        // FORWARD 拒绝（旧读者按必填消费，新数据该字段可能缺失）
        DataContract fwdOld = contract(CompatibilityLevel.FORWARD, f("id", "long", true));
        DataContract fwdNew = contract(CompatibilityLevel.FORWARD, f("id", "long", false));
        CompatibilityReport fwdReport = checkAndLog("FORWARD必填放宽", fwdOld, fwdNew);
        assertFalse(fwdReport.compatible(), "FORWARD 下必填->可选破坏旧读者，必须拦住");
        assertTrue(fwdReport.violations().stream()
                .anyMatch(v -> v.changeType() == ChangeType.REQUIRED_RELAXED));

        // FULL 双向约束，同样拒绝
        DataContract fullOld = contract(CompatibilityLevel.FULL, f("id", "long", true));
        DataContract fullNew = contract(CompatibilityLevel.FULL, f("id", "long", false));
        CompatibilityReport fullReport = checkAndLog("FULL必填放宽", fullOld, fullNew);
        assertFalse(fullReport.compatible(), "FULL 下必填->可选必须拦住");
        assertTrue(fullReport.violations().stream()
                .anyMatch(v -> v.changeType() == ChangeType.REQUIRED_RELAXED));
    }

    @Test
    void requiredTighteningDirectionFollowsCompatibilityLevel() {
        // 可选 -> 必填：FORWARD 允许（旧数据始终带该字段时旧读者不受影响，新数据只会更严格）
        DataContract fwdOld = contract(CompatibilityLevel.FORWARD, f("id", "long", false));
        DataContract fwdNew = contract(CompatibilityLevel.FORWARD, f("id", "long", true));
        assertTrue(checkAndLog("FORWARD必填收紧", fwdOld, fwdNew).compatible(),
                "FORWARD 下可选->必填是合法演进，不得误伤");

        // FULL 拒绝（与 BACKWARD 同向的破坏性变化必须拦住）
        DataContract fullOld = contract(CompatibilityLevel.FULL, f("id", "long", false));
        DataContract fullNew = contract(CompatibilityLevel.FULL, f("id", "long", true));
        CompatibilityReport fullReport = checkAndLog("FULL必填收紧", fullOld, fullNew);
        assertFalse(fullReport.compatible(), "FULL 下可选->必填必须拦住");
        assertTrue(fullReport.violations().stream()
                .anyMatch(v -> v.changeType() == ChangeType.REQUIRED_TIGHTENED));
    }

    @Test
    void fullLevelRejectsRemovingRequiredFieldAndRequiredAddition() {
        // FULL 双向约束：删除必填字段在 FORWARD 一侧读丢 -> 拒；新增必填字段在 BACKWARD 一侧读丢 -> 拒。
        DataContract v1 = contract(CompatibilityLevel.FULL,
                f("id", "long", true), f("note", "string", true));
        DataContract v2 = contract(CompatibilityLevel.FULL,
                f("id", "long", true), f("code", "string", true));
        CompatibilityReport report = checkAndLog("FULL删除必填+新增必填", v1, v2);
        assertFalse(report.compatible());
        assertTrue(report.violations().stream().anyMatch(v -> v.changeType() == ChangeType.FIELD_REMOVED));
        assertTrue(report.violations().stream().anyMatch(v -> v.changeType() == ChangeType.FIELD_ADDED));
    }

    @Test
    void fullLevelAllowsRemovingOptionalField() {
        // FULL 下删除可选字段：BACKWARD 一侧旧数据仍带该字段（新读者忽略），
        // FORWARD 一侧旧读者本就容忍可选字段缺失，双向都能容忍，必须放行。
        DataContract v1 = contract(CompatibilityLevel.FULL,
                f("id", "long", true), f("note", "string", false));
        DataContract v2 = contract(CompatibilityLevel.FULL, f("id", "long", true));
        assertTrue(checkAndLog("FULL删除可选字段", v1, v2).compatible(),
                "FULL 不得把单向限制套到整个级别：双向都能容忍的删除不应被拒");
    }
}
