package com.github.xingjianluo.datacontractgovernancejava.api;

import com.github.xingjianluo.datacontractgovernancejava.model.DataContract;
import com.github.xingjianluo.datacontractgovernancejava.registry.ContractRegistry;
import com.github.xingjianluo.datacontractgovernancejava.registry.ContractVersion;
import com.github.xingjianluo.datacontractgovernancejava.registry.PrecheckResult;
import com.github.xingjianluo.datacontractgovernancejava.registry.RegistrationResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 契约注册与演进预检入口。
 */
@RestController
@RequestMapping("/api/contracts")
public class ContractController {

    private final ContractRegistry registry;

    public ContractController(ContractRegistry registry) {
        this.registry = registry;
    }

    /**
     * 演进预检（只读）：先看结论再决定是否落库。
     *
     * @param baseVersion 可选，参照的已有版本号；缺省以最新版本为参照
     */
    @PostMapping("/precheck")
    public PrecheckResult precheck(@RequestBody DataContract contract,
                                   @RequestParam(name = "baseVersion", required = false) Integer baseVersion) {
        return registry.precheck(contract, baseVersion);
    }

    /**
     * 正式注册。可选 baseVersion 显式指定参照版本；缺省以最新版本为参照。
     * 预检与本入口共享同一判定逻辑，结论完全一致。
     */
    @PostMapping
    public RegistrationResult register(@RequestBody DataContract contract,
                                       @RequestParam(name = "baseVersion", required = false) Integer baseVersion) {
        return registry.register(contract, baseVersion);
    }

    @GetMapping("/{name}/versions")
    public List<ContractVersion> versions(@PathVariable String name) {
        return registry.versions(name);
    }
}
