package com.github.xingjianluo.datacontractgovernancejava.tests.contract;

import com.github.xingjianluo.datacontractgovernancejava.contract.model.CompatibilityMode;
import com.github.xingjianluo.datacontractgovernancejava.contract.model.CompatibilityReport;
import com.github.xingjianluo.datacontractgovernancejava.contract.model.ContractDefinition;
import com.github.xingjianluo.datacontractgovernancejava.contract.model.ContractVersion;
import com.github.xingjianluo.datacontractgovernancejava.contract.model.FieldDefinition;
import com.github.xingjianluo.datacontractgovernancejava.contract.model.FieldType;
import com.github.xingjianluo.datacontractgovernancejava.contract.service.ContractCompatibilityChecker;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 覆盖新增/删除、类型变更、必填性调整与重命名在各兼容级别下的稳定结论。 */
class ContractCompatibilityCheckerTest {

    private static final Logger log = LoggerFactory.getLogger(ContractCompatibilityCheckerTest.class);

    private final ContractCompatibilityChecker checker = new ContractCompatibilityChecker();

    private static FieldDefinition field(String name, FieldType type, boolean required) {
        return new FieldDefinition(name, type, required, null, null);
    }

    private static FieldDefinition rename(String newName, FieldType type, boolean required, String oldName) {
        return new FieldDefinition(newName, type, required, oldName, null);
    }

    private static ContractVersion v1(List<FieldDefinition> fields) {
        return new ContractVersion("orders", 1, CompatibilityMode.BACKWARD, fields, Instant.now());
    }

    private static ContractDefinition candidate(int version, CompatibilityMode mode,
                                                List<FieldDefinition> fields) {
        return new ContractDefinition("orders", version, mode, fields);
    }

    private void logInput(String scenario, ContractVersion prev, ContractDefinition cand) {
        log.info("[CASE] scenario={}\n  input.prev={}\n  input.next={}",
                scenario,
                prev == null ? null : prev.fields().stream().map(f -> f.name()
                        + "(type=" + f.type() + ",required=" + f.required()
                        + (f.isRename() ? ",renamedFrom=" + f.renamedFrom() : "") + ")").toList(),
                cand.fields().stream().map(f -> f.name()
                        + "(type=" + f.type() + ",required=" + f.required()
                        + (f.isRename() ? ",renamedFrom=" + f.renamedFrom() : "") + ")").toList());
    }

    @Test
    void addingOptionalFieldIsBackwardCompatible() {
        ContractVersion prev = v1(List.of(field("id", FieldType.LONG, true)));
        ContractDefinition cand = candidate(2, CompatibilityMode.BACKWARD,
                List.of(field("id", FieldType.LONG, true),
                        field("remark", FieldType.STRING, false)));
        logInput("add optional / BACKWARD", prev, cand);
        CompatibilityReport report = checker.check(prev, cand);
        log.info("[CASE] result compatible={} violations={}", report.compatible(), report.violations());
        assertTrue(report.compatible());
    }

    @Test
    void addingRequiredFieldBreaksBackwardButAllowedForward() {
        ContractVersion prev = v1(List.of(field("id", FieldType.LONG, true)));
        List<FieldDefinition> next = List.of(field("id", FieldType.LONG, true),
                field("new_required", FieldType.STRING, true));

        ContractDefinition backward = candidate(2, CompatibilityMode.BACKWARD, next);
        logInput("add required / BACKWARD", prev, backward);
        CompatibilityReport r1 = checker.check(prev, backward);
        log.info("[CASE] BACKWARD result compatible={} violations={}", r1.compatible(), r1.violations());
        assertFalse(r1.compatible());
        assertEquals("ADDED_REQUIRED_FIELD", r1.violations().get(0).reason());
        assertEquals("new_required", r1.violations().get(0).fieldName());

        ContractDefinition forward = candidate(2, CompatibilityMode.FORWARD, next);
        logInput("add required / FORWARD", prev, forward);
        CompatibilityReport r2 = checker.check(prev, forward);
        log.info("[CASE] FORWARD result compatible={} violations={}", r2.compatible(), r2.violations());
        assertTrue(r2.compatible(), "FORWARD 允许新增必填字段");
    }

    @Test
    void removingRequiredFieldBreaksForward() {
        ContractVersion prev = v1(List.of(field("id", FieldType.LONG, true),
                field("ssn", FieldType.STRING, true)));
        ContractDefinition cand = candidate(2, CompatibilityMode.FORWARD,
                List.of(field("id", FieldType.LONG, true)));
        logInput("remove required / FORWARD", prev, cand);
        CompatibilityReport report = checker.check(prev, cand);
        log.info("[CASE] result compatible={} violations={}", report.compatible(), report.violations());
        assertFalse(report.compatible());
        assertEquals("REMOVED_REQUIRED_FIELD", report.violations().get(0).reason());
        assertEquals("ssn", report.violations().get(0).fieldName());
    }

    @Test
    void typeChangeIsIncompatibleInAllDirectionalModes() {
        ContractVersion prev = v1(List.of(field("id", FieldType.INTEGER, true)));
        for (CompatibilityMode mode : List.of(CompatibilityMode.BACKWARD,
                CompatibilityMode.FORWARD, CompatibilityMode.FULL)) {
            ContractDefinition cand = candidate(2, mode,
                    List.of(field("id", FieldType.STRING, true)));
            logInput("type change / " + mode, prev, cand);
            CompatibilityReport report = checker.check(prev, cand);
            log.info("[CASE] {} result compatible={} violations={}", mode, report.compatible(),
                    report.violations());
            assertFalse(report.compatible());
            assertEquals("FIELD_TYPE_CHANGED", report.violations().get(0).reason());
        }
    }

