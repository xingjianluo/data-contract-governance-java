package com.github.xingjianluo.datacontractgovernancejava.api;

import com.github.xingjianluo.datacontractgovernancejava.error.GovernanceException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 统一把拒绝原因映射为可区分的 409 响应；兼容不通过时附上按变更种类区分的判定依据。
 */
@RestControllerAdvice
public class GovernanceExceptionHandler {

    @ExceptionHandler(GovernanceException.class)
    public ResponseEntity<Map<String, Object>> handle(GovernanceException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("reason", ex.getReason().name());
        body.put("message", ex.getMessage());
        if (!ex.getViolations().isEmpty()) {
            body.put("violations", ex.getViolations());
        }
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }
}
