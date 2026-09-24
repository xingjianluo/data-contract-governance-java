package com.github.xingjianluo.datacontractgovernancejava.registry;

import com.github.xingjianluo.datacontractgovernancejava.model.DataContract;

/**
 * 已注册的契约版本：版本号单调递增、不可变。
 */
public record ContractVersion(String contractName, int version, DataContract contract, String fingerprint) {
}
