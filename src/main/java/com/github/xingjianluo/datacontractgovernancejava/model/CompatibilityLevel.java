package com.github.xingjianluo.datacontractgovernancejava.model;

/**
 * 兼容性级别：
 * BACKWARD 新契约可读取旧版本数据；FORWARD 旧契约可读取新版本数据；
 * FULL 双向兼容；NONE 不做兼容性约束。
 */
public enum CompatibilityLevel {
    BACKWARD,
    FORWARD,
    FULL,
    NONE
}
