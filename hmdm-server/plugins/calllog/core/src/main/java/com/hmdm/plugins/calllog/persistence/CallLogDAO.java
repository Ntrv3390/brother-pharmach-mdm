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
     * Get call logs for a device with pagination and optional server-side filtering
     * @param callType nullable – filter by call type (1=incoming,2=outgoing,etc.)
     * @param search   nullable – filter by phone number or contact name (case-insensitive LIKE)
     */
    List<CallLogRecord> getCallLogsByDevicePagedFiltered(int deviceId, int customerId,
                                                         Integer callType, String search,
                                                         int limit, int offset);

    /**
     * Get total count of call logs for a device
     */
    int getCallLogsCountByDevice(int deviceId, int customerId);

    /**
     * Get count with optional filters (matches getCallLogsByDevicePagedFiltered)
     */
    int getCallLogsCountByDeviceFiltered(int deviceId, int customerId, Integer callType, String search);

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
