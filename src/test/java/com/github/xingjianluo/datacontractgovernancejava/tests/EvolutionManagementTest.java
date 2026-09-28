package com.github.xingjianluo.datacontractgovernancejava.tests;

import com.github.xingjianluo.datacontractgovernancejava.compat.ChangeType;
import com.github.xingjianluo.datacontractgovernancejava.compat.CompatibilityChecker;
import com.github.xingjianluo.datacontractgovernancejava.error.GovernanceException;
import com.github.xingjianluo.datacontractgovernancejava.error.RejectReason;
import com.github.xingjianluo.datacontractgovernancejava.model.CompatibilityLevel;
import com.github.xingjianluo.datacontractgovernancejava.model.ContractField;
import com.github.xingjianluo.datacontractgovernancejava.model.DataContract;
import com.github.xingjianluo.datacontractgovernancejava.model.SensitivityLevel;
import com.github.xingjianluo.datacontractgovernancejava.registry.ContractRegistry;
import com.github.xingjianluo.datacontractgovernancejava.registry.PrecheckResult;
import com.github.xingjianluo.datacontractgovernancejava.registry.PrecheckStatus;
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
 * 版本演进管理：历史版本重放收敛、演进预检与正式注册结论一致性、并发重放。
 */
class EvolutionManagementTest {

    private static final Logger log = LoggerFactory.getLogger(EvolutionManagementTest.class);

    private final ContractRegistry registry = new ContractRegistry(new CompatibilityChecker());

    private static ContractField f(String name, String type, boolean required) {
        return new ContractField(name, type, required, SensitivityLevel.PUBLIC);
    }

    private static DataContract contract(String name, CompatibilityLevel level, ContractField... fields) {
        return new DataContract(name, level, List.of(fields));
    }

    @Test
    void replayOfHistoricalVersionConvergesEvenWhenNotLatest() {
        // v1 -> v2 两个已注册版本（BACKWARD 下新增可选字段合法）
        DataContract v1 = contract("orders", CompatibilityLevel.BACKWARD, f("id", "long", true));
        DataContract v2 = contract("orders", CompatibilityLevel.BACKWARD,
                f("id", "long", true), f("note", "string", false));
        assertEquals(1, registry.register(v1).version());
        assertEquals(2, registry.register(v2).version());

        // CI 重跑把旧版本 v1 原样补投回来：不得因最新是 v2 而生成 v3
        RegistrationResult replay = registry.register(
                contract("orders", CompatibilityLevel.BACKWARD, f("id", "long", true)));
        log.info("[历史重放] 最新版本=2, 补投内容=v1, 结论={}, 历史版本号={}",
                replay, registry.versions("orders").stream().map(v -> v.version()).toList());
        assertEquals(RegistrationStatus.IDEMPOTENT_REPLAY, replay.status());
        assertEquals(1, replay.version(), "旧版本重放必须收敛到它原本的版本号");
        assertEquals(2, registry.versions("orders").size(), "历史版本重放不得生成新版本");

        // 对历史版本做预检，结论同样是幂等重放
        PrecheckResult pre = registry.precheck(
                contract("orders", CompatibilityLevel.BACKWARD, f("id", "long", true)), null);
        assertEquals(PrecheckStatus.IDEMPOTENT_REPLAY, pre.status());
        assertEquals(1, pre.resolvedVersion());

        // 与所有历史版本都不同的内容仍按新版本处理
        DataContract v3 = contract("orders", CompatibilityLevel.BACKWARD,
                f("id", "long", true), f("note", "string", false), f("tag", "string", false));
        RegistrationResult r3 = registry.register(v3);
        log.info("[非重放新内容] 结论={}, 历史版本数={}", r3, registry.versions("orders").size());
        assertEquals(RegistrationStatus.REGISTERED, r3.status());
        assertEquals(3, r3.version());
    }

