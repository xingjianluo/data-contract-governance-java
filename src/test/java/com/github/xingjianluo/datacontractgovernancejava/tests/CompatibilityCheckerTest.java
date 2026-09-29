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

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    void fieldRemovalMatrixAcrossLevelsAndRequiredness() {
        // 矩阵：级别（行）× 被删字段必填性（列）
        //                         删必填  删可选
        // BACKWARD（新读旧）        放行    放行
        // FORWARD（旧读新）        拒绝    放行
        // FULL（双向）             拒绝    放行
        record Row(CompatibilityLevel level, boolean removeRequired, boolean expectCompatible) {
        }
        List<Row> rows = List.of(
                new Row(CompatibilityLevel.BACKWARD, true, true),
                new Row(CompatibilityLevel.BACKWARD, false, true),
                new Row(CompatibilityLevel.FORWARD, true, false),
                new Row(CompatibilityLevel.FORWARD, false, true),
                new Row(CompatibilityLevel.FULL, true, false),
                new Row(CompatibilityLevel.FULL, false, true)
        );
        for (Row row : rows) {
            DataContract prev = contract(row.level,
                    f("id", "long", true), f("note", "string", false));
            DataContract next = row.removeRequired
                    ? contract(row.level, f("note", "string", false))
                    : contract(row.level, f("id", "long", true));
            String caseName = row.level + (row.removeRequired ? "删除必填" : "删除可选");
            CompatibilityReport report = checkAndLog(caseName, prev, next);
            assertEquals(row.expectCompatible, report.compatible(),
                    caseName + " 期望 compatible=" + row.expectCompatible);
            if (!row.expectCompatible) {
                assertTrue(report.violations().stream()
                        .anyMatch(v -> v.changeType() == ChangeType.FIELD_REMOVED));
            }
        }
    }

    @Test
    void firstVersionIsAlwaysCompatible() {
        DataContract v1 = contract(CompatibilityLevel.FULL, f("id", "long", true));
        assertTrue(checker.check(null, v1).compatible());
    }

    @Test
    void backwardAllowsRemovalOfRequiredAndOptionalFields() {
        // BACKWARD：新读者读旧数据，旧数据中多出的字段可直接忽略，删除必填/可选均合法
        DataContract v1 = contract(CompatibilityLevel.BACKWARD,
                f("id", "long", true), f("note", "string", false));

        DataContract removeOptional = contract(CompatibilityLevel.BACKWARD, f("id", "long", true));
        assertTrue(checkAndLog("BACKWARD删除可选字段", v1, removeOptional).compatible(),
                "BACKWARD 下删除可选字段必须放行，不得误伤");

        DataContract removeRequired = contract(CompatibilityLevel.BACKWARD, f("note", "string", false));
        assertTrue(checkAndLog("BACKWARD删除必填字段", v1, removeRequired).compatible(),
                "BACKWARD 下删除必填字段同样必须放行（新读者读的是旧数据，多出的 id 被忽略）");
    }

    @Test
    void forwardRejectsRequiredRemovalButAllowsOptionalRemoval() {
        // FORWARD：旧读者读新数据。旧契约里必填的字段在新数据中缺失 -> 破坏读取，必须拦住
        DataContract v1 = contract(CompatibilityLevel.FORWARD,
                f("id", "long", true), f("note", "string", false));

        DataContract removeRequired = contract(CompatibilityLevel.FORWARD, f("note", "string", false));
        CompatibilityReport requiredReport = checkAndLog("FORWARD删除必填字段", v1, removeRequired);
        assertFalse(requiredReport.compatible(), "FORWARD 下删除必填字段必须拦住");
        assertTrue(requiredReport.violations().stream()
                .anyMatch(v -> v.changeType() == ChangeType.FIELD_REMOVED
                        && v.field().equals("id")));

        // 删除可选字段：旧读者本就能容忍可选字段缺失，必须放行
        DataContract removeOptional = contract(CompatibilityLevel.FORWARD, f("id", "long", true));
        assertTrue(checkAndLog("FORWARD删除可选字段", v1, removeOptional).compatible(),
                "FORWARD 下删除可选字段必须放行，不得误伤");
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
    void fullLevelRemovalFollowsTwoWayReadSemantics() {
        // FULL 双向成立：删除可选字段两侧读方都能容忍，必须放行（不得把单向限制套到整个级别）
        DataContract v1 = contract(CompatibilityLevel.FULL,
                f("id", "long", true), f("note", "string", false));
        DataContract removeOptional = contract(CompatibilityLevel.FULL, f("id", "long", true));
        assertTrue(checkAndLog("FULL删除可选字段", v1, removeOptional).compatible(),
                "FULL 下删除可选字段双向都能容忍，不应被拒");

        // 删除必填字段：FORWARD 方向（旧读者读新数据）读丢必填字段，必须拦住
        DataContract removeRequired = contract(CompatibilityLevel.FULL, f("note", "string", false));
        CompatibilityReport removeRequiredReport = checkAndLog("FULL删除必填字段", v1, removeRequired);
        assertFalse(removeRequiredReport.compatible(), "FULL 下删除必填字段必须拦住");
        assertTrue(removeRequiredReport.violations().stream()
                .anyMatch(v -> v.changeType() == ChangeType.FIELD_REMOVED && v.field().equals("id")));
    }

    @Test
    void fullLevelRejectsRequiredAdditionButAllowsOptionalRemovalCombined() {
        // 删除可选 note（双向可容忍）+ 新增必填 code（BACKWARD 方向旧数据没有该字段）
        // 仅新增必填构成破坏，删除可选不应产生依据
        DataContract v1 = contract(CompatibilityLevel.FULL, f("id", "long", true), f("note", "string", false));
        DataContract v2 = contract(CompatibilityLevel.FULL, f("id", "long", true), f("code", "string", true));
        CompatibilityReport report = checkAndLog("FULL删除可选+新增必填", v1, v2);
        assertFalse(report.compatible());
        assertTrue(report.violations().stream().anyMatch(v -> v.changeType() == ChangeType.FIELD_ADDED));
        assertTrue(report.violations().stream().noneMatch(v -> v.changeType() == ChangeType.FIELD_REMOVED),
                "删除可选字段双向可容忍，不得出现在拒绝依据中");
    }
}
