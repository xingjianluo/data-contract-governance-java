package com.github.xingjianluo.datacontractgovernancejava.error;

/**
 * 治理层统一异常：携带可区分的拒绝原因，失败请求不得留下部分写入。
 */
public class GovernanceException extends RuntimeException {

    private final RejectReason reason;

    public GovernanceException(RejectReason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public RejectReason getReason() {
        return reason;
    }
}
