package com.github.xingjianluo.datacontractgovernancejava.tests;

import com.github.xingjianluo.datacontractgovernancejava.compat.ChangeType;
import com.github.xingjianluo.datacontractgovernancejava.compat.CompatibilityChecker;
import com.github.xingjianluo.datacontractgovernancejava.error.GovernanceException;
import com.github.xingjianluo.datacontractgovernancejava.error.RejectReason;
import com.github.xingjianluo.datacontractgovernancejava.evolution.PrecheckResult;
import com.github.xingjianluo.datacontractgovernancejava.evolution.PrecheckStatus;
import com.github.xingjianluo.datacontractgovernancejava.model.CompatibilityLevel;
import com.github.xingjianluo.datacontractgovernancejava.model.ContractField;
import com.github.xingjianluo.datacontractgovernancejava.model.DataContract;
import com.github.xingjianluo.datacontractgovernancejava.model.SensitivityLevel;
import com.github.xingjianluo.datacontractgovernancejava.registry.ContractRegistry;
import com.github.xingjianluo.datacontractgovernancejava.registry.RegistrationResult;
import com.github.xingjianluo.datacontractgovernancejava.registry.RegistrationStatus;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 版本演进管理：历史版本重放收敛、预检只读且与正式注册结论一致、
 * 参照版本/兼容级别切换、并发重放不产生重复版本。
 */
class EvolutionPrecheckTest {

    private static final Logger log = LoggerFactory.getLogger(EvolutionPrecheckTest.class);

    private final ContractRegistry registry = new ContractRegistry(new CompatibilityChecker());

    private static ContractField f(String name, String type, boolean required) {
        return new ContractField(name, type, required, SensitivityLevel.PUBLIC);
    }

    private static DataContract contract(String name, CompatibilityLevel level, ContractField... fields) {
        return new DataContract(name, level, List.of(fields));
    }

    @Test
    void oldVersionReplayConvergesToExistingVersionInsteadOfCreatingNew() {
        // v1 -> v2 -> v3（BACKWARD 下连续新增可选字段）
        DataContract v1 = contract("orders", CompatibilityLevel.BACKWARD, f("id", "long", true));
        DataContract v2 = contract("orders", CompatibilityLevel.BACKWARD,
                f("id", "long", true), f("a", "string", false));
        DataContract v3 = contract("orders", CompatibilityLevel.BACKWARD,
                f("id", "long", true), f("a", "string", false), f("b", "string", false));
        assertEquals(1, registry.register(v1).version());
        assertEquals(2, registry.register(v2).version());
        assertEquals(3, registry.register(v3).version());

        // CI 重跑补投旧版本 v1 / v2：即使它们与“当前最新版本”不同，也必须收敛到已存在版本
        RegistrationResult replayV1 = registry.register(v1);
        RegistrationResult replayV2 = registry.register(v2);
        log.info("[历史重放收敛] 补投 v1={}, 补投 v2={}, 历史版本数={}",
                replayV1, replayV2, registry.versions("orders").size());
        assertEquals(RegistrationStatus.IDEMPOTENT_REPLAY, replayV1.status());
        assertEquals(1, replayV1.version());
        assertEquals(RegistrationStatus.IDEMPOTENT_REPLAY, replayV2.status());
        assertEquals(2, replayV2.version());
        assertEquals(3, registry.versions("orders").size(), "历史重放不得额外生成新版本");

        // 内容确实与所有历史版本不同：按新版本处理（可选字段新增，BACKWARD 允许）
        DataContract v4 = contract("orders", CompatibilityLevel.BACKWARD,
                f("id", "long", true), f("a", "string", false), f("b", "string", false),
                f("c", "string", false));
        RegistrationResult r4 = registry.register(v4);
        log.info("[历史重放-新内容] 全新内容注册={}", r4);
        assertEquals(RegistrationStatus.REGISTERED, r4.status());
        assertEquals(4, r4.version());
    }

    @Test
    void replayOfOldVersionBypassesCompatibilityAgainstLatest() {
        // BACKWARD：必填放宽（必填->可选）合法，故历史中存在 v1(id必填) -> v2(id可选)
        DataContract v1 = contract("users", CompatibilityLevel.BACKWARD, f("id", "long", true));
        DataContract v2 = contract("users", CompatibilityLevel.BACKWARD, f("id", "long", false));
        registry.register(v1);
        registry.register(v2);

        // 若按最新版本判定，id 可选->必填是收紧，BACKWARD 必须拒绝；
        // 但内容与 v1 完全一致，必须按幂等重放收敛，不能拒绝
        PrecheckResult pre = registry.precheck(v1, null, null);
        log.info("[重放优先于判定] 预检补投 v1 内容: status={}, version={}, idempotent={}, 依据={}",
                pre.status(), pre.version(), pre.idempotent(), pre.violations());
        assertEquals(PrecheckStatus.IDEMPOTENT_REPLAY, pre.status());
        assertEquals(1, pre.version());
        assertTrue(pre.idempotent());

        RegistrationResult replay = registry.register(v1);
        assertEquals(RegistrationStatus.IDEMPOTENT_REPLAY, replay.status());
        assertEquals(1, replay.version());
        assertEquals(2, registry.versions("users").size());
    }

