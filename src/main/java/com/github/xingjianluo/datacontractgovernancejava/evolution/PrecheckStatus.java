package com.github.xingjianluo.datacontractgovernancejava.evolution;

/**
 * 演进预检结论状态：
 * - REGISTERED：按当前状态正式提交会被接受，并将注册为 version 指向的新版本；
 * - IDEMPOTENT_REPLAY：内容与某个已注册历史版本完全一致，正式提交会幂等返回该版本，不产生新版本；
 * - REJECTED：正式提交会被拒绝，依据见 violations（可按变更种类区分）。
 */
public enum PrecheckStatus {
    REGISTERED,
    IDEMPOTENT_REPLAY,
    REJECTED
}
