package com.github.xingjianluo.datacontractgovernancejava.tests.contract;

import com.github.xingjianluo.datacontractgovernancejava.contract.exception.ContractRejectedException;
import com.github.xingjianluo.datacontractgovernancejava.contract.model.CompatibilityMode;
import com.github.xingjianluo.datacontractgovernancejava.contract.model.ContractDefinition;
import com.github.xingjianluo.datacontractgovernancejava.contract.model.FieldDefinition;
import com.github.xingjianluo.datacontractgovernancejava.contract.model.FieldType;
import com.github.xingjianluo.datacontractgovernancejava.contract.model.RegistrationOutcome;
import com.github.xingjianluo.datacontractgovernancejava.contract.model.RejectionCode;
import com.github.xingjianluo.datacontractgovernancejava.contract.service.ContractCompatibilityChecker;
import com.github.xingjianluo.datacontractgovernancejava.contract.service.ContractService;
import com.github.xingjianluo.datacontractgovernancejava.contract.store.InMemoryContractStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 覆盖拒绝原因可区分、幂等重放、失败不留写、以及并发注册不产生重复版本/丢失更新。 */
class ContractServiceTest {

    private static final Logger log = LoggerFactory.getLogger(ContractServiceTest.class);

    private InMemoryContractStore store;
    private ContractService service;

    private static FieldDefinition f(String name, FieldType type, boolean required) {
        return new FieldDefinition(name, type, required, null, null);
    }

    private static ContractDefinition def(String name, int version, CompatibilityMode mode,
                                          List<FieldDefinition> fields) {
        return new ContractDefinition(name, version, mode, fields);
    }

    @BeforeEach
    void setUp() {
        store = new InMemoryContractStore();
        service = new ContractService(store, new ContractCompatibilityChecker());
    }

    @Test
    void incompatibleChangeIsRejectedWithDistinctCodeAndNothingPersisted() {
        ContractDefinition v1 = def("orders", 1, CompatibilityMode.BACKWARD,
                List.of(f("id", FieldType.LONG, true), f("ssn", FieldType.STRING, true)));
        service.register(v1);

        // 删除必填字段 + 改类型，BACKWARD 下删除合法但类型变更非法（用 BACKWARD 验证 FIELD_TYPE_CHANGED）
        ContractDefinition bad = def("orders", 2, CompatibilityMode.BACKWARD,
                List.of(f("id", FieldType.STRING, true), f("ssn", FieldType.STRING, true)));
        log.info("[CASE] incompatible register input={}", bad.fields().stream()
                .map(x -> x.name() + ":" + x.type()).toList());
        ContractRejectedException ex = assertThrows(ContractRejectedException.class,
                () -> service.register(bad));
        log.warn("[CASE] rejected code={} message={}", ex.getCode(), ex.getMessage());
        assertEquals(RejectionCode.INCOMPATIBLE_CHANGE, ex.getCode());

        // v2 不得落盘，最新仍是 v1
        assertEquals(1, service.history("orders").size());
        assertEquals(1, service.latest("orders").orElseThrow().version());
    }

    @Test
    void duplicateSameVersionIsIdempotentAndDoesNotCreateExtraVersion() {
        ContractDefinition v1 = def("orders", 1, CompatibilityMode.FULL,
                List.of(f("id", FieldType.LONG, false)));
        assertEquals(RegistrationOutcome.CREATED, service.register(v1).outcome());
        assertEquals(RegistrationOutcome.IDEMPOTENT_REPLAY, service.register(v1).outcome());
        assertEquals(RegistrationOutcome.IDEMPOTENT_REPLAY, service.register(v1).outcome());
        assertEquals(1, service.history("orders").size());
        assertEquals(1, service.latest("orders").orElseThrow().version());
    }

    @Test
    void sameVersionWithDifferentPayloadIsRejected() {
        service.register(def("orders", 1, CompatibilityMode.FULL,
                List.of(f("id", FieldType.LONG, false))));
        ContractDefinition conflict = def("orders", 1, CompatibilityMode.FULL,
                List.of(f("id", FieldType.STRING, false)));
        ContractRejectedException ex = assertThrows(ContractRejectedException.class,
                () -> service.register(conflict));
        assertEquals(RejectionCode.VERSION_ALREADY_EXISTS, ex.getCode());
        assertEquals(1, service.history("orders").size());
    }

