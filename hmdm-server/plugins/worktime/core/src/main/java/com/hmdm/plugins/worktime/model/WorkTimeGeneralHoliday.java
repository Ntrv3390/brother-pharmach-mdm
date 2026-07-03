package com.hmdm.plugins.worktime.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;

import java.io.Serializable;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;

/**
 * <p>Organization-wide (general) holiday.</p>
 * <p>When the current date falls within an active general holiday (inclusive), all devices of the
 * customer behave as if they are outside working hours: applications configured for "After Work"
 * and "24 Hours" become available all day. Normal work-time enforcement resumes automatically once
 * the holiday ends.</p>
 *
 * @author hmdm
 */
@ApiModel(description = "Organization-wide work time holiday")
@JsonIgnoreProperties(ignoreUnknown = true)
public class WorkTimeGeneralHoliday implements Serializable {

    private static final long serialVersionUID = 1L;

    @ApiModelProperty("Holiday ID")
    private Integer id;

    @ApiModelProperty("Customer ID")
    private int customerId;

    @ApiModelProperty("Holiday name")
    private String name;

    @ApiModelProperty("Inclusive start date (yyyy-MM-dd)")
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd", timezone = "UTC")
    private Date startDate;

    @ApiModelProperty("Inclusive end date (yyyy-MM-dd)")
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd", timezone = "UTC")
    private Date endDate;

    @ApiModelProperty("Whether the start boundary push has been sent")
    private Boolean startPushSent;

    @ApiModelProperty("Whether the end boundary push has been sent")
    private Boolean endPushSent;

    @ApiModelProperty("Created at")
    private Timestamp createdAt;

    @ApiModelProperty("Updated at")
    private Timestamp updatedAt;

    public WorkTimeGeneralHoliday() {
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

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Date getStartDate() {
        return startDate;
    }

    public void setStartDate(Date startDate) {
        this.startDate = startDate;
    }

    public void setStartDate(String startDate) {
        this.startDate = parseDate(startDate);
    }

    public Date getEndDate() {
        return endDate;
    }

    public void setEndDate(Date endDate) {
        this.endDate = endDate;
    }

    public void setEndDate(String endDate) {
        this.endDate = parseDate(endDate);
    }

    private static Date parseDate(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        try {
            return Date.valueOf(LocalDate.parse(value.trim()));
        } catch (Exception ignored) {
            return null;
        }
    }

    public Boolean getStartPushSent() {
        return startPushSent;
    }

    public void setStartPushSent(Boolean startPushSent) {
        this.startPushSent = startPushSent;
    }

    public Boolean getEndPushSent() {
        return endPushSent;
    }

    public void setEndPushSent(Boolean endPushSent) {
        this.endPushSent = endPushSent;
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
