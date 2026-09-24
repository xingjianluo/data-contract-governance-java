package com.github.xingjianluo.datacontractgovernancejava.model;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 数据契约：名称 + 兼容级别 + 字段集合。version 由注册中心分配或校验。
 */
public class DataContract {

    private String name;
    private CompatibilityLevel compatibility = CompatibilityLevel.BACKWARD;
    private List<ContractField> fields = new ArrayList<>();

    public DataContract() {
    }

    public DataContract(String name, CompatibilityLevel compatibility, List<ContractField> fields) {
        this.name = name;
        this.compatibility = compatibility;
        this.fields = fields == null ? new ArrayList<>() : new ArrayList<>(fields);
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public CompatibilityLevel getCompatibility() {
        return compatibility;
    }

    public void setCompatibility(CompatibilityLevel compatibility) {
        this.compatibility = compatibility;
    }

    public List<ContractField> getFields() {
        return fields;
    }

    public void setFields(List<ContractField> fields) {
        this.fields = fields == null ? new ArrayList<>() : new ArrayList<>(fields);
    }

    public ContractField field(String fieldName) {
        return fields.stream()
                .filter(f -> f.getName().equals(fieldName))
                .findFirst()
                .orElse(null);
    }

    /** 内容指纹：同一契约同一版本的重复提交据此做幂等判定。 */
    public String fingerprint() {
        return name + "|" + compatibility + "|"
                + fields.stream().map(ContractField::fingerprint).sorted()
                        .collect(Collectors.joining(","));
    }
}
