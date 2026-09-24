package com.github.xingjianluo.datacontractgovernancejava.registry;

/**
 * 注册结果状态：新注册 / 幂等重放（内容一致的重复提交，不产生新版本）。
 */
public enum RegistrationStatus {
    REGISTERED,
    IDEMPOTENT_REPLAY
}
