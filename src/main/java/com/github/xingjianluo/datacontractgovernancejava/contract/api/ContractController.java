package com.github.xingjianluo.datacontractgovernancejava.contract.api;

import com.github.xingjianluo.datacontractgovernancejava.contract.model.ContractDefinition;
import com.github.xingjianluo.datacontractgovernancejava.contract.model.ContractVersion;
import com.github.xingjianluo.datacontractgovernancejava.contract.model.RegistrationResponse;
import com.github.xingjianluo.datacontractgovernancejava.contract.service.ContractService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 契约治理 HTTP 入口。 */
@RestController
@RequestMapping("/api/contracts")
public class ContractController {

    private final ContractService contractService;

    public ContractController(ContractService contractService) {
        this.contractService = contractService;
    }

    @PostMapping("/register")
    public RegistrationResponse register(@RequestBody ContractDefinition definition) {
        return contractService.register(definition);
    }

    @GetMapping("/{name}/versions")
    public List<ContractVersion> versions(@PathVariable("name") String name) {
        return contractService.history(name);
    }

    @GetMapping("/{name}/latest")
    public ResponseEntity<ContractVersion> latest(@PathVariable("name") String name) {
        return contractService.latest(name)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).build());
    }
}
