package com.hmdm.plugins.worktime.persistence;

import com.hmdm.plugins.worktime.model.WorkTimeDevicePolicy;
import com.hmdm.plugins.worktime.model.WorkTimeDeviceOverride;
import com.hmdm.plugins.worktime.model.WorkTimeGeneralHoliday;

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

    List<WorkTimeDeviceOverride> findOverlappingExceptions(int customerId, int deviceId,
                                                           java.sql.Timestamp startDateTime,
                                                           java.sql.Timestamp endDateTime);

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

    // ------------------------------------------------------------------
    // General (organization-wide) holidays
    // ------------------------------------------------------------------

    List<WorkTimeGeneralHoliday> getHolidays(int customerId);

    WorkTimeGeneralHoliday getHolidayById(int customerId, int id);

    /** Holidays of the customer that are active on the given date (start_date &lt;= date &lt;= end_date). */
    List<WorkTimeGeneralHoliday> getActiveHolidays(int customerId, java.sql.Date date);

    void insertHoliday(WorkTimeGeneralHoliday holiday);

    void updateHoliday(WorkTimeGeneralHoliday holiday);

    void deleteHolidayById(int customerId, int id);

    /** All holidays across all customers whose end has not passed by more than one day (for boundary/cleanup task). */
    List<WorkTimeGeneralHoliday> getAllHolidaysForMaintenance();

    void markHolidayStartPushSent(int id);

    void markHolidayEndPushSent(int id);

    void deleteHolidayByIdAnyCustomer(int id);
}

