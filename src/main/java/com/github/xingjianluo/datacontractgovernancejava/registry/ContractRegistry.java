package com.github.xingjianluo.datacontractgovernancejava.registry;

import com.github.xingjianluo.datacontractgovernancejava.compat.CompatibilityChecker;
import com.github.xingjianluo.datacontractgovernancejava.compat.CompatibilityReport;
import com.github.xingjianluo.datacontractgovernancejava.error.GovernanceException;
import com.github.xingjianluo.datacontractgovernancejava.error.RejectReason;
import com.github.xingjianluo.datacontractgovernancejava.model.DataContract;
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
 * 并发语义：按契约名加锁串行化注册，临界区内完成 校验 -> 兼容判定 -> 提交，
 * 因此并发注册不会出现版本号重复或丢失更新；被拒绝的请求在提交前抛出，
 * 不留下任何部分写入状态。
 */
@Repository
public class ContractRegistry {

    private final CompatibilityChecker checker;
    private final Map<String, List<ContractVersion>> store = new ConcurrentHashMap<>();
    private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    public ContractRegistry(CompatibilityChecker checker) {
        this.checker = checker;
    }

    /**
     * 注册新版本。
     * - 内容与最新版本完全一致：幂等重放，返回现有版本号，不产生新版本；
     * - 兼容校验失败：抛 GovernanceException(INCOMPATIBLE_SCHEMA)，不写入任何状态。
     */
    public RegistrationResult register(DataContract contract) {
        validate(contract);
        ReentrantLock lock = locks.computeIfAbsent(contract.getName(), k -> new ReentrantLock());
        lock.lock();
        try {
            List<ContractVersion> history = store.computeIfAbsent(contract.getName(), k -> new ArrayList<>());
            ContractVersion latest = history.isEmpty() ? null : history.get(history.size() - 1);

            // 幂等：与最新版本内容指纹一致，直接重放结论
            if (latest != null && latest.fingerprint().equals(contract.fingerprint())) {
                return new RegistrationResult(RegistrationStatus.IDEMPOTENT_REPLAY,
                        contract.getName(), latest.version());
            }

            CompatibilityReport report = checker.check(latest == null ? null : latest.contract(), contract);
            if (!report.compatible()) {
                throw new GovernanceException(RejectReason.INCOMPATIBLE_SCHEMA,
                        "契约 '" + contract.getName() + "' 不兼容变更被拒绝: " + report.violations());
            }

            int nextVersion = history.size() + 1;
            history.add(new ContractVersion(contract.getName(), nextVersion, contract, contract.fingerprint()));
            return new RegistrationResult(RegistrationStatus.REGISTERED, contract.getName(), nextVersion);
        } finally {
            lock.unlock();
        }
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
