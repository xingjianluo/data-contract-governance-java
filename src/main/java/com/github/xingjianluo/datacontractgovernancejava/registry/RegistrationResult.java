package com.github.xingjianluo.datacontractgovernancejava.registry;

/**
 * 注册结果：状态 + 版本号；重复提交返回相同版本号。
 */
public record RegistrationResult(RegistrationStatus status, String contractName, int version) {
}
