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
    void backwardAllowsFieldRemoval() {
        // BACKWARD=新读者读旧数据：旧数据仍带被删字段，新读者忽略多余字段，删除不破坏 BACKWARD 方向
        DataContract v1 = contract(CompatibilityLevel.BACKWARD, f("id", "long", true), f("note", "string", false));
        DataContract v2 = contract(CompatibilityLevel.BACKWARD, f("id", "long", true));
        assertTrue(checkAndLog("BACKWARD删除可选字段", v1, v2).compatible());

        DataContract v3 = contract(CompatibilityLevel.BACKWARD, f("id", "long", true), f("code", "string", true));
        DataContract v4 = contract(CompatibilityLevel.BACKWARD, f("id", "long", true));
        assertTrue(checkAndLog("BACKWARD删除必填字段", v3, v4).compatible());
    }

    @Test
    void forwardAllowsOptionalFieldRemovalButRejectsRequiredRemoval() {
        // FORWARD=旧读者读新数据：被删字段可选时旧读者已容忍缺失；必填时新数据缺失会让旧读者失败
        DataContract v1 = contract(CompatibilityLevel.FORWARD, f("id", "long", true), f("note", "string", false));
        DataContract v2 = contract(CompatibilityLevel.FORWARD, f("id", "long", true));
        assertTrue(checkAndLog("FORWARD删除可选字段", v1, v2).compatible());

        DataContract v3 = contract(CompatibilityLevel.FORWARD, f("id", "long", true), f("code", "string", true));
        DataContract v4 = contract(CompatibilityLevel.FORWARD, f("id", "long", true));
        CompatibilityReport requiredRemoved = checkAndLog("FORWARD删除必填字段", v3, v4);
        assertFalse(requiredRemoved.compatible());
        assertTrue(requiredRemoved.violations().stream().anyMatch(v -> v.changeType() == ChangeType.FIELD_REMOVED));
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
    void fullLevelRejectsRequiredRemovalAndRequiredAddition() {
        // FULL 两个方向都校验：删除必填字段破坏 FORWARD，新增必填字段破坏 BACKWARD
        DataContract v1 = contract(CompatibilityLevel.FULL, f("id", "long", true), f("note", "string", true));
        DataContract v2 = contract(CompatibilityLevel.FULL, f("id", "long", true), f("code", "string", true));
        CompatibilityReport report = checkAndLog("FULL删除必填+新增必填", v1, v2);
        assertFalse(report.compatible());
        assertTrue(report.violations().stream().anyMatch(v -> v.changeType() == ChangeType.FIELD_REMOVED));
        assertTrue(report.violations().stream().anyMatch(v -> v.changeType() == ChangeType.FIELD_ADDED));
    }

    @Test
    void requiredRelaxationRejectedUnderForwardAndFullButAllowedUnderBackward() {
        // 必填->可选：新数据可能缺字段，旧读者仍按必填读 -> FORWARD/FULL 拒绝；
        // 新读者对旧数据（字段必在）只会更宽松 -> BACKWARD 合法
        DataContract prev = contract(CompatibilityLevel.BACKWARD, f("id", "long", true));
        DataContract nextB = contract(CompatibilityLevel.BACKWARD, f("id", "long", false));
        assertTrue(checkAndLog("BACKWARD必填放宽", prev, nextB).compatible());

        DataContract nextF = contract(CompatibilityLevel.FORWARD, f("id", "long", false));
        CompatibilityReport forward = checkAndLog("FORWARD必填放宽", prev, nextF);
        assertFalse(forward.compatible());
        assertTrue(forward.violations().stream().anyMatch(v -> v.changeType() == ChangeType.REQUIRED_RELAXED));

        DataContract nextFull = contract(CompatibilityLevel.FULL, f("id", "long", false));
        CompatibilityReport full = checkAndLog("FULL必填放宽", prev, nextFull);
        assertFalse(full.compatible());
        assertTrue(full.violations().stream().anyMatch(v -> v.changeType() == ChangeType.REQUIRED_RELAXED));
    }
}
