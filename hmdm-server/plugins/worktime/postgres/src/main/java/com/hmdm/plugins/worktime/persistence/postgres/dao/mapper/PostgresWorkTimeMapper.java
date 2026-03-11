package com.hmdm.plugins.worktime.persistence.postgres.dao.mapper;

import com.hmdm.plugins.worktime.model.WorkTimePolicy;
import com.hmdm.plugins.worktime.model.WorkTimeDeviceOverride;
import org.apache.ibatis.annotations.Param;
import java.util.List;

public interface PostgresWorkTimeMapper {

    WorkTimePolicy getGlobalPolicy(@Param("customerId") int customerId);

    void insertGlobalPolicy(WorkTimePolicy policy);

    void updateGlobalPolicy(WorkTimePolicy policy);

    // Device overrides
    List<WorkTimeDeviceOverride> getDeviceOverrides(@Param("customerId") int customerId);

    WorkTimeDeviceOverride getDeviceOverride(@Param("customerId") int customerId, @Param("deviceId") int deviceId);

    void insertDeviceOverride(WorkTimeDeviceOverride override);

    void updateDeviceOverride(WorkTimeDeviceOverride override);

    void deleteDeviceOverride(@Param("customerId") int customerId, @Param("deviceId") int deviceId);

    void markExceptionStartPushSent(@Param("customerId") int customerId, @Param("deviceId") int deviceId);

    void markExceptionEndPushSent(@Param("customerId") int customerId, @Param("deviceId") int deviceId);

    // Get all device overrides across all customers (for cleanup task)
    List<WorkTimeDeviceOverride> getAllDeviceOverrides();
}
