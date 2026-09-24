package com.github.xingjianluo.datacontractgovernancejava.lineage.exception;

import com.github.xingjianluo.datacontractgovernancejava.lineage.model.LineageIssueCode;

/** 血缘登记被拒绝的业务异常，携带可区分原因码。 */
public class LineageRejectedException extends RuntimeException {

    private final LineageIssueCode code;

    public LineageRejectedException(LineageIssueCode code, String message) {
        super(message);
        this.code = code;
    }

    public LineageRejectedException(LineageIssueCode code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public LineageIssueCode getCode() {
        return code;
    }
}
