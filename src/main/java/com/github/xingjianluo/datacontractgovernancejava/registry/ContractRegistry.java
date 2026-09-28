package com.github.xingjianluo.datacontractgovernancejava.registry;

import com.github.xingjianluo.datacontractgovernancejava.compat.CompatibilityChecker;
import com.github.xingjianluo.datacontractgovernancejava.compat.CompatibilityReport;
import com.github.xingjianluo.datacontractgovernancejava.error.GovernanceException;
import com.github.xingjianluo.datacontractgovernancejava.error.RejectReason;
import com.github.xingjianluo.datacontractgovernancejava.evolution.PrecheckResult;
import com.github.xingjianluo.datacontractgovernancejava.model.CompatibilityLevel;
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
 * 并发语义：按契约名加锁串行化所有演进判定，临界区内完成
 * 历史重放扫描 -> 参照版本解析 -> 兼容判定 -> 提交。预检与正式注册共用同一个评估核心，
 * 因此二者结论严格一致；并发重放同一历史内容不会产生重复版本、丢更新或半写入状态。
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
     * 注册新版本（默认以最新版本为参照）。
     * - 内容与任意历史版本指纹一致：历史重放收敛，幂等返回该已有版本号，不产生新版本；
     * - 兼容校验失败：抛 GovernanceException(INCOMPATIBLE_SCHEMA) 并携带判定依据，不写入任何状态。
     */
    public RegistrationResult register(DataContract contract) {
        return register(contract, null);
    }

    /**
     * 以显式指定的已注册版本为参照注册新版本（参照版本不存在时抛 VERSION_CONFLICT）。
     */
    public RegistrationResult register(DataContract contract, Integer referenceVersion) {
        validate(contract);
        ReentrantLock lock = locks.computeIfAbsent(contract.getName(), k -> new ReentrantLock());
        lock.lock();
        try {
            List<ContractVersion> history = store.computeIfAbsent(contract.getName(), k -> new ArrayList<>());
            Evaluation eval = evaluate(history, contract, null, referenceVersion);
            String fp = contract.fingerprint();

            if (eval.replay() != null) {
                log.info("[正式注册-幂等重放] 契约={}, 候选指纹={}, 命中历史版本={}, 不产生新版本",
                        contract.getName(), fp, eval.replay().version());
                return new RegistrationResult(RegistrationStatus.IDEMPOTENT_REPLAY,
                        contract.getName(), eval.replay().version());
            }

            if (!eval.report().compatible()) {
                log.info("[正式注册-拒绝] 契约={}, 参照版本={}, level={}, 候选指纹={}, 判定依据={}",
                        contract.getName(), versionOf(eval.reference()), eval.level(), fp,
                        eval.report().violations());
                throw new GovernanceException(RejectReason.INCOMPATIBLE_SCHEMA,
                        "契约 '" + contract.getName() + "' 不兼容变更被拒绝: " + eval.report().violations(),
                        eval.report().violations());
            }

            int nextVersion = history.size() + 1;
            history.add(new ContractVersion(contract.getName(), nextVersion, contract, fp));
            log.info("[正式注册-接受] 契约={}, 参照版本={}, level={}, 分配新版本={}, 历史版本数={}",
                    contract.getName(), versionOf(eval.reference()), eval.level(), nextVersion, history.size());
            return new RegistrationResult(RegistrationStatus.REGISTERED, contract.getName(), nextVersion);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 演进预检：只读不写。结论与 {@link #register(DataContract, Integer)} 完全一致。
     *
     * @param contract        候选契约
     * @param referenceVersion 指定参照版本；null 表示以最新版本为参照
     * @param levelOverride   临时指定兼容级别；null 表示采用候选契约自身声明的级别
     */
    public PrecheckResult precheck(DataContract contract, Integer referenceVersion, CompatibilityLevel levelOverride) {
        validate(contract);
        ReentrantLock lock = locks.computeIfAbsent(contract.getName(), k -> new ReentrantLock());
        lock.lock();
        try {
            List<ContractVersion> history = store.getOrDefault(contract.getName(), List.of());
            Evaluation eval = evaluate(history, contract, levelOverride, referenceVersion);
            String fp = contract.fingerprint();

            if (eval.replay() != null) {
                log.info("[演进预检-幂等重放] 契约={}, 候选指纹={}, 命中历史版本={}（预检只读，不写入）",
                        contract.getName(), fp, eval.replay().version());
                return PrecheckResult.idempotentReplay(contract.getName(), eval.replay().version(), eval.level());
            }

            int referenceVersionNo = versionOf(eval.reference());
            if (!eval.report().compatible()) {
                log.info("[演进预检-拒绝] 契约={}, 参照版本={}, level={}, 候选指纹={}, 判定依据={}（预检只读，不写入）",
                        contract.getName(), referenceVersionNo, eval.level(), fp, eval.report().violations());
                return PrecheckResult.rejected(contract.getName(), referenceVersionNo,
                        eval.level(), eval.report().violations());
            }

            int nextVersion = history.size() + 1;
            log.info("[演进预检-接受] 契约={}, 参照版本={}, level={}, 预计新版本={}（预检只读，不写入）",
                    contract.getName(), referenceVersionNo, eval.level(), nextVersion);
            return PrecheckResult.registered(contract.getName(), nextVersion, referenceVersionNo, eval.level());
        } finally {
            lock.unlock();
        }
    }

    /**
     * 预检与正式注册共享的唯一评估核心，保证两条路径结论一致。
     * 步骤：历史内容重放扫描（最高优先） -> 参照版本解析 -> 兼容判定。
     */
    private Evaluation evaluate(List<ContractVersion> history, DataContract contract,
                                CompatibilityLevel levelOverride, Integer referenceVersion) {
        CompatibilityLevel level = levelOverride != null ? levelOverride : contract.getCompatibility();
        String fp = contract.fingerprint();

        // 1) 历史版本重放收敛：与任意历史版本内容指纹一致即幂等，不受“当前最新版本是谁”影响
        for (ContractVersion v : history) {
            if (v.fingerprint().equals(fp)) {
                return new Evaluation(v, null, level, CompatibilityReport.ok());
            }
        }

        // 2) 解析参照版本（首个版本或无历史时为 null）
        ContractVersion reference = resolveReference(history, referenceVersion);

        // 3) 兼容判定：内容与所有历史版本都不同，必须按指定级别过判定
        CompatibilityReport report = checker.check(reference == null ? null : reference.contract(),
                contract, level);
        return new Evaluation(null, reference, level, report);
    }

    private ContractVersion resolveReference(List<ContractVersion> history, Integer referenceVersion) {
        if (referenceVersion == null) {
            return history.isEmpty() ? null : history.get(history.size() - 1);
        }
        if (referenceVersion < 1 || referenceVersion > history.size()) {
            throw new GovernanceException(RejectReason.VERSION_CONFLICT,
                    "契约 '" + (history.isEmpty() ? "" : history.get(0).contractName())
                            + "' 参照版本 " + referenceVersion + " 不存在，当前共有 " + history.size() + " 个版本");
        }
        return history.get(referenceVersion - 1);
    }

    private int versionOf(ContractVersion version) {
        return version == null ? 0 : version.version();
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

    /** 评估中间结论：replay 非空表示命中历史重放；否则看 report 是否兼容。 */
    private record Evaluation(ContractVersion replay, ContractVersion reference,
                              CompatibilityLevel level, CompatibilityReport report) {
    }
}
