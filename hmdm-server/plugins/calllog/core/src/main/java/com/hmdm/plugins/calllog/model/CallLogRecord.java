package com.hmdm.plugins.calllog.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;

import java.io.Serializable;

/**
 * A single call log record from an Android device
 */
@ApiModel(description = "A call log record from a device")
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CallLogRecord implements Serializable {

    @ApiModelProperty("Internal record ID")
    private Integer id;

    @ApiModelProperty("Device ID that generated this log")
    private int deviceId;

    @ApiModelProperty("Phone number involved in the call")
    private String phoneNumber;

    @ApiModelProperty("Contact name (if available)")
    private String contactName;

    @ApiModelProperty("Call type: 1=incoming, 2=outgoing, 3=missed, 4=rejected, 5=blocked")
    private int callType;

    @ApiModelProperty("Call duration in seconds")
    private long duration;

    @ApiModelProperty("Call timestamp (epoch milliseconds)")
    private long callTimestamp;

    @ApiModelProperty("Date the call occurred (readable format)")
    private String callDate;

    @ApiModelProperty("Timestamp when this record was received by server")
    private Long createTime;

    @ApiModelProperty("Customer ID")
    private int customerId;

    // Constructors
    public CallLogRecord() {
    }

    // Getters and Setters
    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public int getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(int deviceId) {
        this.deviceId = deviceId;
    }

    public String getPhoneNumber() {
        return phoneNumber;
    }

    public void setPhoneNumber(String phoneNumber) {
        this.phoneNumber = phoneNumber;
    }

    public String getContactName() {
        return contactName;
    }

    public void setContactName(String contactName) {
        this.contactName = contactName;
    }

    public int getCallType() {
        return callType;
    }

    public void setCallType(int callType) {
        this.callType = callType;
    }

    public long getDuration() {
        return duration;
    }

    public void setDuration(long duration) {
        this.duration = duration;
    }

    public long getCallTimestamp() {
        return callTimestamp;
    }

    public void setCallTimestamp(long callTimestamp) {
        this.callTimestamp = callTimestamp;
    }

    public String getCallDate() {
        return callDate;
    }

    public void setCallDate(String callDate) {
        this.callDate = callDate;
    }

    public Long getCreateTime() {
        return createTime;
    }

    public void setCreateTime(Long createTime) {
        this.createTime = createTime;
    }

    public int getCustomerId() {
        return customerId;
    }

    public void setCustomerId(int customerId) {
        this.customerId = customerId;
    }

    @Override
    public String toString() {
        return "CallLogRecord{" +
                "id=" + id +
                ", deviceId=" + deviceId +
                ", phoneNumber='" + phoneNumber + '\'' +
                ", contactName='" + contactName + '\'' +
                ", callType=" + callType +
                ", duration=" + duration +
                ", callTimestamp=" + callTimestamp +
                '}';
    }
}
