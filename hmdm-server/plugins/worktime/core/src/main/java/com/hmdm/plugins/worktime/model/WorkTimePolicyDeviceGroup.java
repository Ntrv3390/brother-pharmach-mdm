package com.hmdm.plugins.worktime.model;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.io.Serializable;

@ApiModel(description = "Work Time Policy Device Group Assignment")
@JsonIgnoreProperties(ignoreUnknown = true)
public class WorkTimePolicyDeviceGroup implements Serializable {

    @ApiModelProperty("ID")
    private Integer id;

    @ApiModelProperty("Policy ID")
    private Integer policyId;

    @ApiModelProperty("Device Group ID")
    private Integer groupId;

    public WorkTimePolicyDeviceGroup() {
    }

    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public Integer getPolicyId() {
        return policyId;
    }

    public void setPolicyId(Integer policyId) {
        this.policyId = policyId;
    }

    public Integer getGroupId() {
        return groupId;
    }

    public void setGroupId(Integer groupId) {
        this.groupId = groupId;
    }
}
