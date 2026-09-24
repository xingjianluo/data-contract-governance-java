package com.github.xingjianluo.datacontractgovernancejava.model;

import java.util.Objects;

/**
 * 契约字段定义。
 */
public class ContractField {

    private String name;
    private String type;
    private boolean required;
    private SensitivityLevel sensitivity = SensitivityLevel.PUBLIC;

    public ContractField() {
    }

    public ContractField(String name, String type, boolean required, SensitivityLevel sensitivity) {
        this.name = name;
        this.type = type;
        this.required = required;
        this.sensitivity = sensitivity == null ? SensitivityLevel.PUBLIC : sensitivity;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public boolean isRequired() {
        return required;
    }

    public void setRequired(boolean required) {
        this.required = required;
    }

    public SensitivityLevel getSensitivity() {
        return sensitivity;
    }

    public void setSensitivity(SensitivityLevel sensitivity) {
        this.sensitivity = sensitivity;
    }

    /** 结构指纹：用于幂等判定，不包含敏感级别以外的元数据。 */
    public String fingerprint() {
        return name + ":" + type + ":" + required + ":" + sensitivity;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ContractField that)) {
            return false;
        }
        return required == that.required
                && Objects.equals(name, that.name)
                && Objects.equals(type, that.type)
                && sensitivity == that.sensitivity;
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, type, required, sensitivity);
    }
}
