package com.github.xingjianluo.datacontractgovernancejava.lineage.model;

import java.util.List;

/**
 * 字段敏感级的最终收敛结论：取所有来源中的最高级别；
 * 当多个来源级别并列且 transform 不同等导致不一致时，标记冲突并保留全部依据。
 */
public record SensitivityResolution(
        String nodeId,
        SensitivityLevel effectiveLevel,
        boolean conflict,
        List<SensitivityContribution> contributions
) {
}
