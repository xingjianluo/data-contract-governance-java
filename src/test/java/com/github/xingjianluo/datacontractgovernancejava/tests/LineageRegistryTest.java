package com.github.xingjianluo.datacontractgovernancejava.tests;

import com.github.xingjianluo.datacontractgovernancejava.compat.CompatibilityChecker;
import com.github.xingjianluo.datacontractgovernancejava.error.GovernanceException;
import com.github.xingjianluo.datacontractgovernancejava.error.RejectReason;
import com.github.xingjianluo.datacontractgovernancejava.lineage.FieldRef;
import com.github.xingjianluo.datacontractgovernancejava.lineage.LineageEdge;
import com.github.xingjianluo.datacontractgovernancejava.lineage.LineageRegistry;
import com.github.xingjianluo.datacontractgovernancejava.lineage.SensitivityResolution;
import com.github.xingjianluo.datacontractgovernancejava.model.CompatibilityLevel;
import com.github.xingjianluo.datacontractgovernancejava.model.ContractField;
import com.github.xingjianluo.datacontractgovernancejava.model.DataContract;
import com.github.xingjianluo.datacontractgovernancejava.model.SensitivityLevel;
import com.github.xingjianluo.datacontractgovernancejava.registry.ContractRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LineageRegistryTest {

    private static final Logger log = LoggerFactory.getLogger(LineageRegistryTest.class);

    private ContractRegistry contracts;
    private LineageRegistry lineage;

    @BeforeEach
    void setUp() {
        contracts = new ContractRegistry(new CompatibilityChecker());
        lineage = new LineageRegistry(contracts);
    }

    private void registerContract(String name, ContractField... fields) {
        contracts.register(new DataContract(name, CompatibilityLevel.BACKWARD, List.of(fields)));
    }

    private static ContractField f(String name, SensitivityLevel level) {
        return new ContractField(name, "string", false, level);
    }

    @Test
    void danglingReferenceRejected() {
        registerContract("a", f("x", SensitivityLevel.PUBLIC));
        GovernanceException ex = assertThrows(GovernanceException.class,
                () -> lineage.registerEdge(new LineageEdge(FieldRef.of("a", "x"), FieldRef.of("ghost", "y"))));
        log.info("[悬空引用] 输入 a.x -> ghost.y, 拒绝原因={}, 已登记边={}", ex.getReason(), lineage.edges());
        assertEquals(RejectReason.UNKNOWN_CONTRACT, ex.getReason());
        assertTrue(lineage.edges().isEmpty(), "被拒绝的登记不得留下部分写入");

        GovernanceException ex2 = assertThrows(GovernanceException.class,
                () -> lineage.registerEdge(new LineageEdge(FieldRef.of("a", "x"), FieldRef.of("a", "nope"))));
        log.info("[悬空字段] 输入 a.x -> a.nope, 拒绝原因={}", ex2.getReason());
        assertEquals(RejectReason.LINEAGE_DANGLING_REFERENCE, ex2.getReason());
    }

    @Test
    void cyclicLineageRejected() {
        registerContract("a", f("x", SensitivityLevel.PUBLIC));
        registerContract("b", f("y", SensitivityLevel.PUBLIC));
        registerContract("c", f("z", SensitivityLevel.PUBLIC));
        lineage.registerEdge(new LineageEdge(FieldRef.of("a", "x"), FieldRef.of("b", "y")));
        lineage.registerEdge(new LineageEdge(FieldRef.of("b", "y"), FieldRef.of("c", "z")));

        GovernanceException ex = assertThrows(GovernanceException.class,
                () -> lineage.registerEdge(new LineageEdge(FieldRef.of("c", "z"), FieldRef.of("a", "x"))));
        log.info("[环状依赖] 已有 a.x->b.y->c.z, 输入 c.z->a.x, 拒绝原因={}, 已登记边数={}",
                ex.getReason(), lineage.edges().size());
        assertEquals(RejectReason.LINEAGE_CYCLE, ex.getReason());
        assertEquals(2, lineage.edges().size(), "成环边不得写入");

        GovernanceException selfLoop = assertThrows(GovernanceException.class,
                () -> lineage.registerEdge(new LineageEdge(FieldRef.of("a", "x"), FieldRef.of("a", "x"))));
        assertEquals(RejectReason.LINEAGE_CYCLE, selfLoop.getReason());
    }

    @Test
    void duplicateEdgeIsIdempotent() {
        registerContract("a", f("x", SensitivityLevel.PUBLIC));
        registerContract("b", f("y", SensitivityLevel.PUBLIC));
        LineageEdge edge = new LineageEdge(FieldRef.of("a", "x"), FieldRef.of("b", "y"));
        boolean first = lineage.registerEdge(edge);
        boolean replay = lineage.registerEdge(edge);
        log.info("[重复登记] 首次={}, 重复={}, 已登记边数={}", first, replay, lineage.edges().size());
        assertTrue(first);
        assertFalse(replay, "重复登记应幂等返回 false");
        assertEquals(1, lineage.edges().size(), "重复登记不得产生重复边");
    }

    @Test
    void sensitivityPropagatesTransitively() {
        registerContract("src", f("ssn", SensitivityLevel.SECRET));
        registerContract("mid", f("ssn_copy", SensitivityLevel.PUBLIC));
        registerContract("dst", f("report_col", SensitivityLevel.PUBLIC));
        lineage.registerEdge(new LineageEdge(FieldRef.of("src", "ssn"), FieldRef.of("mid", "ssn_copy")));
        lineage.registerEdge(new LineageEdge(FieldRef.of("mid", "ssn_copy"), FieldRef.of("dst", "report_col")));

        SensitivityResolution res = lineage.resolveSensitivity(FieldRef.of("dst", "report_col"));
        log.info("[敏感传播] src.ssn(SECRET) -> mid.ssn_copy(PUBLIC) -> dst.report_col(PUBLIC), "
                + "结论: effective={}, conflict={}, 上游贡献={}",
                res.effectiveLevel(), res.conflict(), res.upstreamContributions());
        assertEquals(SensitivityLevel.SECRET, res.effectiveLevel(), "敏感级别应沿血缘传递向下游传播");
        assertFalse(res.conflict(), "单上游链不应标记冲突");
    }

    @Test
    void conflictingUpstreamsConvergeToHighestWithEvidence() {
        registerContract("up1", f("a", SensitivityLevel.PUBLIC));
        registerContract("up2", f("b", SensitivityLevel.CONFIDENTIAL));
        registerContract("up3", f("c", SensitivityLevel.INTERNAL));
        registerContract("down", f("merged", SensitivityLevel.PUBLIC));
        lineage.registerEdge(new LineageEdge(FieldRef.of("up1", "a"), FieldRef.of("down", "merged")));
        lineage.registerEdge(new LineageEdge(FieldRef.of("up2", "b"), FieldRef.of("down", "merged")));
        lineage.registerEdge(new LineageEdge(FieldRef.of("up3", "c"), FieldRef.of("down", "merged")));

        SensitivityResolution res = lineage.resolveSensitivity(FieldRef.of("down", "merged"));
        log.info("[冲突收敛] 上游级别 PUBLIC/CONFIDENTIAL/INTERNAL -> down.merged, "
                + "结论: effective={}, conflict={}, 冲突依据(各上游贡献)={}, 低于收敛级别的来源={}",
                res.effectiveLevel(), res.conflict(), res.upstreamContributions(), res.conflictSources());
        assertEquals(SensitivityLevel.CONFIDENTIAL, res.effectiveLevel(), "多上游冲突应收敛到最高级别");
        assertTrue(res.conflict(), "上游级别不一致应标记冲突");
        assertEquals(3, res.upstreamContributions().size(), "应保留全部上游贡献作为冲突依据");
        assertEquals(2, res.conflictSources().size());

        // 可复现：重复调用结论一致
        SensitivityResolution again = lineage.resolveSensitivity(FieldRef.of("down", "merged"));
        assertEquals(res.effectiveLevel(), again.effectiveLevel());
        assertEquals(res.upstreamContributions(), again.upstreamContributions());
    }
}
