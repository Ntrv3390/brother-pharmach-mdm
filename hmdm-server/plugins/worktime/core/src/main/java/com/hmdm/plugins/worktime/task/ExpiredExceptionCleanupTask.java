package com.hmdm.plugins.worktime.task;

import com.hmdm.plugins.worktime.model.WorkTimeDeviceOverride;
import com.hmdm.plugins.worktime.persistence.WorkTimeDAO;
import com.hmdm.notification.PushService;
import com.hmdm.notification.persistence.domain.PushMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
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
    private static final ZoneId WORKTIME_ZONE = ZoneId.of("Asia/Kolkata");
    
    private final WorkTimeDAO workTimeDAO;
    private final PushService pushService;

    @Inject
    public ExpiredExceptionCleanupTask(WorkTimeDAO workTimeDAO, PushService pushService) {
        this.workTimeDAO = workTimeDAO;
        this.pushService = pushService;
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
                    workTimeDAO.markExceptionStartPushSent(override.getCustomerId(), override.getDeviceId());
                    startBoundaryPushCount++;
                    log.info("Sent persisted start boundary push for device {} in customer {}",
                            override.getDeviceId(), override.getCustomerId());
                }

                if (shouldSendEndBoundaryPush(override, now)) {
                    sendConfigUpdated(override.getDeviceId());
                    workTimeDAO.markExceptionEndPushSent(override.getCustomerId(), override.getDeviceId());
                    endBoundaryPushCount++;
                    log.info("Sent persisted end boundary push for device {} in customer {}",
                            override.getDeviceId(), override.getCustomerId());
                }

                if (isExpired(override, now)) {
                    log.info("Deleting expired exception for device {} in customer {}", 
                             override.getDeviceId(), override.getCustomerId());
                    workTimeDAO.deleteDeviceOverride(override.getCustomerId(), override.getDeviceId());
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

        LocalDateTime start = override.getStartDateTime().toLocalDateTime();
        LocalDateTime end = override.getEndDateTime().toLocalDateTime();
        return (!now.isBefore(start)) && (!now.isAfter(end));
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
