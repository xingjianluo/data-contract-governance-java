package com.github.xingjianluo.datacontractgovernancejava.registry;

/**
 * 演进预检结论状态：可注册为新版本 / 命中历史版本幂等重放 / 将被拒绝。
 */
public enum PrecheckStatus {
    COMPATIBLE,
    IDEMPOTENT_REPLAY,
    REJECTED
}
