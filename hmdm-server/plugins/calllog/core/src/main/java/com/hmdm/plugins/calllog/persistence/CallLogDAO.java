package com.hmdm.plugins.calllog.persistence;

import com.hmdm.plugins.calllog.model.CallLogRecord;
import com.hmdm.plugins.calllog.model.CallLogSettings;

import java.util.List;

/**
 * DAO interface for call log operations
 */
public interface CallLogDAO {

    /**
     * Insert a new call log record
     */
    void insertCallLogRecord(CallLogRecord record);

    /**
     * Insert multiple call log records in batch
     */
    void insertCallLogRecordsBatch(List<CallLogRecord> records);

    /**
     * Get call logs for a specific device
     */
    List<CallLogRecord> getCallLogsByDevice(int deviceId, int customerId);

    /**
     * Get call logs for a device with pagination
     */
    List<CallLogRecord> getCallLogsByDevicePaged(int deviceId, int customerId, int limit, int offset);

    /**
     * Get total count of call logs for a device
     */
    int getCallLogsCountByDevice(int deviceId, int customerId);

    /**
     * Delete old call logs based on retention policy
     */
    int deleteOldCallLogs(int customerId, int retentionDays);

    /**
     * Delete all call logs for a device
     */
    int deleteCallLogsByDevice(int deviceId, int customerId);

    /**
     * Get plugin settings for a customer
     */
    CallLogSettings getSettings(int customerId);

    /**
     * Save plugin settings
     */
    void saveSettings(CallLogSettings settings);
}
