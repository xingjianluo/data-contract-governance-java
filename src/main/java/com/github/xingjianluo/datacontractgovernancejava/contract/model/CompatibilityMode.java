package com.github.xingjianluo.datacontractgovernancejava.contract.model;

/**
 * 契约演进时声明的兼容级别：
 * BACKWARD：新版本可被旧消费者读取（消费者使用旧 schema 读新数据）；
 * FORWARD：旧版本写出的数据可被新消费者读取；
 * FULL：同时满足 BACKWARD 与 FORWARD；
 * NONE：不做兼容性约束（仅登记，不判定）。
 */
public enum CompatibilityMode {
    BACKWARD,
    FORWARD,
    FULL,
    NONE
}