    @Test
    void precheckIsReadOnlyAndMatchesRegisterDecision() {
        registry.register(contract("orders", CompatibilityLevel.BACKWARD, f("id", "long", true)));

        // 破坏性候选：BACKWARD 下新增必填字段
        DataContract bad = contract("orders", CompatibilityLevel.BACKWARD,
                f("id", "long", true), f("code", "string", true));
        int versionsBefore = registry.versions("orders").size();
        PrecheckResult rejected = registry.precheck(bad, null);
        log.info("[预检拒绝] 输入 fingerprint={}, 预检结论={}, 分组依据={}",
                bad.fingerprint(), rejected.status(), rejected.violationsByType());

        assertEquals(PrecheckStatus.REJECTED, rejected.status());
        assertNull(resolvedVersionSafe(rejected), "拒绝结论不得携带版本号");
        assertTrue(rejected.violations().stream().anyMatch(v -> v.changeType() == ChangeType.FIELD_ADDED));
        assertTrue(rejected.violationsByType().containsKey(ChangeType.FIELD_ADDED.name()),
                "拒绝依据必须能按变更种类区分");
        assertEquals(versionsBefore, registry.versions("orders").size(), "预检不得改动任何已注册数据");

        // 同样参数正式注册：必须拒绝，且拒绝依据与预检逐条一致
        GovernanceException ex = assertThrows(GovernanceException.class, () -> registry.register(bad));
        assertEquals(RejectReason.INCOMPATIBLE_SCHEMA, ex.getReason());
        assertEquals(rejected.violations(), ex.getReport().violations(),
                "预检与正式注册的拒绝依据必须完全一致");
        assertEquals(versionsBefore, registry.versions("orders").size(), "正式拒绝不得留下半写入状态");

        // 合法候选：预检可注册 -> 正式注册得到预检预告的版本号
        DataContract good = contract("orders", CompatibilityLevel.BACKWARD,
                f("id", "long", true), f("code", "string", false));
        PrecheckResult ok = registry.precheck(good, null);
        log.info("[预检通过] 预检结论={}, 预告版本号={}", ok.status(), ok.resolvedVersion());
        assertEquals(PrecheckStatus.COMPATIBLE, ok.status());
        RegistrationResult registered = registry.register(good);
        assertEquals(RegistrationStatus.REGISTERED, registered.status());
        assertEquals(ok.resolvedVersion(), registered.version(), "正式注册版本号必须与预检预告一致");
    }

    @Test
    void precheckAgainstExplicitBaseVersionFollowsThatReference() {
        // FORWARD 级别：v1={id必填}，v2 新增必填字段 b（FORWARD 允许新增必填）
        registry.register(contract("orders", CompatibilityLevel.FORWARD, f("id", "long", true)));
        registry.register(contract("orders", CompatibilityLevel.FORWARD,
                f("id", "long", true), f("b", "string", true)));

        // 候选与所有历史版本都不同：{id必填, b可选}
        DataContract candidate = contract("orders", CompatibilityLevel.FORWARD,
                f("id", "long", true), f("b", "string", false));

        // 以最新 v2 为参照：必填->可选放宽，FORWARD 必须拦住
        PrecheckResult againstLatest = registry.precheck(candidate, null);
        log.info("[指定参照] 以最新版本v2为参照: status={}, 依据={}",
                againstLatest.status(), againstLatest.violationsByType());
        assertEquals(PrecheckStatus.REJECTED, againstLatest.status());
        assertEquals(2, againstLatest.baseVersion());
        assertTrue(againstLatest.violationsByType().containsKey(ChangeType.REQUIRED_RELAXED.name()));

        // 显式以 v1 为参照：只是新增可选字段 b，FORWARD 合法 -> 通过，预告版本 3
        PrecheckResult againstV1 = registry.precheck(candidate, 1);
        log.info("[指定参照] 以v1为参照: status={}, resolvedVersion={}",
                againstV1.status(), againstV1.resolvedVersion());
        assertEquals(PrecheckStatus.COMPATIBLE, againstV1.status());
        assertEquals(1, againstV1.baseVersion());
        assertEquals(3, againstV1.resolvedVersion());
        assertEquals(2, registry.versions("orders").size(), "预检只读，不得落库");

        // 不存在的参照版本 -> VERSION_CONFLICT，预检也不落库
        GovernanceException ex = assertThrows(GovernanceException.class,
                () -> registry.precheck(candidate, 99));
        log.info("[指定参照] 非法版本号 99 -> 拒绝原因={}", ex.getReason());
        assertEquals(RejectReason.VERSION_CONFLICT, ex.getReason());
        assertEquals(2, registry.versions("orders").size());
    }

