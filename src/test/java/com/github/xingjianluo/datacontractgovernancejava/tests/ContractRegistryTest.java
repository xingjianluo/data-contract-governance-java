package com.github.xingjianluo.datacontractgovernancejava.tests;

import com.github.xingjianluo.datacontractgovernancejava.compat.CompatibilityChecker;
import com.github.xingjianluo.datacontractgovernancejava.error.GovernanceException;
import com.github.xingjianluo.datacontractgovernancejava.error.RejectReason;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContractRegistryTest {

    private static final Logger log = LoggerFactory.getLogger(ContractRegistryTest.class);

    private final ContractRegistry registry = new ContractRegistry(new CompatibilityChecker());

    private static ContractField f(String name, String type, boolean required) {
        return new ContractField(name, type, required, SensitivityLevel.PUBLIC);
    }

    private static DataContract contract(String name, ContractField... fields) {
        return new DataContract(name, CompatibilityLevel.BACKWARD, List.of(fields));
    }

    @Test
    void duplicateSubmissionIsIdempotent() {
        DataContract v1 = contract("orders", f("id", "long", true));
        RegistrationResult first = registry.register(v1);
        RegistrationResult replay = registry.register(contract("orders", f("id", "long", true)));
        log.info("[幂等重放] 首次={}, 重复提交={}, 版本历史={}", first, replay, registry.versions("orders"));
        assertEquals(RegistrationStatus.REGISTERED, first.status());
        assertEquals(RegistrationStatus.IDEMPOTENT_REPLAY, replay.status());
        assertEquals(first.version(), replay.version());
        assertEquals(1, registry.versions("orders").size(), "重复提交不得产生重复版本");
    }

    @Test
    void rejectedChangeLeavesNoPartialState() {
        registry.register(contract("orders", f("id", "long", true), f("note", "string", false)));
        DataContract bad = contract("orders", f("id", "string", true));
        GovernanceException ex = assertThrows(GovernanceException.class, () -> registry.register(bad));
        log.info("[拒绝无残留] 输入类型变更 id:long->string, 拒绝原因={}, 版本历史={}",
                ex.getReason(), registry.versions("orders"));
        assertEquals(RejectReason.INCOMPATIBLE_SCHEMA, ex.getReason());
        assertEquals(1, registry.versions("orders").size(), "被拒绝的请求不得留下部分写入");
        assertEquals("long", registry.latest("orders").orElseThrow().contract().field("id").getType());
    }

    @Test
    void concurrentRegistrationProducesUniqueSequentialVersions() throws InterruptedException {
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        Set<Integer> versions = ConcurrentHashMap.newKeySet();
        AtomicInteger registered = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();

        // 每个线程提交一个内容互不相同的契约（NONE 级别隔离兼容判定，
        // 专注验证并发下版本号唯一且不丢失更新）
        for (int i = 0; i < threads; i++) {
            int idx = i;
            pool.submit(() -> {
                ready.countDown();
                try {
                    go.await();
                    DataContract c = new DataContract("events", CompatibilityLevel.NONE,
                            List.of(f("id", "long", true), f("col_" + idx, "string", false)));
                    RegistrationResult r = registry.register(c);
                    if (r.status() == RegistrationStatus.REGISTERED) {
                        versions.add(r.version());
                        registered.incrementAndGet();
                    }
                } catch (GovernanceException e) {
                    rejected.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        go.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        int total = registry.versions("events").size();
        log.info("[并发注册] 线程数={}, 成功注册={}, 拒绝={}, 版本号集合={}, 历史版本数={}",
                threads, registered.get(), rejected.get(), versions, total);
        assertEquals(0, rejected.get(), "兼容变更不应被拒绝");
        assertEquals(registered.get(), versions.size(), "版本号不得重复");
        assertEquals(total, registered.get(), "不得丢失更新：每次成功注册都应留下一个版本");
        assertEquals(IntStream.rangeClosed(1, threads).boxed().collect(java.util.stream.Collectors.toSet()),
                versions, "版本号应为 1..N 连续序列");
    }

    @Test
    void concurrentDuplicateSubmissionsAreIdempotent() throws InterruptedException {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        Set<Integer> versions = ConcurrentHashMap.newKeySet();
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    go.await();
                    versions.add(registry.register(contract("users", f("id", "long", true))).version());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        go.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        log.info("[并发幂等] 相同契约并发提交 {} 次, 得到版本号={}, 历史版本数={}",
                threads, versions, registry.versions("users").size());
        assertEquals(Set.of(1), versions, "相同内容的并发提交应收敛到同一版本号");
        assertEquals(1, registry.versions("users").size());
    }
}
