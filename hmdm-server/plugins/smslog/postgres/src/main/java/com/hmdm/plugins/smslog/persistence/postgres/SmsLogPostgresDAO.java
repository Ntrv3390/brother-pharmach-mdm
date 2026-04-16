package com.hmdm.plugins.smslog.persistence.postgres;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.hmdm.plugins.smslog.model.SmsLogRecord;
import com.hmdm.plugins.smslog.model.SmsLogSettings;
import com.hmdm.plugins.smslog.persistence.SmsLogDAO;
import com.hmdm.plugins.smslog.persistence.postgres.mapper.SmsLogMapper;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * PostgreSQL implementation of SmsLogDAO
 */
@Singleton
public class SmsLogPostgresDAO implements SmsLogDAO {

    private final SmsLogMapper mapper;

    @Inject
    public SmsLogPostgresDAO(SmsLogMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void insertSmsLogRecord(SmsLogRecord record) {
        mapper.insertSmsLogRecord(record);
    }

    @Override
    public void insertSmsLogRecordsBatch(List<SmsLogRecord> records) {
        if (records != null && !records.isEmpty()) {
            mapper.insertSmsLogRecordsBatch(records);
        }
    }

    @Override
    public List<SmsLogRecord> getSmsLogsByDevice(int deviceId, int customerId) {
        return mapper.getSmsLogsByDevice(deviceId, customerId);
    }

    @Override
    public List<SmsLogRecord> getSmsLogsByDevicePaged(int deviceId, int customerId, int limit, int offset) {
        Map<String, Object> params = new HashMap<>();
        params.put("deviceId", deviceId);
        params.put("customerId", customerId);
        params.put("limit", limit);
        params.put("offset", offset);
        return mapper.getSmsLogsByDevicePaged(params);
    }

    @Override
    public List<SmsLogRecord> getSmsLogsByDevicePagedFiltered(int deviceId, int customerId,
                                                                 Integer messageType, Integer simSlot, String search,
                                                                 int limit, int offset) {
        Map<String, Object> params = new HashMap<>();
        params.put("deviceId", deviceId);
        params.put("customerId", customerId);
        params.put("messageType", messageType);
        params.put("simSlot", simSlot);
        params.put("search", (search != null && !search.trim().isEmpty()) ? search.trim() : null);
        params.put("limit", limit);
        params.put("offset", offset);
        return mapper.getSmsLogsByDevicePagedFiltered(params);
    }

    @Override
    public int getSmsLogsCountByDevice(int deviceId, int customerId) {
        return mapper.getSmsLogsCountByDevice(deviceId, customerId);
    }

    @Override
    public int getSmsLogsCountByDeviceFiltered(int deviceId, int customerId, Integer messageType, Integer simSlot, String search) {
        Map<String, Object> params = new HashMap<>();
        params.put("deviceId", deviceId);
        params.put("customerId", customerId);
        params.put("messageType", messageType);
        params.put("simSlot", simSlot);
        params.put("search", (search != null && !search.trim().isEmpty()) ? search.trim() : null);
        return mapper.getSmsLogsCountByDeviceFiltered(params);
    }

    @Override
    public int deleteOldSmsLogs(int customerId, int retentionDays) {
        if (retentionDays <= 0) {
            return 0; // Don't delete if retention is 0 or negative (keep forever)
        }
        return mapper.deleteOldSmsLogs(customerId, retentionDays);
    }

    @Override
    public int deleteSmsLogsByDevice(int deviceId, int customerId) {
        return mapper.deleteSmsLogsByDevice(deviceId, customerId);
    }

    @Override
    public SmsLogSettings getSettings(int customerId) {
        return mapper.getSettings(customerId);
    }

    @Override
    public void saveSettings(SmsLogSettings settings) {
        SmsLogSettings existing = mapper.getSettings(settings.getCustomerId());
        if (existing == null) {
            mapper.insertSettings(settings);
        } else {
            mapper.updateSettings(settings);
        }
    }
}
