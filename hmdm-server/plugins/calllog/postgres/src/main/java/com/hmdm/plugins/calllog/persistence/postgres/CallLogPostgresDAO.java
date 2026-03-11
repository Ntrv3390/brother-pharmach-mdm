package com.hmdm.plugins.calllog.persistence.postgres;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.hmdm.plugins.calllog.model.CallLogRecord;
import com.hmdm.plugins.calllog.model.CallLogSettings;
import com.hmdm.plugins.calllog.persistence.CallLogDAO;
import com.hmdm.plugins.calllog.persistence.postgres.mapper.CallLogMapper;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * PostgreSQL implementation of CallLogDAO
 */
@Singleton
public class CallLogPostgresDAO implements CallLogDAO {

    private final CallLogMapper mapper;

    @Inject
    public CallLogPostgresDAO(CallLogMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void insertCallLogRecord(CallLogRecord record) {
        mapper.insertCallLogRecord(record);
    }

    @Override
    public void insertCallLogRecordsBatch(List<CallLogRecord> records) {
        if (records != null && !records.isEmpty()) {
            mapper.insertCallLogRecordsBatch(records);
        }
    }

    @Override
    public List<CallLogRecord> getCallLogsByDevice(int deviceId, int customerId) {
        return mapper.getCallLogsByDevice(deviceId, customerId);
    }

    @Override
    public List<CallLogRecord> getCallLogsByDevicePaged(int deviceId, int customerId, int limit, int offset) {
        Map<String, Object> params = new HashMap<>();
        params.put("deviceId", deviceId);
        params.put("customerId", customerId);
        params.put("limit", limit);
        params.put("offset", offset);
        return mapper.getCallLogsByDevicePaged(params);
    }

    @Override
    public int getCallLogsCountByDevice(int deviceId, int customerId) {
        return mapper.getCallLogsCountByDevice(deviceId, customerId);
    }

    @Override
    public int deleteOldCallLogs(int customerId, int retentionDays) {
        if (retentionDays <= 0) {
            return 0; // Don't delete if retention is 0 or negative (keep forever)
        }
        return mapper.deleteOldCallLogs(customerId, retentionDays);
    }

    @Override
    public int deleteCallLogsByDevice(int deviceId, int customerId) {
        return mapper.deleteCallLogsByDevice(deviceId, customerId);
    }

    @Override
    public CallLogSettings getSettings(int customerId) {
        return mapper.getSettings(customerId);
    }

    @Override
    public void saveSettings(CallLogSettings settings) {
        CallLogSettings existing = mapper.getSettings(settings.getCustomerId());
        if (existing == null) {
            mapper.insertSettings(settings);
        } else {
            mapper.updateSettings(settings);
        }
    }
}
