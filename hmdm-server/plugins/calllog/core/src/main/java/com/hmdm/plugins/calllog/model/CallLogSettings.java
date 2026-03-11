package com.hmdm.plugins.calllog.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;

import java.io.Serializable;
import java.util.List;

/**
 * Settings for call log plugin
 */
@ApiModel(description = "Call log plugin settings")
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CallLogSettings implements Serializable {

    @ApiModelProperty("Plugin enabled status")
    private boolean enabled;

    @ApiModelProperty("Customer ID")
    private int customerId;

    @ApiModelProperty("Number of days to keep logs (0 = forever)")
    private int retentionDays;

    public CallLogSettings() {
        this.enabled = true;
        this.retentionDays = 90; // Default 90 days
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getCustomerId() {
        return customerId;
    }

    public void setCustomerId(int customerId) {
        this.customerId = customerId;
    }

    public int getRetentionDays() {
        return retentionDays;
    }

    public void setRetentionDays(int retentionDays) {
        this.retentionDays = retentionDays;
    }
}
