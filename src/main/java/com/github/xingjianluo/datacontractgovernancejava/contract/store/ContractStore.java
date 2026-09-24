package com.github.xingjianluo.datacontractgovernancejava.contract.store;

import com.github.xingjianluo.datacontractgovernancejava.contract.model.ContractVersion;

import java.util.List;
import java.util.Optional;

/** 契约版本本地存储抽象。 */
public interface ContractStore {

    /** 原子地：若不存在同 (name,version) 则插入；返回当前存在的版本（新插入或既有）。 */
    ContractVersion putIfAbsent(ContractVersion version);

    Optional<ContractVersion> find(String contractName, int version);

    List<ContractVersion> findAll(String contractName);

    void clear();
}