    @Test
    void tighteningOptionalityBreaksBackwardRelaxingBreaksForward() {
        ContractVersion prevOpt = v1(List.of(field("nickname", FieldType.STRING, false)));
        ContractDefinition tightened = candidate(2, CompatibilityMode.BACKWARD,
                List.of(field("nickname", FieldType.STRING, true)));
        logInput("optional->required / BACKWARD", prevOpt, tightened);
        CompatibilityReport r1 = checker.check(prevOpt, tightened);
        log.info("[CASE] result compatible={} violations={}", r1.compatible(), r1.violations());
        assertFalse(r1.compatible());
        assertEquals("REQUIRED_CONSTRAINT_TIGHTENED", r1.violations().get(0).reason());

        ContractVersion prevReq = v1(List.of(field("nickname", FieldType.STRING, true)));
        ContractDefinition relaxed = candidate(2, CompatibilityMode.FORWARD,
                List.of(field("nickname", FieldType.STRING, false)));
        logInput("required->optional / FORWARD", prevReq, relaxed);
        CompatibilityReport r2 = checker.check(prevReq, relaxed);
        log.info("[CASE] result compatible={} violations={}", r2.compatible(), r2.violations());
        assertFalse(r2.compatible());
        assertEquals("REQUIRED_CONSTRAINT_RELAXED", r2.violations().get(0).reason());
    }

    @Test
    void renameRulesDependOnModeAndOptionality() {
        // 双方都可选：任何方向都兼容
        ContractVersion bothOptional = new ContractVersion("orders", 1, CompatibilityMode.FULL,
                List.of(field("name", FieldType.STRING, false)), Instant.now());
        ContractDefinition ok = candidate(2, CompatibilityMode.FULL,
                List.of(rename("full_name", FieldType.STRING, false, "name")));
        logInput("rename optional->optional / FULL", bothOptional, ok);
        assertTrue(checker.check(bothOptional, ok).compatible());

        // 新字段必填 -> BACKWARD 不兼容
        ContractVersion oldOptional = new ContractVersion("orders", 1, CompatibilityMode.BACKWARD,
                List.of(field("name", FieldType.STRING, false)), Instant.now());
        ContractDefinition newRequired = candidate(2, CompatibilityMode.BACKWARD,
                List.of(rename("full_name", FieldType.STRING, true, "name")));
        logInput("rename with new required / BACKWARD", oldOptional, newRequired);
        CompatibilityReport r1 = checker.check(oldOptional, newRequired);
        log.info("[CASE] result compatible={} violations={}", r1.compatible(), r1.violations());
        assertFalse(r1.compatible());
        assertEquals("RENAME_TARGET_REQUIRED", r1.violations().get(0).reason());

        // 旧字段必填 -> FORWARD 不兼容
        ContractVersion oldReqVersion = new ContractVersion("orders", 1, CompatibilityMode.FORWARD,
                List.of(field("name", FieldType.STRING, true)), Instant.now());
        ContractDefinition newOptional = candidate(2, CompatibilityMode.FORWARD,
                List.of(rename("full_name", FieldType.STRING, false, "name")));
        logInput("rename old required / FORWARD", oldReqVersion, newOptional);
        CompatibilityReport r2 = checker.check(oldReqVersion, newOptional);
        log.info("[CASE] result compatible={} violations={}", r2.compatible(), r2.violations());
        assertFalse(r2.compatible());
        assertEquals("RENAME_SOURCE_REQUIRED", r2.violations().get(0).reason());
    }

    @Test
    void noneModeAlwaysCompatibleEvenWithBreakingChange() {
        ContractVersion prev = v1(List.of(field("id", FieldType.INTEGER, true)));
        ContractDefinition cand = candidate(2, CompatibilityMode.NONE,
                List.of(field("id", FieldType.STRING, false)));
        logInput("breaking change / NONE", prev, cand);
        CompatibilityReport report = checker.check(prev, cand);
        log.info("[CASE] result compatible={} violations={}", report.compatible(), report.violations());
        assertTrue(report.compatible());
    }

    @Test
    void conclusionsAreDeterministicAcrossRepeatedRuns() {
        ContractVersion prev = v1(List.of(field("a", FieldType.STRING, true),
                field("b", FieldType.LONG, true)));
        ContractDefinition cand = candidate(2, CompatibilityMode.FULL,
                List.of(field("a", FieldType.INTEGER, false),
                        field("c", FieldType.STRING, true)));
        CompatibilityReport first = checker.check(prev, cand);
        CompatibilityReport second = checker.check(prev, cand);
        log.info("[CASE] determinism first={} second={}", first.violations(), second.violations());
        assertEquals(first.violations(), second.violations());
        // 排序确定性：(a,TYPE_CHANGED) (a,REQUIRED_CONSTRAINT_RELAXED) (b,REMOVED_REQUIRED_FIELD) (c,ADDED_REQUIRED_FIELD)
        assertEquals(List.of("FIELD_TYPE_CHANGED", "REQUIRED_CONSTRAINT_RELAXED",
                "REMOVED_REQUIRED_FIELD", "ADDED_REQUIRED_FIELD"),
                first.violations().stream().map(v -> v.reason()).toList());
    }
}
