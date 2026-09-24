package com.github.xingjianluo.datacontractgovernancejava.api;

import com.github.xingjianluo.datacontractgovernancejava.lineage.FieldRef;
import com.github.xingjianluo.datacontractgovernancejava.lineage.LineageEdge;
import com.github.xingjianluo.datacontractgovernancejava.lineage.LineageRegistry;
import com.github.xingjianluo.datacontractgovernancejava.lineage.SensitivityResolution;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 血缘登记与敏感级别查询入口。
 */
@RestController
@RequestMapping("/api/lineage")
public class LineageController {

    private final LineageRegistry registry;

    public LineageController(LineageRegistry registry) {
        this.registry = registry;
    }

    @PostMapping
    public Map<String, Object> register(@RequestBody LineageEdge edge) {
        boolean created = registry.registerEdge(edge);
        return Map.of("created", created, "from", edge.from().toString(), "to", edge.to().toString());
    }

    @GetMapping
    public List<LineageEdge> edges() {
        return registry.edges();
    }

    @GetMapping("/sensitivity/{contract}/{field}")
    public SensitivityResolution sensitivity(@PathVariable String contract, @PathVariable String field) {
        return registry.resolveSensitivity(FieldRef.of(contract, field));
    }
}
