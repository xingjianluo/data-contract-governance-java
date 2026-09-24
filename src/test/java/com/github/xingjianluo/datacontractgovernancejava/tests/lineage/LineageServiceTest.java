package com.github.xingjianluo.datacontractgovernancejava.tests.lineage;

import com.github.xingjianluo.datacontractgovernancejava.lineage.exception.LineageRejectedException;
import com.github.xingjianluo.datacontractgovernancejava.lineage.model.LineageOutcome;
import com.github.xingjianluo.datacontractgovernancejava.lineage.model.LineageIssueCode;
import com.github.xingjianluo.datacontractgovernancejava.lineage.model.LineageRegisterResponse;
import com.github.xingjianluo.datacontractgovernancejava.lineage.model.SensitivityLevel;
import com.github.xingjianluo.datacontractgovernancejava.lineage.model.SensitivityResolution;
import com.github.xingjianluo.datacontractgovernancejava.lineage.service.LineageService;
import com.github.xingjianluo.datacontractgovernancejava.lineage.store.InMemoryLineageStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 覆盖自环、悬空引用、成环、重复登记，以及敏感级传播与多上游冲突收敛。 */
class LineageServiceTest {

    private static final Logger log = LoggerFactory.getLogger(LineageServiceTest.class);

    private InMemoryLineageStore store;
    private LineageService service;

    @BeforeEach
    void setUp() {
        store = new InMemoryLineageStore();
        service = new LineageService(store);
    }

    private String node(String contract, int version, String field) {
        return LineageService.nodeId(contract, version, field);
    }

    private void regNode(String id, SensitivityLevel level) {
        String[] parts = id.split(":");
        service.registerNode(parts[0], Integer.parseInt(parts[1]), parts[2], level);
    }

    @Test
    void danglingReferenceIsRejectedAndNoEdgePersisted() {
        regNode("a:1:x", SensitivityLevel.PUBLIC);
        log.info("[CASE] edge input source=a:1:x target=b:1:y (target missing)");
        LineageRejectedException ex = assertThrows(LineageRejectedException.class,
                () -> service.registerEdge(node("a", 1, "x"), node("b", 1, "y"), null));
        log.warn("[CASE] rejected code={} message={}", ex.getCode(), ex.getMessage());
        assertEquals(LineageIssueCode.DANGLING_REFERENCE, ex.getCode());
        assertTrue(store.allEdges().isEmpty());
    }

    @Test
    void selfLoopIsRejected() {
        regNode("a:1:x", SensitivityLevel.PUBLIC);
        LineageRejectedException ex = assertThrows(LineageRejectedException.class,
                () -> service.registerEdge(node("a", 1, "x"), node("a", 1, "x"), null));
        assertEquals(LineageIssueCode.SELF_LOOP, ex.getCode());
        assertTrue(store.allEdges().isEmpty());
    }

    @Test
    void cycleIsRejectedAndExistingEdgesRemainUntouched() {
        // x -> y -> z 已存在，再登记 z -> x 必然成环
        regNode("a:1:x", SensitivityLevel.PUBLIC);
        regNode("a:1:y", SensitivityLevel.PUBLIC);
        regNode("a:1:z", SensitivityLevel.PUBLIC);
        assertEquals(LineageOutcome.REGISTERED,
                service.registerEdge(node("a", 1, "x"), node("a", 1, "y"), null).outcome());
        assertEquals(LineageOutcome.REGISTERED,
                service.registerEdge(node("a", 1, "y"), node("a", 1, "z"), null).outcome());

        LineageRejectedException ex = assertThrows(LineageRejectedException.class,
                () -> service.registerEdge(node("a", 1, "z"), node("a", 1, "x"), null));
        log.warn("[CASE] cycle rejected code={} message={}", ex.getCode(), ex.getMessage());
        assertEquals(LineageIssueCode.CYCLE_DETECTED, ex.getCode());
        // 被拒绝的边不得落盘，原有两条边不变
        assertEquals(2, store.allEdges().size());
        assertTrue(store.findEdge(node("a", 1, "z"), node("a", 1, "x")).isEmpty());
    }

    @Test
    void duplicateEdgeIsIdempotentAndDoesNotDuplicate() {
        regNode("a:1:x", SensitivityLevel.PUBLIC);
        regNode("a:1:y", SensitivityLevel.PUBLIC);
        LineageRegisterResponse r1 = service.registerEdge(node("a", 1, "x"), node("a", 1, "y"), "copy");
        LineageRegisterResponse r2 = service.registerEdge(node("a", 1, "x"), node("a", 1, "y"), "copy");
        LineageRegisterResponse r3 = service.registerEdge(node("a", 1, "x"), node("a", 1, "y"), "copy");
        assertEquals(LineageOutcome.REGISTERED, r1.outcome());
        assertEquals(LineageOutcome.DUPLICATE_REPLAY, r2.outcome());
        assertEquals(LineageOutcome.DUPLICATE_REPLAY, r3.outcome());
        assertEquals(1, store.allEdges().size());
    }

