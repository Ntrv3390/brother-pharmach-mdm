package com.hmdm.plugins.worktime.persistence.postgres.dao.mapper;

import com.hmdm.plugins.worktime.model.WorkTimeDevicePolicy;
import com.hmdm.plugins.worktime.model.WorkTimeDeviceOverride;
import org.apache.ibatis.annotations.Param;
import java.util.List;

public interface PostgresWorkTimeMapper {

    WorkTimeDevicePolicy getDevicePolicy(@Param("customerId") int customerId, @Param("deviceId") int deviceId);

    List<WorkTimeDevicePolicy> getDevicePolicies(@Param("customerId") int customerId);

    void insertDevicePolicy(WorkTimeDevicePolicy policy);

    void updateDevicePolicy(WorkTimeDevicePolicy policy);

    // Device overrides
    List<WorkTimeDeviceOverride> getDeviceOverrides(@Param("customerId") int customerId);

    List<WorkTimeDeviceOverride> getDeviceOverridesForDevice(@Param("customerId") int customerId, @Param("deviceId") int deviceId);

    WorkTimeDeviceOverride getDeviceOverride(@Param("customerId") int customerId, @Param("deviceId") int deviceId);

    WorkTimeDeviceOverride getDeviceOverrideById(@Param("customerId") int customerId, @Param("id") int id);

    void insertDeviceOverride(WorkTimeDeviceOverride override);

    void updateDeviceOverride(WorkTimeDeviceOverride override);

    void deleteDeviceOverride(@Param("customerId") int customerId, @Param("deviceId") int deviceId);

    void deleteDeviceOverrideById(@Param("customerId") int customerId, @Param("id") int id);

    void markExceptionStartPushSent(@Param("customerId") int customerId, @Param("deviceId") int deviceId);

    void markExceptionEndPushSent(@Param("customerId") int customerId, @Param("deviceId") int deviceId);

    void markExceptionStartPushSentById(@Param("id") int id);

    void markExceptionEndPushSentById(@Param("id") int id);

    // Get all device overrides across all customers (for cleanup task)
    List<WorkTimeDeviceOverride> getAllDeviceOverrides();
}
