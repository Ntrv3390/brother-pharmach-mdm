package com.hmdm.plugins.worktime.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;

import java.io.Serializable;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

@ApiModel(description = "Per-device work time override")
@JsonIgnoreProperties(ignoreUnknown = true)
public class WorkTimeDeviceOverride implements Serializable {

    private static final long serialVersionUID = 1L;

    @ApiModelProperty("Override ID")
    private Integer id;

    @ApiModelProperty("Customer ID")
    private int customerId;

    @ApiModelProperty("Device ID")
    private int deviceId;

    @ApiModelProperty("Device name")
    private String deviceName;

    @ApiModelProperty("Enabled flag")
    private boolean enabled = true;

    @ApiModelProperty("Start time HH:mm")
    private String startTime;

    @ApiModelProperty("End time HH:mm")
    private String endTime;

    @ApiModelProperty("Exception start datetime (local)")
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSX")
    private Timestamp startDateTime;

    @ApiModelProperty("Exception end datetime (local)")
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSX")
    private Timestamp endDateTime;

    @ApiModelProperty("Days of week (bitmask or csv)")
    private String daysOfWeek;

    @ApiModelProperty("Allowed apps during work (comma or '*')")
    private String allowedAppsDuringWork;

    @ApiModelProperty("Allowed apps outside work (comma or '*')")
    private String allowedAppsOutsideWork;

    @ApiModelProperty("Priority")
    private Integer priority;

    @ApiModelProperty("Created at")
    private Timestamp createdAt;

    @ApiModelProperty("Updated at")
    private Timestamp updatedAt;

    @ApiModelProperty("Whether exception start boundary push has been sent")
    private Boolean startBoundaryPushSent;

    @ApiModelProperty("Whether exception end boundary push has been sent")
    private Boolean endBoundaryPushSent;

    @ApiModelProperty("List of exceptions (transient, not persisted)")
    private java.util.List<java.util.Map<String, Object>> exceptions;

    public WorkTimeDeviceOverride() {
    }

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

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
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

    public Timestamp getStartDateTime() {
        return startDateTime;
    }

    public void setStartDateTime(Timestamp startDateTime) {
        this.startDateTime = startDateTime;
    }

    public void setStartDateTime(String startDateTime) {
        this.startDateTime = parseTimestamp(startDateTime);
    }

    public Timestamp getEndDateTime() {
        return endDateTime;
    }

    public void setEndDateTime(Timestamp endDateTime) {
        this.endDateTime = endDateTime;
    }

    public void setEndDateTime(String endDateTime) {
        this.endDateTime = parseTimestamp(endDateTime);
    }

    private Timestamp parseTimestamp(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        try {
            return Timestamp.from(OffsetDateTime.parse(value).toInstant());
        } catch (Exception ignored) {
            try {
                LocalDateTime ldt = LocalDateTime.parse(value, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
                return Timestamp.valueOf(ldt.atZone(ZoneId.systemDefault()).toLocalDateTime());
            } catch (Exception ignoredAgain) {
                return null;
            }
        }
    }

    public String getDaysOfWeek() {
        return daysOfWeek;
    }

    public void setDaysOfWeek(String daysOfWeek) {
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

    public Integer getPriority() {
        return priority;
    }

    public void setPriority(Integer priority) {
        this.priority = priority;
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

    public Boolean getStartBoundaryPushSent() {
        return startBoundaryPushSent;
    }

    public void setStartBoundaryPushSent(Boolean startBoundaryPushSent) {
        this.startBoundaryPushSent = startBoundaryPushSent;
    }

    public Boolean getEndBoundaryPushSent() {
        return endBoundaryPushSent;
    }

    public void setEndBoundaryPushSent(Boolean endBoundaryPushSent) {
        this.endBoundaryPushSent = endBoundaryPushSent;
    }

    public java.util.List<java.util.Map<String, Object>> getExceptions() {
        return exceptions;
    }

    public void setExceptions(java.util.List<java.util.Map<String, Object>> exceptions) {
        this.exceptions = exceptions;
    }
}