    @Test
    void precheckIsReadOnlyAndPredictsAcceptedVersion() {
        registry.register(contract("orders", CompatibilityLevel.BACKWARD, f("id", "long", true)));
        DataContract candidate = contract("orders", CompatibilityLevel.BACKWARD,
                f("id", "long", true), f("note", "string", false));

        PrecheckResult pre = registry.precheck(candidate, null, null);
        log.info("[预检只读-接受] 输入字段={}, 预检={}, 预检后历史版本数={}",
                candidate.getFields().stream().map(ContractField::fingerprint).toList(),
                pre, registry.versions("orders").size());
        assertEquals(PrecheckStatus.REGISTERED, pre.status());
        assertEquals(2, pre.version());
        assertEquals(1, pre.referenceVersion());
        assertEquals(CompatibilityLevel.BACKWARD, pre.compatibilityLevel());
        assertEquals(1, registry.versions("orders").size(), "预检不得写入任何版本");

        RegistrationResult registered = registry.register(candidate);
        assertEquals(RegistrationStatus.REGISTERED, registered.status());
        assertEquals(pre.version(), registered.version(), "预检预测版本必须与正式注册一致");
    }

    @Test
    void precheckAndRegistrationRejectWithExactlySameBasis() {
        registry.register(contract("orders", CompatibilityLevel.BACKWARD, f("id", "long", true)));
        // 新增必填字段：BACKWARD 必须拒绝，依据种类为 FIELD_ADDED
        DataContract bad = contract("orders", CompatibilityLevel.BACKWARD,
                f("id", "long", true), f("code", "string", true));

        PrecheckResult pre = registry.precheck(bad, null, null);
        log.info("[预检/注册一致-拒绝] 预检 status={}, 依据={}", pre.status(), pre.violations());
        assertEquals(PrecheckStatus.REJECTED, pre.status());
        assertNull(pre.version());
        assertTrue(pre.violations().stream().anyMatch(v -> v.changeType() == ChangeType.FIELD_ADDED));
        assertEquals(1, registry.versions("orders").size(), "被预检拒绝的内容不得写入");

        GovernanceException ex = assertThrows(GovernanceException.class, () -> registry.register(bad));
        log.info("[预检/注册一致-正式拒绝] reason={}, 依据={}", ex.getReason(), ex.getViolations());
        assertEquals(RejectReason.INCOMPATIBLE_SCHEMA, ex.getReason());
        assertEquals(pre.violations(), ex.getViolations(), "预检与正式注册的拒绝依据必须逐条一致");
        assertEquals(1, registry.versions("orders").size(), "正式拒绝也不得留下部分写入");
    }

    @Test
    void switchingCompatibilityLevelFlipsConclusionAndBothPathsAgree() {
        // 同一结构变化：id 可选 -> 必填（必填收紧）
        registry.register(contract("evt", CompatibilityLevel.BACKWARD, f("id", "long", false)));

        DataContract backwardCandidate =
                contract("evt", CompatibilityLevel.BACKWARD, f("id", "long", true));
        PrecheckResult preBackward = registry.precheck(backwardCandidate, null, null);
        log.info("[级别语义-收紧] BACKWARD 预检={}, 依据={}", preBackward.status(), preBackward.violations());
        assertEquals(PrecheckStatus.REJECTED, preBackward.status());
        assertTrue(preBackward.violations().stream()
                .anyMatch(v -> v.changeType() == ChangeType.REQUIRED_TIGHTENED));
        assertThrows(GovernanceException.class, () -> registry.register(backwardCandidate));

        // 预检临时切到 FORWARD：旧读者读新数据，收紧不破坏 FORWARD 方向 -> 接受
        PrecheckResult preForwardOverride = registry.precheck(backwardCandidate, null, CompatibilityLevel.FORWARD);
        log.info("[级别语义-收紧] FORWARD 覆盖预检={}", preForwardOverride.status());
        assertEquals(PrecheckStatus.REGISTERED, preForwardOverride.status());
        assertEquals(CompatibilityLevel.FORWARD, preForwardOverride.compatibilityLevel());

        // 以 FORWARD 级别正式声明同样内容，预检与正式注册一致接受
        DataContract forwardCandidate =
                contract("evt", CompatibilityLevel.FORWARD, f("id", "long", true));
        assertEquals(PrecheckStatus.REGISTERED, registry.precheck(forwardCandidate, null, null).status());
        assertEquals(2, registry.register(forwardCandidate).version());

        // 反向变化 必填->可选：FORWARD/FULL 必须拦，BACKWARD 合法
        DataContract relax = contract("evt2", CompatibilityLevel.FORWARD, f("id", "long", true));
        registry.register(relax);
        DataContract relaxed = contract("evt2", CompatibilityLevel.FORWARD, f("id", "long", false));
        PrecheckResult preRelax = registry.precheck(relaxed, null, null);
        log.info("[级别语义-放宽] FORWARD 预检={}, 依据={}", preRelax.status(), preRelax.violations());
        assertEquals(PrecheckStatus.REJECTED, preRelax.status());
        assertTrue(preRelax.violations().stream()
                .anyMatch(v -> v.changeType() == ChangeType.REQUIRED_RELAXED));
        assertThrows(GovernanceException.class, () -> registry.register(relaxed));

        PrecheckResult preRelaxBackward = registry.precheck(relaxed, null, CompatibilityLevel.BACKWARD);
        assertEquals(PrecheckStatus.REGISTERED, preRelaxBackward.status(),
                "必填放宽在 BACKWARD 下是合法演进");
    }

