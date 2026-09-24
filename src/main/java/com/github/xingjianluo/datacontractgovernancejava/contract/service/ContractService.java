package com.github.xingjianluo.datacontractgovernancejava.contract.service;

import com.github.xingjianluo.datacontractgovernancejava.contract.exception.ContractRejectedException;
import com.github.xingjianluo.datacontractgovernancejava.contract.model.CompatibilityMode;
import com.github.xingjianluo.datacontractgovernancejava.contract.model.CompatibilityReport;
import com.github.xingjianluo.datacontractgovernancejava.contract.model.ContractDefinition;
import com.github.xingjianluo.datacontractgovernancejava.contract.model.ContractVersion;
import com.github.xingjianluo.datacontractgovernancejava.contract.model.FieldDefinition;
import com.github.xingjianluo.datacontractgovernancejava.contract.model.RejectionCode;
import com.github.xingjianluo.datacontractgovernancejava.contract.model.RegistrationOutcome;
import com.github.xingjianluo.datacontractgovernancejava.contract.model.RegistrationResponse;
import com.github.xingjianluo.datacontractgovernancejava.contract.store.ContractStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 契约注册、版本管理与并发一致性入口。
 *
 * <p>并发语义：对同一 contractName 的注册在该名称的锁上串行化（不同名称互不阻塞）。
 * 兼容判定与版本序检查全部先于落盘完成，被拒绝的请求不会产生任何写入；
 * 落盘使用 {@code putIfAbsent} 原子语义，即使绕过锁也不会出现重复版本。
 * 重复提交同一 (contractName, version, mode, fields) 返回 IDEMPOTENT_REPLAY，
 * 结论可复现。</p>
 */
@Service
public class ContractService {

    private static final Logger log = LoggerFactory.getLogger(ContractService.class);

    private final ContractStore store;
    private final ContractCompatibilityChecker checker;

    public ContractService(ContractStore store, ContractCompatibilityChecker checker) {
        this.store = store;
        this.checker = checker;
    }

    public RegistrationResponse register(ContractDefinition definition) {
        validate(definition);
        String name = definition.contractName();
        log.info("[register] input contract={} version={} mode={} fields={}",
                name, definition.version(), definition.mode(),
                definition.fields().stream().map(FieldDefinition::name).toList());

        synchronized (lockFor(name)) {
            List<ContractVersion> history = store.findAll(name);
            Optional<ContractVersion> exact = history.stream()
                    .filter(v -> v.version() == definition.version())
                    .findFirst();

            if (exact.isPresent()) {
                ContractVersion existing = exact.get();
                if (payloadEquals(existing, definition)) {
                    log.info("[register] idempotent replay contract={} version={}", name, existing.version());
                    return new RegistrationResponse(RegistrationOutcome.IDEMPOTENT_REPLAY,
                            name, existing.version(), null,
                            "identical version already registered; replayed idempotently",
                            List.of());
                }
                log.warn("[register] rejected contract={} version={} reason=VERSION_ALREADY_EXISTS "
                        + "existingFields={} candidateFields={}",
                        name, definition.version(),
                        existing.fields().stream().map(FieldDefinition::name).toList(),
                        definition.fields().stream().map(FieldDefinition::name).toList());
                throw new ContractRejectedException(RejectionCode.VERSION_ALREADY_EXISTS,
                        "version " + definition.version() + " of contract '" + name
                                + "' already exists with a different definition");
            }

            // 版本必须严格递增（允许跳号，但不能回退/重号）
            int maxVersion = history.stream().mapToInt(ContractVersion::version).max().orElse(0);
            if (definition.version() <= maxVersion) {
                log.warn("[register] rejected contract={} version={} max={} reason=VERSION_OUT_OF_ORDER",
                        name, definition.version(), maxVersion);
                throw new ContractRejectedException(RejectionCode.VERSION_OUT_OF_ORDER,
                        "version " + definition.version() + " must be greater than current max " + maxVersion);
            }

            // 基线（首个版本）或 NONE 模式不做兼容判定；否则相对最新版本判定
            ContractVersion previous = history.isEmpty() ? null
                    : history.get(history.size() - 1);
            CompatibilityReport report = checker.check(previous, definition);
            if (!report.compatible()) {
                log.warn("[register] rejected contract={} version={} reason=INCOMPATIBLE_CHANGE violations={}",
                        name, definition.version(), report.violations());
                throw new ContractRejectedException(RejectionCode.INCOMPATIBLE_CHANGE,
                        "version " + definition.version() + " is incompatible with version "
                                + (previous == null ? "-" : previous.version())
                                + " under mode " + report.mode() + ": "
                                + report.violations().stream()
                                        .map(v -> v.reason() + "(" + v.fieldName() + ")")
                                        .toList());
            }

            // 全部检查通过后才落盘；putIfAbsent 在并发下仍是去重的最后防线
            ContractVersion candidate = new ContractVersion(name, definition.version(),
                    definition.mode(), List.copyOf(definition.fields()), Instant.now());
            ContractVersion stored = store.putIfAbsent(candidate);
            if (stored != candidate && !payloadEquals(stored, definition)) {
                // 理论上锁内不会走到，这里保证“绝不覆盖既有版本”
                throw new ContractRejectedException(RejectionCode.VERSION_ALREADY_EXISTS,
                        "version " + definition.version() + " of contract '" + name
                                + "' already exists with a different definition");
            }
            log.info("[register] created contract={} version={} mode={}",
                    name, stored.version(), stored.mode());
            return new RegistrationResponse(RegistrationOutcome.CREATED,
                    name, stored.version(), null, "registered", List.of());
        }
    }

