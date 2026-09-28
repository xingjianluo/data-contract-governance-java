package com.github.xingjianluo.datacontractgovernancejava.api;

import com.github.xingjianluo.datacontractgovernancejava.compat.CompatibilityReport;
import com.github.xingjianluo.datacontractgovernancejava.compat.CompatibilityViolation;
import com.github.xingjianluo.datacontractgovernancejava.error.GovernanceException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 统一把拒绝原因映射为可区分的 409 响应；兼容拒绝时附带按变更种类分组的结构化依据。
 */
@RestControllerAdvice
public class GovernanceExceptionHandler {

    @ExceptionHandler(GovernanceException.class)
    public ResponseEntity<Map<String, Object>> handle(GovernanceException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("reason", ex.getReason().name());
        body.put("message", ex.getMessage());
        CompatibilityReport report = ex.getReport();
        if (report != null) {
            body.put("compatible", report.compatible());
            body.put("violations", report.violations());
            Map<String, List<CompatibilityViolation>> byType = new LinkedHashMap<>();
            for (CompatibilityViolation v : report.violations()) {
                byType.computeIfAbsent(v.changeType().name(), k -> new java.util.ArrayList<>()).add(v);
            }
            body.put("violationsByType", byType);
        }
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }
}
