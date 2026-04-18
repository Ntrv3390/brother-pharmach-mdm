package com.hmdm.plugins.smslog.persistence;

import com.hmdm.plugins.smslog.model.SmsLogRecord;
import com.hmdm.plugins.smslog.model.SmsLogSettings;

import java.util.List;

/**
 * DAO interface for SMS log operations
 */
public interface SmsLogDAO {

    /**
    * Insert a new SMS log record
     */
    void insertSmsLogRecord(SmsLogRecord record);

    /**
    * Insert multiple SMS log records in batch
     */
    void insertSmsLogRecordsBatch(List<SmsLogRecord> records);

    /**
    * Get SMS logs for a specific device
     */
    List<SmsLogRecord> getSmsLogsByDevice(int deviceId, int customerId);

    /**
    * Get SMS logs for a device with pagination
     */
    List<SmsLogRecord> getSmsLogsByDevicePaged(int deviceId, int customerId, int limit, int offset);

    /**
    * Get SMS logs for a device with pagination and optional server-side filtering
    * @param messageType nullable - filter by message type (1=incoming,2=outgoing)
    * @param simSlot nullable - filter by SIM slot (1/2)
    * @param search nullable - filter by phone number or contact name (case-insensitive LIKE)
     */
    List<SmsLogRecord> getSmsLogsByDevicePagedFiltered(int deviceId, int customerId,
                                              Integer messageType, Integer simSlot, String search,
                                                         int limit, int offset);

    /**
    * Get total count of SMS logs for a device
     */
    int getSmsLogsCountByDevice(int deviceId, int customerId);

    /**
    * Get count with optional filters (matches getSmsLogsByDevicePagedFiltered)
     */
    int getSmsLogsCountByDeviceFiltered(int deviceId, int customerId, Integer messageType, Integer simSlot, String search);

    /**
    * Delete old SMS logs based on retention policy
     */
    int deleteOldSmsLogs(int customerId, int retentionDays);

    /**
    * Delete all SMS logs for a device
     */
    int deleteSmsLogsByDevice(int deviceId, int customerId);

    /**
     * Get plugin settings for a customer
     */
    SmsLogSettings getSettings(int customerId);

    /**
     * Save plugin settings
     */
    void saveSettings(SmsLogSettings settings);
}
