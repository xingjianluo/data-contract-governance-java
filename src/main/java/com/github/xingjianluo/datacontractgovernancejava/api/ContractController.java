package com.github.xingjianluo.datacontractgovernancejava.api;

import com.github.xingjianluo.datacontractgovernancejava.model.DataContract;
import com.github.xingjianluo.datacontractgovernancejava.registry.ContractRegistry;
import com.github.xingjianluo.datacontractgovernancejava.registry.ContractVersion;
import com.github.xingjianluo.datacontractgovernancejava.registry.RegistrationResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 契约注册入口。
 */
@RestController
@RequestMapping("/api/contracts")
public class ContractController {

    private final ContractRegistry registry;

    public ContractController(ContractRegistry registry) {
        this.registry = registry;
    }

    @PostMapping
    public RegistrationResult register(@RequestBody DataContract contract) {
        return registry.register(contract);
    }

    @GetMapping("/{name}/versions")
    public List<ContractVersion> versions(@PathVariable String name) {
        return registry.versions(name);
    }
}
