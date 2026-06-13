package com.hmdm.plugins.worktime.persistence;

import com.hmdm.plugins.worktime.model.WorkTimeDevicePolicy;
import com.hmdm.plugins.worktime.model.WorkTimeDeviceOverride;

import java.util.List;

public interface WorkTimeDAO {

    WorkTimeDevicePolicy getDevicePolicy(int customerId, int deviceId);

    List<WorkTimeDevicePolicy> getDevicePolicies(int customerId);

    void saveDevicePolicy(WorkTimeDevicePolicy policy);

    // Device override management (admin only)
    List<WorkTimeDeviceOverride> getDeviceOverrides(int customerId);

    List<WorkTimeDeviceOverride> getDeviceOverridesForDevice(int customerId, int deviceId);

    WorkTimeDeviceOverride getDeviceOverride(int customerId, int deviceId);

    WorkTimeDeviceOverride getDeviceOverrideById(int customerId, int id);

    void saveDeviceOverride(WorkTimeDeviceOverride policy);

    void deleteDeviceOverride(int customerId, int deviceId);

    void deleteDeviceOverrideById(int customerId, int id);

    void deleteDeviceOverridesForDevice(int customerId, int deviceId);

    void markExceptionStartPushSent(int customerId, int deviceId);

    void markExceptionEndPushSent(int customerId, int deviceId);

    void markExceptionStartPushSentById(int id, int customerId);

    void markExceptionEndPushSentById(int id, int customerId);

    // Get all device overrides across all customers (for cleanup task)
    List<WorkTimeDeviceOverride> getAllDeviceOverrides();
}

