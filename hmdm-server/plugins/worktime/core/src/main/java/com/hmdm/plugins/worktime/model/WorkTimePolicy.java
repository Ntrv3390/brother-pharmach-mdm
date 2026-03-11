package com.hmdm.plugins.worktime.model;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.io.Serializable;
import java.util.Date;
import java.util.List;

@ApiModel(description = "Work Time Policy")
@JsonIgnoreProperties(ignoreUnknown = true)
public class WorkTimePolicy implements Serializable {

    @ApiModelProperty("ID")
    private Integer id;

    @ApiModelProperty("Name")
    private String name;

    @ApiModelProperty("Description")
    private String description;

    @ApiModelProperty("Start time (HH:mm)")
    private String startTime;

    @ApiModelProperty("End time (HH:mm)")
    private String endTime;

    @ApiModelProperty("Days of week (bitmask)")
    private Integer daysOfWeek;

    @ApiModelProperty("Allowed apps during work time (comma separated or *)")
    private String allowedAppsDuringWork;

    @ApiModelProperty("Allowed apps outside work time (comma separated or *)")
    private String allowedAppsOutsideWork;

    @ApiModelProperty("Assigned device groups")
    private List<WorkTimePolicyDeviceGroup> deviceGroups;

    @ApiModelProperty("Customer ID")
    private int customerId;

    @ApiModelProperty("Global policy enabled flag")
    private Boolean enabled;

    // ===== NEW PRODUCTION FIELDS =====

    @ApiModelProperty("Policy priority (higher number = higher priority)")
    private Integer priority;

    @ApiModelProperty("Timezone (e.g. Europe/Bucharest, UTC)")
    private String timezone;

    @ApiModelProperty("Created at")
    private Date createdAt;

    @ApiModelProperty("Updated at")
    private Date updatedAt;

    @ApiModelProperty("Created by user ID")
    private Integer createdBy;

    public WorkTimePolicy() {
    }

    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
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

    public List<WorkTimePolicyDeviceGroup> getDeviceGroups() {
        return deviceGroups;
    }

    public void setDeviceGroups(List<WorkTimePolicyDeviceGroup> deviceGroups) {
        this.deviceGroups = deviceGroups;
    }

    public int getCustomerId() {
        return customerId;
    }

    public void setCustomerId(int customerId) {
        this.customerId = customerId;
    }

    public Integer getPriority() {
        return priority;
    }

    public void setPriority(Integer priority) {
        this.priority = priority;
    }

    public String getTimezone() {
        return timezone;
    }

    public void setTimezone(String timezone) {
        this.timezone = timezone;
    }

    public Date getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Date createdAt) {
        this.createdAt = createdAt;
    }

    public Date getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Date updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Integer getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(Integer createdBy) {
        this.createdBy = createdBy;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }
}