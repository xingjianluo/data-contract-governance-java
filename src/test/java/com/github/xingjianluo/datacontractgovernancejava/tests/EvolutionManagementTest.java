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

    @Test
    void fieldRemovalPrecheckParityAcrossLevels() {
        // 同一份“删除字段”候选在预检与正式注册中结论必须一致，且严格跟随级别的读写方向：
        //                 删除必填          删除可选
        //  BACKWARD         通过/落库         通过/落库
        //  FORWARD          拒绝 FIELD_REMOVED 通过/落库
        //  FULL             拒绝 FIELD_REMOVED 通过/落库
        for (CompatibilityLevel level : List.of(CompatibilityLevel.BACKWARD,
                CompatibilityLevel.FORWARD, CompatibilityLevel.FULL)) {
            String base = "rm-" + level;

            // 删除必填字段：基线 {id 必填, note 可选} -> 候选 {note 可选}
            DataContract dropRequired = contract(base + "-req", level, f("note", "string", false));
            assertRemovalParity(level, base + "-req",
                    contract(base + "-req", level, f("id", "long", true), f("note", "string", false)),
                    dropRequired, true);

            // 删除可选字段：基线 {id 必填, note 可选} -> 候选 {id 必填}
            DataContract dropOptional = contract(base + "-opt", level, f("id", "long", true));
            assertRemovalParity(level, base + "-opt",
                    contract(base + "-opt", level, f("id", "long", true), f("note", "string", false)),
                    dropOptional, false);
        }
    }

    @Test
    void sameFieldRemovalCandidateFollowsSwitchedCompatibilityLevel() {
        // 同一份“删除必填字段”候选，切换级别后结论必须跟随该级别真实读写含义：
        String name = "rm-switch";
        DataContract v1 = contract(name, CompatibilityLevel.FORWARD,
                f("id", "long", true), f("note", "string", false));
        registry.register(v1);

        // 以 FORWARD 注册的基线，候选切到 BACKWARD 做删除必填：新读者读旧数据，合法 -> 预检通过并可落库
        DataContract asBackward = contract(name, CompatibilityLevel.BACKWARD, f("note", "string", false));
        PrecheckResult backwardPre = registry.precheck(asBackward, null);
        log.info("[切换级别-BACKWARD删除必填] 预检={}, 依据={}",
                backwardPre.status(), backwardPre.violations());
        assertEquals(PrecheckStatus.COMPATIBLE, backwardPre.status());
        RegistrationResult registered = registry.register(asBackward);
        assertEquals(RegistrationStatus.REGISTERED, registered.status());

        // 再补一个 FORWARD 基线，同形态候选在 FORWARD 下必须拒绝
        String forwardName = "rm-switch-fwd";
        registry.register(contract(forwardName, CompatibilityLevel.FORWARD,
                f("id", "long", true), f("note", "string", false)));
        DataContract asForward = contract(forwardName, CompatibilityLevel.FORWARD,
                f("note", "string", false));
        PrecheckResult forwardPre = registry.precheck(asForward, null);
        log.info("[切换级别-FORWARD删除必填] 预检={}, 分组依据={}",
                forwardPre.status(), forwardPre.violationsByType());
        assertEquals(PrecheckStatus.REJECTED, forwardPre.status());
        assertTrue(forwardPre.violationsByType().containsKey(ChangeType.FIELD_REMOVED.name()));
        GovernanceException ex = assertThrows(GovernanceException.class, () -> registry.register(asForward));
        assertEquals(forwardPre.violations(), ex.getReport().violations(),
                "切换级别后预检与正式注册依据仍须逐条一致");
        assertEquals(1, registry.versions(forwardName).size(), "FORWARD 下被拒不落库");
    }

    /**
     * 断言删除字段候选在预检与正式注册中结论/依据一致：
     * @param removedRequired true=候选删除了基线中的必填字段（FORWARD/FULL 应拒）；false=删除可选字段（一律放行）
     */
    private void assertRemovalParity(CompatibilityLevel level, String name,
                                     DataContract baseline, DataContract candidate,
                                     boolean removedRequired) {
        RegistrationResult baseResult = registry.register(baseline);
        assertEquals(RegistrationStatus.REGISTERED, baseResult.status());
        int versionsBefore = registry.versions(name).size();

        PrecheckResult pre = registry.precheck(candidate, null);
        boolean shouldReject = removedRequired && level != CompatibilityLevel.BACKWARD;
        log.info("[删除字段一致性] level={}, name={}, 删除必填={}, 预检={}, 依据={}",
                level, name, removedRequired, pre.status(), pre.violations());

        if (shouldReject) {
            assertEquals(PrecheckStatus.REJECTED, pre.status(), level + " 删除必填必须预检拒绝");
            assertNull(pre.resolvedVersion(), "拒绝结论不得携带版本号");
            assertTrue(pre.violations().stream()
                            .anyMatch(v -> v.changeType() == ChangeType.FIELD_REMOVED),
                    level + " 拒绝依据必须能按变更种类区分为 FIELD_REMOVED");
            assertEquals(versionsBefore, registry.versions(name).size(), "预检只读，不得改动已注册数据");

            GovernanceException ex = assertThrows(GovernanceException.class,
                    () -> registry.register(candidate));
            assertEquals(RejectReason.INCOMPATIBLE_SCHEMA, ex.getReason());
            assertEquals(pre.violations(), ex.getReport().violations(),
                    level + " 预检与正式注册的拒绝依据必须逐条一致");
            assertEquals(versionsBefore, registry.versions(name).size(),
                    level + " 正式拒绝不得留下半写入状态");
            // 再预检一次，结论保持稳定
            assertEquals(pre.violations(), registry.precheck(candidate, null).violations(),
                    "重复预检结论必须稳定一致");
        } else {
            assertEquals(PrecheckStatus.COMPATIBLE, pre.status(),
                    level + " 删除" + (removedRequired ? "必填" : "可选") + "字段应判定为合法演进");
            RegistrationResult registered = registry.register(candidate);
            assertEquals(RegistrationStatus.REGISTERED, registered.status());
            assertEquals(pre.resolvedVersion(), registered.version(),
                    level + " 正式注册版本号必须与预检预告一致");
            assertEquals(versionsBefore + 1, registry.versions(name).size());
        }
    }

    private static Integer resolvedVersionSafe(PrecheckResult r) {
        return r.resolvedVersion();
    }
}