    public List<ContractVersion> history(String contractName) {
        return store.findAll(contractName);
    }

    public Optional<ContractVersion> latest(String contractName) {
        List<ContractVersion> all = store.findAll(contractName);
        return all.isEmpty() ? Optional.empty() : Optional.of(all.get(all.size() - 1));
    }

    /** 每个契约一把锁，避免不同契约互相阻塞。 */
    private Object lockFor(String contractName) {
        return Locks.computeIfAbsent(contractName.intern(), k -> new Object());
    }

    private static final java.util.concurrent.ConcurrentHashMap<String, Object> Locks =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static void validate(ContractDefinition d) {
        if (d == null) {
            throw new IllegalArgumentException("request body is required");
        }
        if (d.contractName() == null || d.contractName().isBlank()) {
            throw new IllegalArgumentException("contractName is required");
        }
        if (d.version() == null || d.version() <= 0) {
            throw new IllegalArgumentException("version must be a positive integer");
        }
        if (d.mode() == null) {
            throw new IllegalArgumentException("mode is required (BACKWARD/FORWARD/FULL/NONE)");
        }
        if (d.fields() == null || d.fields().isEmpty()) {
            throw new IllegalArgumentException("at least one field is required");
        }
        Set<String> names = new HashSet<>();
        for (FieldDefinition f : d.fields()) {
            if (f == null || f.name() == null || f.name().isBlank()) {
                throw new IllegalArgumentException("field name is required");
            }
            if (f.type() == null) {
                throw new IllegalArgumentException("field type is required for '" + f.name() + "'");
            }
            if (!names.add(f.name())) {
                throw new ContractRejectedException(RejectionCode.DUPLICATE_FIELD_NAME,
                        "duplicate field name '" + f.name() + "' in contract '" + d.contractName() + "'");
            }
        }
    }

    private static boolean payloadEquals(ContractVersion v, ContractDefinition d) {
        if (v.mode() != d.mode() || v.version() != d.version()) {
            return false;
        }
        if (v.fields().size() != d.fields().size()) {
            return false;
        }
        for (int i = 0; i < v.fields().size(); i++) {
            FieldDefinition a = v.fields().get(i);
            FieldDefinition b = d.fields().get(i);
            if (!a.name().equals(b.name()) || a.type() != b.type()
                    || a.required() != b.required()
                    || !java.util.Objects.equals(a.renamedFrom(), b.renamedFrom())
                    || !java.util.Objects.equals(a.description(), b.description())) {
                return false;
            }
        }
        return true;
    }
}
