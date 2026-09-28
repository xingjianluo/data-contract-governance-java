package com.github.xingjianluo.datacontractgovernancejava.error;

import com.github.xingjianluo.datacontractgovernancejava.compat.CompatibilityReport;

/**
 * 治理层统一异常：携带可区分的拒绝原因，失败请求不得留下部分写入。
 */
public class GovernanceException extends RuntimeException {

    private final RejectReason reason;
    private final transient CompatibilityReport report;

    public GovernanceException(RejectReason reason, String message) {
        this(reason, message, null);
    }

    public GovernanceException(RejectReason reason, String message, CompatibilityReport report) {
        super(message);
        this.reason = reason;
        this.report = report;
    }

    public RejectReason getReason() {
        return reason;
    }

    /** 兼容判定拒绝时携带的完整报告（含按变更种类区分的依据）；其他原因为 null。 */
    public CompatibilityReport getReport() {
        return report;
    }
}
