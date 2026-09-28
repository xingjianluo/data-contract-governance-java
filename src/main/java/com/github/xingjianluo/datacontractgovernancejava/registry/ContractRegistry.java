package com.github.xingjianluo.datacontractgovernancejava.registry;

import com.github.xingjianluo.datacontractgovernancejava.compat.CompatibilityChecker;
import com.github.xingjianluo.datacontractgovernancejava.compat.CompatibilityReport;
import com.github.xingjianluo.datacontractgovernancejava.error.GovernanceException;
import com.github.xingjianluo.datacontractgovernancejava.error.RejectReason;
import com.github.xingjianluo.datacontractgovernancejava.model.DataContract;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 契约注册中心（本地存储）。
 *
 * 并发语义：按契约名加锁串行化注册，临界区内完成 校验 -> 历史重放收敛 -> 参照版本解析
 * -> 兼容判定 -> 提交，因此并发注册/并发重放不会出现版本号重复、丢失更新或半写入状态。
 *
 * 演进预检（{@link #precheck}）与正式注册（{@link #register}）共享同一个判定函数
 * {@code decideLocked}：预检只读取快照绝不写入，正式注册在同一临界区内判定后立即提交，
 * 两者结论完全一致。
 */
@Repository
public class ContractRegistry {

    private static final Logger log = LoggerFactory.getLogger(ContractRegistry.class);

    private final CompatibilityChecker checker;
    private final Map<String, List<ContractVersion>> store = new ConcurrentHashMap<>();
    private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    public ContractRegistry(CompatibilityChecker checker) {
        this.checker = checker;
    }

    /**
     * 注册新版本（以最新版本为参照）。
     * - 内容与任一历史版本完全一致：历史版本重放收敛，返回现有版本号（IDEMPOTENT_REPLAY），不产生新版本；
     * - 兼容校验失败：抛 GovernanceException(INCOMPATIBLE_SCHEMA)，不写入任何状态。
     */
    public RegistrationResult register(DataContract contract) {
        return register(contract, null);
    }

    /**
     * 注册新版本，可显式指定参照的已注册版本号（null 表示以最新版本为参照）。
     * 判定与提交在同一临界区内完成，杜绝参照版本过期、版本号竞争与半写入。
     */
    public RegistrationResult register(DataContract contract, Integer baseVersion) {
        log.info("正式注册输入: name={}, level={}, baseVersion={}, fingerprint={}",
                contract.getName(), contract.getCompatibility(), baseVersion, contract.fingerprint());
        validate(contract);
        ReentrantLock lock = locks.computeIfAbsent(contract.getName(), k -> new ReentrantLock());
        lock.lock();
        try {
            List<ContractVersion> history = store.computeIfAbsent(contract.getName(), k -> new ArrayList<>());
            EvolutionDecision decision = decideLocked(history, contract, baseVersion);

            if (decision.isReplay()) {
                log.info("正式注册结论: 历史版本重放收敛 name={}, 复用版本号={}",
                        contract.getName(), decision.idempotentVersion());
                return new RegistrationResult(RegistrationStatus.IDEMPOTENT_REPLAY,
                        contract.getName(), decision.idempotentVersion());
            }
            if (decision.isRejected()) {
                log.info("正式注册结论: 拒绝 name={}, 依据={}",
                        contract.getName(), decision.report().violations());
                throw new GovernanceException(RejectReason.INCOMPATIBLE_SCHEMA,
                        "契约 '" + contract.getName() + "' 不兼容变更被拒绝: "
                                + decision.report().violations(),
                        decision.report());
            }

            int nextVersion = history.size() + 1;
            history.add(new ContractVersion(contract.getName(), nextVersion, contract,
                    contract.fingerprint()));
            log.info("正式注册结论: 落库 name={}, 新版本号={}, 参照版本={}",
                    contract.getName(), nextVersion,
                    decision.base() == null ? null : decision.base().version());
            return new RegistrationResult(RegistrationStatus.REGISTERED, contract.getName(), nextVersion);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 演进预检：只读，绝不修改任何已注册数据。
     * 返回结论与以相同参数调用 {@link #register(DataContract, Integer)} 的结果完全一致：
     * COMPATIBLE / IDEMPOTENT_REPLAY 携带版本号，REJECTED 携带按变更种类区分的拒绝依据。
     *
     * @param baseVersion 指定参照的已有版本号；null 表示以最新版本为参照
     */
    public PrecheckResult precheck(DataContract contract, Integer baseVersion) {
        log.info("演进预检输入: name={}, level={}, baseVersion={}, fingerprint={}",
                contract.getName(), contract.getCompatibility(), baseVersion, contract.fingerprint());
        validate(contract);
        ReentrantLock lock = locks.computeIfAbsent(contract.getName(), k -> new ReentrantLock());
        lock.lock();
        EvolutionDecision decision;
        try {
            List<ContractVersion> history = store.computeIfAbsent(contract.getName(), k -> new ArrayList<>());
            decision = decideLocked(history, contract, baseVersion);
        } finally {
            lock.unlock();
        }

        Integer baseVer = decision.base() == null ? null : decision.base().version();
        PrecheckResult result;
        if (decision.isReplay()) {
            result = PrecheckResult.idempotent(contract.getName(), baseVer, decision.idempotentVersion());
        } else if (decision.isRejected()) {
            result = PrecheckResult.rejected(contract.getName(), baseVer, decision.report().violations());
        } else {
            result = PrecheckResult.compatible(contract.getName(), baseVer, decision.nextVersion());
        }
        log.info("演进预检结论: status={}, name={}, baseVersion={}, resolvedVersion={}, 依据={}",
                result.status(), contract.getName(), result.baseVersion(),
                result.resolvedVersion(), result.violations());
        return result;
    }

    /**
     * 预检/注册共用的只读判定（调用方必须已持有该契约的锁）：
     * 全历史重放收敛 -> 参照版本解析 -> 兼容判定。不产生任何写入。
     */
    private EvolutionDecision decideLocked(List<ContractVersion> history, DataContract contract,
                                           Integer baseVersion) {
        // 历史版本重放收敛优先：内容与任一已注册版本一致即复用该版本号，不看当前最新版本是谁
        Integer replay = findReplayVersion(history, contract);
        if (replay != null) {
            ContractVersion base = history.get(history.size() - 1);
            return new EvolutionDecision(replay, base, history.size() + 1,
                    CompatibilityReport.ok());
        }

        ContractVersion base = resolveBase(history, contract.getName(), baseVersion);
        CompatibilityReport report = checker.check(base == null ? null : base.contract(), contract);
        return new EvolutionDecision(null, base, history.size() + 1, report);
    }

    /** 在全部历史版本中按内容指纹查找；找不到返回 null。 */
    private Integer findReplayVersion(List<ContractVersion> history, DataContract contract) {
        for (ContractVersion v : history) {
            if (v.fingerprint().equals(contract.fingerprint())) {
                return v.version();
            }
        }
        return null;
    }

    private ContractVersion resolveBase(List<ContractVersion> history, String name, Integer baseVersion) {
        if (baseVersion == null) {
            return history.isEmpty() ? null : history.get(history.size() - 1);
        }
        if (baseVersion < 1 || baseVersion > history.size()) {
            throw new GovernanceException(RejectReason.VERSION_CONFLICT,
                    "契约 '" + name + "' 不存在参照版本 " + baseVersion
                            + "（当前历史版本数=" + history.size() + "）");
        }
        return history.get(baseVersion - 1);
    }

    private void validate(DataContract contract) {
        if (contract.getName() == null || contract.getName().isBlank()) {
            throw new GovernanceException(RejectReason.INVALID_CONTRACT, "契约名称不能为空");
        }
        if (contract.getCompatibility() == null) {
            throw new GovernanceException(RejectReason.INVALID_CONTRACT, "兼容级别不能为空");
        }
        long distinct = contract.getFields().stream().map(f -> f.getName()).distinct().count();
        if (distinct != contract.getFields().size()) {
            throw new GovernanceException(RejectReason.INVALID_CONTRACT,
                    "契约 '" + contract.getName() + "' 存在重复字段名");
        }
        boolean invalid = contract.getFields().stream()
                .anyMatch(f -> f.getName() == null || f.getName().isBlank()
                        || f.getType() == null || f.getType().isBlank());
        if (invalid) {
            throw new GovernanceException(RejectReason.INVALID_CONTRACT,
                    "契约 '" + contract.getName() + "' 存在字段名或类型为空的字段");
        }
    }

    public Optional<ContractVersion> latest(String contractName) {
        List<ContractVersion> history = versions(contractName);
        return history.isEmpty() ? Optional.empty() : Optional.of(history.get(history.size() - 1));
    }

    public List<ContractVersion> versions(String contractName) {
        ReentrantLock lock = locks.computeIfAbsent(contractName, k -> new ReentrantLock());
        lock.lock();
        try {
            List<ContractVersion> history = store.get(contractName);
            return history == null ? List.of() : List.copyOf(history);
        } finally {
            lock.unlock();
        }
    }
}
