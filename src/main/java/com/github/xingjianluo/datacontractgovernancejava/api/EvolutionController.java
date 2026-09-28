package com.github.xingjianluo.datacontractgovernancejava.api;

import com.github.xingjianluo.datacontractgovernancejava.evolution.PrecheckResult;
import com.github.xingjianluo.datacontractgovernancejava.model.CompatibilityLevel;
import com.github.xingjianluo.datacontractgovernancejava.model.DataContract;
import com.github.xingjianluo.datacontractgovernancejava.registry.ContractRegistry;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 版本演进管理入口：发布前预检（dry-run），只读不写，结论与正式注册完全一致。
 */
@RestController
@RequestMapping("/api/evolution")
public class EvolutionController {

    private final ContractRegistry registry;

    public EvolutionController(ContractRegistry registry) {
        this.registry = registry;
    }

    /**
     * 演进预检。
     *
     * @param referenceVersion 可选：参照的已注册版本号，缺省为最新版本
     * @param compatibility    可选：临时指定兼容级别，缺省采用候选契约自身声明的级别
     */
    @PostMapping("/precheck")
    public PrecheckResult precheck(@RequestBody DataContract contract,
                                   @RequestParam(name = "referenceVersion", required = false)
                                   Integer referenceVersion,
                                   @RequestParam(name = "compatibility", required = false)
                                   CompatibilityLevel compatibility) {
        return registry.precheck(contract, referenceVersion, compatibility);
    }
}
