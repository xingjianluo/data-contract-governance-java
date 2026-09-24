package com.github.xingjianluo.datacontractgovernancejava.contract.store;

import com.github.xingjianluo.datacontractgovernancejava.contract.model.ContractVersion;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于 {@link ConcurrentHashMap} 的本地契约版本存储。
 *
 * <p>key 为 contractName + ':' + version；putIfAbsent 是原子操作，
 * 因此并发提交同一版本时只会有一个写入胜出，其余调用读到既存版本，
 * 不会产生重复版本，也不会出现丢失更新。</p>
 */
@Component
public class InMemoryContractStore implements ContractStore {

    private final ConcurrentHashMap<String, ContractVersion> versions = new ConcurrentHashMap<>();

    private static String key(String contractName, int version) {
        return contractName + ":" + version;
    }

    @Override
    public ContractVersion putIfAbsent(ContractVersion version) {
        versions.putIfAbsent(key(version.contractName(), version.version()), version);
        return versions.get(key(version.contractName(), version.version()));
    }

    @Override
    public Optional<ContractVersion> find(String contractName, int version) {
        return Optional.ofNullable(versions.get(key(contractName, version)));
    }

    @Override
    public List<ContractVersion> findAll(String contractName) {
        return versions.values().stream()
                .filter(v -> v.contractName().equals(contractName))
                .sorted(Comparator.comparingInt(ContractVersion::version))
                .toList();
    }

    @Override
    public void clear() {
        versions.clear();
    }
}
