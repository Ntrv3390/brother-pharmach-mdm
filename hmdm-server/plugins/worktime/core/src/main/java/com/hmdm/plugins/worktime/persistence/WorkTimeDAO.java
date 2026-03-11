package com.hmdm.plugins.worktime.persistence;

import com.hmdm.plugins.worktime.model.WorkTimePolicy;
import com.hmdm.plugins.worktime.model.WorkTimeDeviceOverride;

import java.util.List;

public interface WorkTimeDAO {

    WorkTimePolicy getGlobalPolicy(int customerId);

    void saveGlobalPolicy(WorkTimePolicy policy);

    // Device override management (admin only)
    List<WorkTimeDeviceOverride> getDeviceOverrides(int customerId);

    WorkTimeDeviceOverride getDeviceOverride(int customerId, int deviceId);

    void saveDeviceOverride(WorkTimeDeviceOverride policy);

    void deleteDeviceOverride(int customerId, int deviceId);

    void markExceptionStartPushSent(int customerId, int deviceId);

    void markExceptionEndPushSent(int customerId, int deviceId);

    // Get all device overrides across all customers (for cleanup task)
    List<WorkTimeDeviceOverride> getAllDeviceOverrides();
}

