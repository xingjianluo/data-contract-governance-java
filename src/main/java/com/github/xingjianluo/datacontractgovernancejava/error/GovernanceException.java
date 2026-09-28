package com.github.xingjianluo.datacontractgovernancejava.error;

import com.github.xingjianluo.datacontractgovernancejava.compat.CompatibilityViolation;

import java.util.List;

/**
 * 治理层统一异常：携带可区分的拒绝原因，失败请求不得留下部分写入。
 * 兼容不通过时同时携带按变更种类区分的判定依据，与预检结论保持同一套输出。
 */
public class GovernanceException extends RuntimeException {

    private final RejectReason reason;
    private final transient List<CompatibilityViolation> violations;

    public GovernanceException(RejectReason reason, String message) {
        this(reason, message, List.of());
    }

    public GovernanceException(RejectReason reason, String message, List<CompatibilityViolation> violations) {
        super(message);
        this.reason = reason;
        this.violations = List.copyOf(violations);
    }

    public RejectReason getReason() {
        return reason;
    }

    public List<CompatibilityViolation> getViolations() {
        return violations;
    }
}
