package com.github.xingjianluo.datacontractgovernancejava.common.api;

import com.github.xingjianluo.datacontractgovernancejava.contract.exception.ContractRejectedException;
import com.github.xingjianluo.datacontractgovernancejava.lineage.exception.LineageRejectedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** 把业务拒绝/非法入参转成结构一致、原因可区分的错误响应；拒绝本身不产生写入。 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ContractRejectedException.class)
    public ResponseEntity<Map<String, Object>> handleContract(ContractRejectedException ex) {
        log.warn("[api] contract rejected code={} message={}", ex.getCode(), ex.getMessage());
        return reject(ex.getCode().name(), ex.getMessage(), HttpStatus.CONFLICT);
    }

    @ExceptionHandler(LineageRejectedException.class)
    public ResponseEntity<Map<String, Object>> handleLineage(LineageRejectedException ex) {
        // 非法入参（INVALID_EDGE）归 400，其余明确拒绝归 409
        HttpStatus status = ex.getCode().name().equals("INVALID_EDGE")
                ? HttpStatus.BAD_REQUEST : HttpStatus.CONFLICT;
        log.warn("[api] lineage rejected code={} message={}", ex.getCode(), ex.getMessage());
        return reject(ex.getCode().name(), ex.getMessage(), status);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegal(IllegalArgumentException ex) {
        log.warn("[api] bad request message={}", ex.getMessage());
        return reject("INVALID_REQUEST", ex.getMessage(), HttpStatus.BAD_REQUEST);
    }

    private static ResponseEntity<Map<String, Object>> reject(String code, String message,
                                                              HttpStatus status) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("rejectionCode", code);
        body.put("message", message);
        return ResponseEntity.status(status).body(body);
    }
}