    @Test
    void lowerVersionNumberIsRejectedAsOutOfOrder() {
        service.register(def("orders", 5, CompatibilityMode.NONE,
                List.of(f("id", FieldType.LONG, true))));
        ContractRejectedException ex = assertThrows(ContractRejectedException.class,
                () -> service.register(def("orders", 2, CompatibilityMode.NONE,
                        List.of(f("id", FieldType.LONG, true)))));
        assertEquals(RejectionCode.VERSION_OUT_OF_ORDER, ex.getCode());
        assertEquals(1, service.history("orders").size());
    }

    @Test
    void validEvolutionAcrossVersionsIsAccepted() {
        service.register(def("orders", 1, CompatibilityMode.BACKWARD,
                List.of(f("id", FieldType.LONG, true))));
        // BACKWARD：新增可选字段合法
        var resp = service.register(def("orders", 2, CompatibilityMode.BACKWARD,
                List.of(f("id", FieldType.LONG, true), f("note", FieldType.STRING, false))));
        assertEquals(RegistrationOutcome.CREATED, resp.outcome());
        assertEquals(List.of(1, 2), service.history("orders").stream()
                .map(v -> v.version()).collect(Collectors.toList()));
    }

    @Test
    void concurrentRegistrationOfSameVersionCreatesExactlyOneVersion() throws Exception {
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<RegistrationOutcome>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            Callable<RegistrationOutcome> task = () -> {
                ready.countDown();
                start.await();
                return service.register(def("orders", 1, CompatibilityMode.NONE,
                        List.of(f("id", FieldType.LONG, true)))).outcome();
            };
            futures.add(pool.submit(task));
        }
        ready.await();
        start.countDown();

        long created = 0;
        long replay = 0;
        for (Future<RegistrationOutcome> fut : futures) {
            RegistrationOutcome o = fut.get();
            if (o == RegistrationOutcome.CREATED) {
                created++;
            } else if (o == RegistrationOutcome.IDEMPOTENT_REPLAY) {
                replay++;
            }
        }
        pool.shutdown();
        log.info("[CASE] concurrent same-version: threads={} created={} replay={}",
                threads, created, replay);
        assertEquals(1, created, "同一版本并发注册只能有一个 CREATED");
        assertEquals(threads - 1, replay, "其余全部幂等回放");
        assertEquals(1, service.history("orders").size());
    }

    @Test
    void concurrentIncrementalVersionsNeverDuplicateOrLoseUpdates() throws Exception {
        // 预置 v1，随后多线程抢注不同的下一个版本号：恰好一个线程成功注册 v2，
        // 其余线程因版本序/重号被拒绝；最终版本集合必须连续、唯一、无丢失。
        service.register(def("orders", 1, CompatibilityMode.NONE,
                List.of(f("id", FieldType.LONG, true))));

        int threads = 12;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            Callable<String> task = () -> {
                ready.countDown();
                start.await();
                try {
                    service.register(def("orders", 2, CompatibilityMode.NONE,
                            List.of(f("id", FieldType.LONG, true),
                                    f("extra_" + Thread.currentThread().getId(),
                                            FieldType.STRING, false))));
                    return "CREATED";
                } catch (ContractRejectedException e) {
                    return "REJECTED:" + e.getCode();
                }
            };
            futures.add(pool.submit(task));
        }
        ready.await();
        start.countDown();

        List<String> results = new ArrayList<>();
        for (Future<String> fut : futures) {
            results.add(fut.get());
        }
        pool.shutdown();
        Collections.sort(results);
        long created = results.stream().filter(r -> r.equals("CREATED")).count();
        log.info("[CASE] concurrent race-for-v2 results={}", results);
        assertEquals(1, created, "只有一个线程赢得 v2");
        assertEquals(2, service.history("orders").size(), "无重复版本也无丢失");
        assertTrue(results.stream().allMatch(r -> r.equals("CREATED")
                || r.equals("REJECTED:" + RejectionCode.VERSION_ALREADY_EXISTS.name())
                || r.equals("REJECTED:" + RejectionCode.INCOMPATIBLE_CHANGE.name())
                || r.equals("REJECTED:" + RejectionCode.VERSION_OUT_OF_ORDER.name())));
    }
}
