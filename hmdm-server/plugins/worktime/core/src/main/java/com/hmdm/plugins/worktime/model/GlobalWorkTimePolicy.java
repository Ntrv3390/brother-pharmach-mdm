package com.hmdm.plugins.worktime.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class GlobalWorkTimePolicy {

    private Integer id;
    private String startTime;
    private String endTime;
    private Integer daysOfWeek;
    private String allowedAppsDuringWork;
    private String allowedAppsOutsideWork;
    private Boolean enabled;
    private Integer customerId;

    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
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

    public Integer getCustomerId() {
        return customerId;
    }

    public void setCustomerId(Integer customerId) {
        this.customerId = customerId;
    }
}
