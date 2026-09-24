package com.github.xingjianluo.datacontractgovernancejava.lineage.api;

import com.github.xingjianluo.datacontractgovernancejava.lineage.model.DataNode;
import com.github.xingjianluo.datacontractgovernancejava.lineage.model.LineageEdge;
import com.github.xingjianluo.datacontractgovernancejava.lineage.model.LineageRegisterResponse;
import com.github.xingjianluo.datacontractgovernancejava.lineage.model.SensitivityResolution;
import com.github.xingjianluo.datacontractgovernancejava.lineage.service.LineageService;
import com.github.xingjianluo.datacontractgovernancejava.lineage.store.LineageStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 血缘治理 HTTP 入口。 */
@RestController
@RequestMapping("/api/lineage")
public class LineageController {

    private final LineageService lineageService;
    private final LineageStore lineageStore;

    public LineageController(LineageService lineageService, LineageStore lineageStore) {
        this.lineageService = lineageService;
        this.lineageStore = lineageStore;
    }

    @PostMapping("/nodes")
    public DataNode registerNode(@RequestBody NodeRegistrationRequest request) {
        return lineageService.registerNode(request.contractName(), request.version(),
                request.fieldName(), request.declaredSensitivity());
    }

    @PostMapping("/edges")
    public LineageRegisterResponse registerEdge(@RequestBody EdgeRegistrationRequest request) {
        return lineageService.registerEdge(request.sourceId(), request.targetId(), request.transform());
    }

    @GetMapping("/nodes/{id}/sensitivity")
    public SensitivityResolution sensitivity(@PathVariable("id") String id) {
        return lineageService.resolveSensitivity(id);
    }

    @GetMapping("/edges")
    public List<LineageEdge> edges() {
        return lineageStore.allEdges();
    }
}
