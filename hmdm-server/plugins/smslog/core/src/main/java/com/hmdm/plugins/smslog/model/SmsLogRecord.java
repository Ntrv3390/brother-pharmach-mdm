package com.hmdm.plugins.smslog.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;

import java.io.Serializable;

/**
 * A single SMS log record from an Android device
 */
@ApiModel(description = "An SMS log record from a device")
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SmsLogRecord implements Serializable {

    @ApiModelProperty("Internal record ID")
    private Integer id;

    @ApiModelProperty("Device ID that generated this log")
    private int deviceId;

    @ApiModelProperty("Phone number involved in the SMS")
    private String phoneNumber;

    @ApiModelProperty("Contact name (if available)")
    private String contactName;

    @ApiModelProperty("Actual SMS message text")
    private String message;

    @ApiModelProperty("Message type: 1=incoming, 2=outgoing")
    private int messageType;

    @ApiModelProperty("SIM slot used for SMS (1 or 2)")
    private Integer simSlot;

    @ApiModelProperty("SMS timestamp (epoch milliseconds)")
    private long smsTimestamp;

    @ApiModelProperty("Date the SMS occurred (readable format)")
    private String smsDate;

    @ApiModelProperty("Timestamp when this record was received by server")
    private Long createTime;

    @ApiModelProperty("Customer ID")
    private int customerId;

    // Constructors
    public SmsLogRecord() {
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

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public int getMessageType() {
        return messageType;
    }

    public void setMessageType(int messageType) {
        this.messageType = messageType;
    }

    public Integer getSimSlot() {
        return simSlot;
    }

    public void setSimSlot(Integer simSlot) {
        this.simSlot = simSlot;
    }

    public long getSmsTimestamp() {
        return smsTimestamp;
    }

    public void setSmsTimestamp(long smsTimestamp) {
        this.smsTimestamp = smsTimestamp;
    }

    public String getSmsDate() {
        return smsDate;
    }

    public void setSmsDate(String smsDate) {
        this.smsDate = smsDate;
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
        return "SmsLogRecord{" +
                "id=" + id +
                ", deviceId=" + deviceId +
                ", phoneNumber='" + phoneNumber + '\'' +
                ", contactName='" + contactName + '\'' +
                ", message='" + message + '\'' +
            ", messageType=" + messageType +
            ", simSlot=" + simSlot +
            ", smsTimestamp=" + smsTimestamp +
                '}';
    }
}