    @Test
    void requiredChangePrecheckParityAcrossLevels() {
        // 必填收紧：BACKWARD 预检拒绝 / FORWARD 预检通过，正式注册结论一致
        DataContract tightened = contract("u", CompatibilityLevel.BACKWARD, f("id", "long", true));
        registry.register(contract("u", CompatibilityLevel.BACKWARD, f("id", "long", false)));
        PrecheckResult pre = registry.precheck(tightened, null);
        assertEquals(PrecheckStatus.REJECTED, pre.status());
        assertTrue(pre.violationsByType().containsKey(ChangeType.REQUIRED_TIGHTENED.name()));
        assertThrows(GovernanceException.class, () -> registry.register(tightened));
        log.info("[必填收紧-BACKWARD] 预检={}, 正式注册同样拒绝, 历史版本数={}",
                pre.status(), registry.versions("u").size());

        DataContract relaxed = contract("v", CompatibilityLevel.FORWARD, f("id", "long", false));
        registry.register(contract("v", CompatibilityLevel.FORWARD, f("id", "long", true)));
        PrecheckResult preRelax = registry.precheck(relaxed, null);
        assertEquals(PrecheckStatus.REJECTED, preRelax.status());
        assertTrue(preRelax.violationsByType().containsKey(ChangeType.REQUIRED_RELAXED.name()));
        assertThrows(GovernanceException.class, () -> registry.register(relaxed));
        log.info("[必填放宽-FORWARD] 预检={}, 正式注册同样拒绝, 历史版本数={}",
                preRelax.status(), registry.versions("v").size());
    }

    @Test
    void concurrentReplayOfHistoricalVersionConverges() throws InterruptedException {
        // v1 -> v2 已注册
        registry.register(contract("evt", CompatibilityLevel.NONE, f("id", "long", true)));
        registry.register(contract("evt", CompatibilityLevel.NONE,
                f("id", "long", true), f("a", "string", false)));

        int threads = 12;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        Set<Integer> resolvedVersions = ConcurrentHashMap.newKeySet();
        // 一半线程重放 v1，一半线程重放 v2，全部应收敛，不得生成 v3
        for (int i = 0; i < threads; i++) {
            boolean replayV1 = i % 2 == 0;
            pool.submit(() -> {
                try {
                    go.await();
                    DataContract c = replayV1
                            ? contract("evt", CompatibilityLevel.NONE, f("id", "long", true))
                            : contract("evt", CompatibilityLevel.NONE,
                                    f("id", "long", true), f("a", "string", false));
                    resolvedVersions.add(registry.register(c).version());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        go.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        log.info("[并发历史重放] {} 线程重放 v1/v2, 收敛版本号集合={}, 历史版本数={}",
                threads, resolvedVersions, registry.versions("evt").size());
        assertEquals(Set.of(1, 2), resolvedVersions, "并发重放只能收敛到已有的 v1/v2");
        assertEquals(2, registry.versions("evt").size(), "并发重放不得产生重复版本");
    }

    private static Integer resolvedVersionSafe(PrecheckResult r) {
        return r.resolvedVersion();
    }
}
