package com.github.xingjianluo.datacontractgovernancejava.api;

import com.github.xingjianluo.datacontractgovernancejava.error.GovernanceException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * 统一把拒绝原因映射为可区分的 409 响应。
 */
@RestControllerAdvice
public class GovernanceExceptionHandler {

    @ExceptionHandler(GovernanceException.class)
    public ResponseEntity<Map<String, Object>> handle(GovernanceException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("reason", ex.getReason().name(), "message", ex.getMessage()));
    }
}
