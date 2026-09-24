package com.github.xingjianluo.datacontractgovernancejava.contract.exception;

import com.github.xingjianluo.datacontractgovernancejava.contract.model.RejectionCode;

/** 契约注册被拒绝的业务异常，携带可区分原因码。 */
public class ContractRejectedException extends RuntimeException {

    private final RejectionCode code;

    public ContractRejectedException(RejectionCode code, String message) {
        super(message);
        this.code = code;
    }

    public RejectionCode getCode() {
        return code;
    }
}
