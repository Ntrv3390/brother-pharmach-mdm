package com.hmdm.plugins.worktime.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdm.persistence.UnsecureDAO;
import com.hmdm.persistence.domain.Device;
import com.hmdm.rest.json.SyncResponseInt;
import com.hmdm.rest.json.SyncResponseHook;
import com.hmdm.plugins.worktime.service.EffectiveWorkTimePolicy;
import com.hmdm.plugins.worktime.service.WorkTimeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * <p>
 * Sync response hook for WorkTime plugin.
 * </p>
 * <p>
 * Automatically delivers worktime policy to Android devices during
 * configuration sync.
 * </p>
 *
 * @author hmdm
 */
@Singleton
public class WorkTimeSyncResponseHook implements SyncResponseHook {

    private static final Logger log = LoggerFactory.getLogger(WorkTimeSyncResponseHook.class);
    private static final String WORKTIME_CUSTOM_FIELD = "custom1"; // Using custom1 field for worktime data
    private static final ZoneId WORKTIME_ZONE = ZoneId.of("Asia/Kolkata");

    private final UnsecureDAO unsecureDAO;
    private final WorkTimeService workTimeService;
    private final ObjectMapper objectMapper;

    @Inject
    public WorkTimeSyncResponseHook(UnsecureDAO unsecureDAO,
            WorkTimeService workTimeService) {
        this.unsecureDAO = unsecureDAO;
        this.workTimeService = workTimeService;
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public SyncResponseInt handle(int deviceId, SyncResponseInt original) {
        log.debug("WorkTimeSyncResponseHook: Handling sync for device {}", deviceId);
        try {
            // Get device information
            Device device = this.unsecureDAO.getDeviceById(deviceId);
            if (device == null) {
                log.warn("Device {} not found, skipping worktime policy injection", deviceId);
                return original;
            }

            int customerId = device.getCustomerId();

            // Resolve effective worktime policy for this device
            EffectiveWorkTimePolicy policy = workTimeService.resolveEffectivePolicy(
                    customerId,
                    deviceId,
                    LocalDateTime.now(WORKTIME_ZONE));

            // Create wrapper object with metadata
            WorkTimePolicyWrapper wrapper = new WorkTimePolicyWrapper(policy);
            String wrapperJson = objectMapper.writeValueAsString(wrapper);

            // Use reflection to call setCustom1() method since plugins don't have
            // compile-time access to SyncResponse class (only the interface)
            try {
                Method setCustom1 = original.getClass().getMethod("setCustom1", String.class);
                setCustom1.invoke(original, wrapperJson);

                log.debug("Injected worktime policy for device {}: enabled={}, customerId={}",
                        deviceId, policy.isEnforcementEnabled(), customerId);
            } catch (NoSuchMethodException e) {
                log.warn("SyncResponse does not have setCustom1 method, cannot inject worktime policy");
            }

            return original;

        } catch (Exception e) {
            log.error("Error adding worktime policy to sync response for device {}", deviceId, e);
            return original; // Return original response if error occurs
        }
    }

    /**
     * Wrapper class for worktime policy with metadata for Android client.
     */
    public static class WorkTimePolicyWrapper {
        private String pluginId = "worktime";
        private long timestamp = System.currentTimeMillis();
        private EffectiveWorkTimePolicy policy;

        public WorkTimePolicyWrapper() {
        }

        public WorkTimePolicyWrapper(EffectiveWorkTimePolicy policy) {
            this.policy = policy;
        }

        public String getPluginId() {
            return pluginId;
        }

        public void setPluginId(String pluginId) {
            this.pluginId = pluginId;
        }

        public long getTimestamp() {
            return timestamp;
        }

        public void setTimestamp(long timestamp) {
            this.timestamp = timestamp;
        }

        public EffectiveWorkTimePolicy getPolicy() {
            return policy;
        }

        public void setPolicy(EffectiveWorkTimePolicy policy) {
            this.policy = policy;
        }
    }
}