    @Test
    void precheckAgainstExplicitReferenceVersionFollowsRealSemantics() {
        // v1: id,code 必填；v2: code 放宽为可选（BACKWARD 合法）
        registry.register(contract("p", CompatibilityLevel.BACKWARD,
                f("id", "long", true), f("code", "string", true)));
        registry.register(contract("p", CompatibilityLevel.BACKWARD,
                f("id", "long", true), f("code", "string", false)));

        // 候选：code 重新必填 + 新增可选 tag
        DataContract candidate = contract("p", CompatibilityLevel.BACKWARD,
                f("id", "long", true), f("code", "string", true), f("tag", "string", false));

        // 参照最新版本 v2：可选->必填收紧，必须拒绝
        PrecheckResult vsLatest = registry.precheck(candidate, null, null);
        log.info("[指定参照] 相对最新 v2 预检={}, 依据={}", vsLatest.status(), vsLatest.violations());
        assertEquals(PrecheckStatus.REJECTED, vsLatest.status());
        assertEquals(2, vsLatest.referenceVersion());

        // 参照旧版本 v1：code 本就必填，仅新增可选 tag，合法接受
        PrecheckResult vsV1 = registry.precheck(candidate, 1, null);
        log.info("[指定参照] 相对 v1 预检={}, 预计版本={}", vsV1.status(), vsV1.version());
        assertEquals(PrecheckStatus.REGISTERED, vsV1.status());
        assertEquals(1, vsV1.referenceVersion());
        assertEquals(3, vsV1.version());
        assertEquals(2, registry.versions("p").size(), "预检只读");

        // 正式按 v1 参照注册，结论与预检一致
        RegistrationResult registered = registry.register(candidate, 1);
        assertEquals(RegistrationStatus.REGISTERED, registered.status());
        assertEquals(vsV1.version(), registered.version());

        // 参照版本不存在：预检与正式注册都抛 VERSION_CONFLICT。
        // 用一份全新内容（避免先命中历史重放——重放优先于参照解析）
        DataContract unknownRefCandidate = contract("p", CompatibilityLevel.BACKWARD,
                f("id", "long", true), f("code", "string", false), f("never_seen", "string", false));
        GovernanceException pre404 = assertThrows(GovernanceException.class,
                () -> registry.precheck(unknownRefCandidate, 99, null));
        GovernanceException reg404 = assertThrows(GovernanceException.class,
                () -> registry.register(unknownRefCandidate, 99));
        log.info("[指定参照] 不存在版本: 预检 reason={}, 注册 reason={}", pre404.getReason(), reg404.getReason());
        assertEquals(RejectReason.VERSION_CONFLICT, pre404.getReason());
        assertEquals(RejectReason.VERSION_CONFLICT, reg404.getReason());
    }

    @Test
    void concurrentReplayOfDifferentOldVersionsConvergesWithoutDuplicates() throws InterruptedException {
        DataContract v1 = contract("log", CompatibilityLevel.BACKWARD, f("id", "long", true));
        DataContract v2 = contract("log", CompatibilityLevel.BACKWARD,
                f("id", "long", true), f("a", "string", false));
        DataContract v3 = contract("log", CompatibilityLevel.BACKWARD,
                f("id", "long", true), f("a", "string", false), f("b", "string", false));
        registry.register(v1);
        registry.register(v2);
        registry.register(v3);

        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch go = new CountDownLatch(1);
        Set<Integer> replayVersions = ConcurrentHashMap.newKeySet();
        Set<PrecheckStatus> precheckStatuses = ConcurrentHashMap.newKeySet();
        for (int i = 0; i < threads; i++) {
            DataContract payload = (i % 2 == 0) ? v1 : v3;
            pool.submit(() -> {
                try {
                    go.await();
                    PrecheckResult pre = registry.precheck(payload, null, null);
                    precheckStatuses.add(pre.status());
                    RegistrationResult r = registry.register(payload);
                    replayVersions.add(r.version());
                    assertEquals(RegistrationStatus.IDEMPOTENT_REPLAY, r.status());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        go.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        log.info("[并发历史重放] {} 个线程混投 v1/v3, 收敛版本集合={}, 预检状态集合={}, 历史版本数={}",
                threads, replayVersions, precheckStatuses, registry.versions("log").size());
        assertEquals(Set.of(1, 3), replayVersions, "并发重放必须收敛到各自已存在的版本号");
        assertEquals(Set.of(PrecheckStatus.IDEMPOTENT_REPLAY), precheckStatuses);
        assertEquals(3, registry.versions("log").size(), "并发重放不得产生重复版本或半写入");
    }
}
