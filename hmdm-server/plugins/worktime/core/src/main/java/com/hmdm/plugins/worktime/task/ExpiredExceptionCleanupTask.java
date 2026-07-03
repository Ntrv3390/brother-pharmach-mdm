package com.hmdm.plugins.worktime.task;

import com.hmdm.plugins.worktime.WorkTimeZone;
import com.hmdm.plugins.worktime.model.WorkTimeDeviceOverride;
import com.hmdm.plugins.worktime.model.WorkTimeGeneralHoliday;
import com.hmdm.plugins.worktime.persistence.WorkTimeDAO;
import com.hmdm.notification.PushService;
import com.hmdm.notification.persistence.domain.PushMessage;
import com.hmdm.persistence.UnsecureDAO;
import com.hmdm.persistence.domain.Device;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

/**
 * Task to clean up expired device exceptions.
 * Runs periodically to check for and remove expired exceptions.
 */
@Singleton
public class ExpiredExceptionCleanupTask {

    private static final Logger log = LoggerFactory.getLogger(ExpiredExceptionCleanupTask.class);
    private static final ZoneId WORKTIME_ZONE = WorkTimeZone.ZONE;
    
    private final WorkTimeDAO workTimeDAO;
    private final PushService pushService;
    private final UnsecureDAO unsecureDAO;

    @Inject
    public ExpiredExceptionCleanupTask(WorkTimeDAO workTimeDAO, PushService pushService, UnsecureDAO unsecureDAO) {
        this.workTimeDAO = workTimeDAO;
        this.pushService = pushService;
        this.unsecureDAO = unsecureDAO;
    }

    /**
     * Clean up all expired device exceptions across all customers.
     * This method is called periodically by the BackgroundTaskRunnerService.
     */
    public void cleanupExpiredExceptions() {
        try {
            log.debug("Running worktime exception boundary/cleanup task");
            LocalDateTime now = LocalDateTime.now(WORKTIME_ZONE);
            
            // Get all device overrides
            List<WorkTimeDeviceOverride> allOverrides = workTimeDAO.getAllDeviceOverrides();
            
            int cleanedCount = 0;
            int startBoundaryPushCount = 0;
            int endBoundaryPushCount = 0;
            for (WorkTimeDeviceOverride override : allOverrides) {
                if (!isExceptionOverride(override)) {
                    continue;
                }

                if (shouldSendStartBoundaryPush(override, now)) {
                    sendConfigUpdated(override.getDeviceId());
                    workTimeDAO.markExceptionStartPushSentById(override.getId(), override.getCustomerId());
                    startBoundaryPushCount++;
                    log.info("Sent persisted start boundary push for device {} in customer {}",
                            override.getDeviceId(), override.getCustomerId());
                }

                if (shouldSendEndBoundaryPush(override, now)) {
                    sendConfigUpdated(override.getDeviceId());
                    workTimeDAO.markExceptionEndPushSentById(override.getId(), override.getCustomerId());
                    endBoundaryPushCount++;
                    log.info("Sent persisted end boundary push for device {} in customer {}",
                            override.getDeviceId(), override.getCustomerId());
                }

                if (isExpired(override, now)) {
                    log.info("Deleting expired exception for device {} in customer {}", 
                             override.getDeviceId(), override.getCustomerId());
                    workTimeDAO.deleteDeviceOverrideById(override.getCustomerId(), override.getId());
                    sendConfigUpdated(override.getDeviceId());
                    cleanedCount++;
                }
            }
            
            if (cleanedCount > 0 || startBoundaryPushCount > 0 || endBoundaryPushCount > 0) {
                log.info("Worktime exception task summary: startPushes={}, endPushes={}, cleanedExpired={}",
                        startBoundaryPushCount, endBoundaryPushCount, cleanedCount);
            } else {
                log.debug("No boundary pushes or expired exceptions to process");
            }
        } catch (Exception e) {
            log.error("Error during expired exception cleanup", e);
        }

        // General (organization-wide) holiday boundaries and cleanup.
        cleanupExpiredHolidays();
    }

