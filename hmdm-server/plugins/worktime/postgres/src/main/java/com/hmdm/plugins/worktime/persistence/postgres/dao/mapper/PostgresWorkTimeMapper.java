package com.hmdm.plugins.worktime.persistence.postgres.dao.mapper;

import com.hmdm.plugins.worktime.model.WorkTimeDevicePolicy;
import com.hmdm.plugins.worktime.model.WorkTimeDeviceOverride;
import com.hmdm.plugins.worktime.model.WorkTimeGeneralHoliday;
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

    List<WorkTimeDeviceOverride> findOverlappingExceptions(@Param("customerId") int customerId,
                                                           @Param("deviceId") int deviceId,
                                                           @Param("startDateTime") java.sql.Timestamp startDateTime,
                                                           @Param("endDateTime") java.sql.Timestamp endDateTime);

    void insertDeviceOverride(WorkTimeDeviceOverride override);

    void updateDeviceOverride(WorkTimeDeviceOverride override);

    void deleteDeviceOverride(@Param("customerId") int customerId, @Param("deviceId") int deviceId);

    void deleteDeviceOverrideById(@Param("customerId") int customerId, @Param("id") int id);

    void markExceptionStartPushSent(@Param("customerId") int customerId, @Param("deviceId") int deviceId);

    void markExceptionEndPushSent(@Param("customerId") int customerId, @Param("deviceId") int deviceId);

    void markExceptionStartPushSentById(@Param("id") int id, @Param("customerId") int customerId);

    void markExceptionEndPushSentById(@Param("id") int id, @Param("customerId") int customerId);

    // Get all device overrides across all customers (for cleanup task)
    List<WorkTimeDeviceOverride> getAllDeviceOverrides();

    // General holidays
    List<WorkTimeGeneralHoliday> getHolidays(@Param("customerId") int customerId);

    WorkTimeGeneralHoliday getHolidayById(@Param("customerId") int customerId, @Param("id") int id);

    List<WorkTimeGeneralHoliday> getActiveHolidays(@Param("customerId") int customerId,
                                                   @Param("date") java.sql.Date date);

    void insertHoliday(WorkTimeGeneralHoliday holiday);

    void updateHoliday(WorkTimeGeneralHoliday holiday);

    void deleteHolidayById(@Param("customerId") int customerId, @Param("id") int id);

    List<WorkTimeGeneralHoliday> getAllHolidaysForMaintenance();

    void markHolidayStartPushSent(@Param("id") int id);

    void markHolidayEndPushSent(@Param("id") int id);

    void deleteHolidayByIdAnyCustomer(@Param("id") int id);
}
