package com.github.xingjianluo.datacontractgovernancejava.lineage.model;

import java.util.List;

/**
 * 单条敏感级收敛依据：哪个上游（或自身声明）贡献了哪个级别。
 */
public record SensitivityContribution(
        String fromNodeId,
        SensitivityLevel level,
        List<String> path
) {
}