    /**
     * Handles general-holiday start boundaries and automatic removal of ended holidays.
     * <ul>
     *   <li>When a holiday first becomes active, pushes a config-updated notification to all of the
     *       customer's devices so the "outside working hours" behavior takes effect immediately.</li>
     *   <li>Once a holiday has fully ended (the day after its inclusive end date), pushes a final
     *       config-updated notification so normal enforcement resumes, then deletes the holiday.</li>
     * </ul>
     */
    private void cleanupExpiredHolidays() {
        try {
            LocalDate today = LocalDate.now(WORKTIME_ZONE);
            List<WorkTimeGeneralHoliday> holidays = workTimeDAO.getAllHolidaysForMaintenance();

            int startPushes = 0;
            int deleted = 0;
            for (WorkTimeGeneralHoliday holiday : holidays) {
                if (holiday.getStartDate() == null || holiday.getEndDate() == null) {
                    continue;
                }
                LocalDate start = holiday.getStartDate().toLocalDate();
                LocalDate end = holiday.getEndDate().toLocalDate();

                if (today.isAfter(end)) {
                    // Holiday fully ended: restore normal policy on all devices, then remove it.
                    pushToCustomerDevices(holiday.getCustomerId());
                    workTimeDAO.deleteHolidayByIdAnyCustomer(holiday.getId());
                    deleted++;
                    log.info("Deleted expired general holiday '{}' (id={}, customer={})",
                            holiday.getName(), holiday.getId(), holiday.getCustomerId());
                    continue;
                }

                if (!Boolean.TRUE.equals(holiday.getStartPushSent()) && !today.isBefore(start)) {
                    // Holiday just became active: push so devices pick up the holiday policy.
                    pushToCustomerDevices(holiday.getCustomerId());
                    workTimeDAO.markHolidayStartPushSent(holiday.getId());
                    startPushes++;
                    log.info("Sent start boundary push for general holiday '{}' (id={}, customer={})",
                            holiday.getName(), holiday.getId(), holiday.getCustomerId());
                }
            }

            if (startPushes > 0 || deleted > 0) {
                log.info("Worktime holiday task summary: startPushes={}, cleanedExpired={}", startPushes, deleted);
            }
        } catch (Exception e) {
            log.error("Error during expired holiday cleanup", e);
        }
    }

    private void pushToCustomerDevices(int customerId) {
        try {
            List<Device> devices = unsecureDAO.getAllCustomerDevices(customerId);
            for (Device device : devices) {
                sendConfigUpdated(device.getId());
            }
        } catch (Exception e) {
            log.error("Failed to push holiday update to devices of customer {}", customerId, e);
        }
    }

    private void sendConfigUpdated(int deviceId) {
        PushMessage message = new PushMessage();
        message.setDeviceId(deviceId);
        message.setMessageType(PushMessage.TYPE_CONFIG_UPDATED);
        pushService.send(message);
    }

    private boolean isExceptionOverride(WorkTimeDeviceOverride override) {
        return !override.isEnabled() && override.getStartDateTime() != null && override.getEndDateTime() != null;
    }

    private boolean shouldSendStartBoundaryPush(WorkTimeDeviceOverride override, LocalDateTime now) {
        if (Boolean.TRUE.equals(override.getStartBoundaryPushSent())) {
            return false;
        }
        // Send start push once we're at or past start time, even if the exception already expired.
        // The push triggers a policy fetch, so the device gets whatever is current (normal policy if expired).
        LocalDateTime start = override.getStartDateTime().toLocalDateTime();
        return !now.isBefore(start);
    }

    private boolean shouldSendEndBoundaryPush(WorkTimeDeviceOverride override, LocalDateTime now) {
        if (Boolean.TRUE.equals(override.getEndBoundaryPushSent())) {
            return false;
        }

        LocalDateTime end = override.getEndDateTime().toLocalDateTime();
        return now.isAfter(end);
    }

    /**
     * Check if an exception has expired.
     */
    private boolean isExpired(WorkTimeDeviceOverride override, LocalDateTime now) {
        // Only check exceptions (enabled=false with date range)
        if (!isExceptionOverride(override)) {
            return false;
        }
        
        LocalDateTime endTime = override.getEndDateTime().toLocalDateTime();
        return now.isAfter(endTime);
    }
}