    @Test
    void duplicateEdgeWithDifferentTransformIsFlaggedButOriginalKept() {
        regNode("a:1:x", SensitivityLevel.PUBLIC);
        regNode("a:1:y", SensitivityLevel.PUBLIC);
        service.registerEdge(node("a", 1, "x"), node("a", 1, "y"), "copy");
        LineageRegisterResponse replay = service.registerEdge(
                node("a", 1, "x"), node("a", 1, "y"), "hash");
        assertEquals(LineageOutcome.DUPLICATE_REPLAY, replay.outcome());
        assertEquals(LineageIssueCode.DUPLICATE_REGISTRATION, replay.issueCode());
        assertEquals("copy", store.allEdges().get(0).transform(), "保留首次登记的 transform");
        assertEquals(1, store.allEdges().size());
    }

    @Test
    void sensitivityPropagatesDownstreamToHighestLevel() {
        // secret 源 -> mid -> sink；另一个 public 源也流入 sink
        regNode("src:1:secret_f", SensitivityLevel.SECRET);
        regNode("src:1:public_f", SensitivityLevel.PUBLIC);
        regNode("dwh:1:mid", SensitivityLevel.UNKNOWN);
        regNode("dwh:1:sink", SensitivityLevel.UNKNOWN);
        service.registerEdge(node("src", 1, "secret_f"), node("dwh", 1, "mid"), null);
        service.registerEdge(node("dwh", 1, "mid"), node("dwh", 1, "sink"), null);
        service.registerEdge(node("src", 1, "public_f"), node("dwh", 1, "sink"), null);

        SensitivityResolution mid = service.resolveSensitivity(node("dwh", 1, "mid"));
        SensitivityResolution sink = service.resolveSensitivity(node("dwh", 1, "sink"));
        log.info("[CASE] mid effective={} contributions={}", mid.effectiveLevel(), mid.contributions());
        log.info("[CASE] sink effective={} conflict={} contributions={}",
                sink.effectiveLevel(), sink.conflict(), sink.contributions());

        assertEquals(SensitivityLevel.SECRET, mid.effectiveLevel());
        assertEquals(SensitivityLevel.SECRET, sink.effectiveLevel(), "敏感级沿血缘向下游传播取最高");
    }

    @Test
    void conflictingUpstreamsConvergeToHighestAndKeepEvidence() {
        // 两个互不相干的 CONFIDENTIAL 根来源汇入同一节点：取 CONFIDENTIAL 且标记冲突、保留双方路径
        regNode("hr:1:salary", SensitivityLevel.CONFIDENTIAL);
        regNode("crm:1:phone", SensitivityLevel.CONFIDENTIAL);
        regNode("ads:1:profile", SensitivityLevel.PUBLIC);
        service.registerEdge(node("hr", 1, "salary"), node("ads", 1, "profile"), null);
        service.registerEdge(node("crm", 1, "phone"), node("ads", 1, "profile"), null);

        SensitivityResolution res = service.resolveSensitivity(node("ads", 1, "profile"));
        log.info("[CASE] conflict resolution effective={} conflict={} contributions={}",
                res.effectiveLevel(), res.conflict(), res.contributions());
        assertEquals(SensitivityLevel.CONFIDENTIAL, res.effectiveLevel());
        assertTrue(res.conflict(), "多个互异根来源贡献同级最高级时应标记冲突");
        List<String> roots = res.contributions().stream()
                .map(c -> c.fromNodeId()).distinct().sorted().toList();
        assertTrue(roots.contains(node("hr", 1, "salary")));
        assertTrue(roots.contains(node("crm", 1, "phone")));
        // 每个贡献都带传播路径，作为可解释依据
        assertTrue(res.contributions().stream().allMatch(c -> !c.path().isEmpty()));
    }

    @Test
    void singleHighestSourceDoesNotFlagConflict() {
        regNode("a:1:high", SensitivityLevel.SECRET);
        regNode("a:1:low", SensitivityLevel.PUBLIC);
        regNode("a:1:join", SensitivityLevel.UNKNOWN);
        service.registerEdge(node("a", 1, "high"), node("a", 1, "join"), null);
        service.registerEdge(node("a", 1, "low"), node("a", 1, "join"), null);
        SensitivityResolution res = service.resolveSensitivity(node("a", 1, "join"));
        log.info("[CASE] no-conflict effective={} conflict={}", res.effectiveLevel(), res.conflict());
        assertEquals(SensitivityLevel.SECRET, res.effectiveLevel());
        assertFalse(res.conflict(), "最高级唯一时不应标记冲突");
    }

    @Test
    void resolutionIsReproducibleAcrossRepeatedCalls() {
        regNode("hr:1:salary", SensitivityLevel.CONFIDENTIAL);
        regNode("crm:1:phone", SensitivityLevel.CONFIDENTIAL);
        regNode("ads:1:profile", SensitivityLevel.PUBLIC);
        service.registerEdge(node("hr", 1, "salary"), node("ads", 1, "profile"), null);
        service.registerEdge(node("crm", 1, "phone"), node("ads", 1, "profile"), null);
        SensitivityResolution r1 = service.resolveSensitivity(node("ads", 1, "profile"));
        SensitivityResolution r2 = service.resolveSensitivity(node("ads", 1, "profile"));
        assertEquals(r1, r2, "同一存储快照下重复调用结论必须可复现");
    }
}
