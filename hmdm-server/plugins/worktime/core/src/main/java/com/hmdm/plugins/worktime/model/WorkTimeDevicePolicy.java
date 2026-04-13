package com.hmdm.plugins.worktime.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;

import java.io.Serializable;
import java.sql.Timestamp;

@ApiModel(description = "Per-device Work Time policy")
@JsonIgnoreProperties(ignoreUnknown = true)
public class WorkTimeDevicePolicy implements Serializable {

    private static final long serialVersionUID = 1L;

    @ApiModelProperty("Policy ID")
    private Integer id;

    @ApiModelProperty("Customer ID")
    private int customerId;

    @ApiModelProperty("Device ID")
    private int deviceId;

    @ApiModelProperty("Device name")
    private String deviceName;

    @ApiModelProperty("Start time HH:mm")
    private String startTime;

    @ApiModelProperty("End time HH:mm")
    private String endTime;

    @ApiModelProperty("Days of week bitmask")
    private Integer daysOfWeek;

    @ApiModelProperty("Allowed apps during work (comma separated or '*')")
    private String allowedAppsDuringWork;

    @ApiModelProperty("Allowed apps outside work (comma separated or '*')")
    private String allowedAppsOutsideWork;

    @ApiModelProperty("Enforcement enabled")
    private Boolean enabled;

    @ApiModelProperty("Created at")
    private Timestamp createdAt;

    @ApiModelProperty("Updated at")
    private Timestamp updatedAt;

    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public int getCustomerId() {
        return customerId;
    }

    public void setCustomerId(int customerId) {
        this.customerId = customerId;
    }

    public int getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(int deviceId) {
        this.deviceId = deviceId;
    }

    public String getDeviceName() {
        return deviceName;
    }

    public void setDeviceName(String deviceName) {
        this.deviceName = deviceName;
    }

    public String getStartTime() {
        return startTime;
    }

    public void setStartTime(String startTime) {
        this.startTime = startTime;
    }

    public String getEndTime() {
        return endTime;
    }

    public void setEndTime(String endTime) {
        this.endTime = endTime;
    }

    public Integer getDaysOfWeek() {
        return daysOfWeek;
    }

    public void setDaysOfWeek(Integer daysOfWeek) {
        this.daysOfWeek = daysOfWeek;
    }

    public String getAllowedAppsDuringWork() {
        return allowedAppsDuringWork;
    }

    public void setAllowedAppsDuringWork(String allowedAppsDuringWork) {
        this.allowedAppsDuringWork = allowedAppsDuringWork;
    }

    public String getAllowedAppsOutsideWork() {
        return allowedAppsOutsideWork;
    }

    public void setAllowedAppsOutsideWork(String allowedAppsOutsideWork) {
        this.allowedAppsOutsideWork = allowedAppsOutsideWork;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    public Timestamp getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Timestamp createdAt) {
        this.createdAt = createdAt;
    }

    public Timestamp getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Timestamp updatedAt) {
        this.updatedAt = updatedAt;
    }
}
