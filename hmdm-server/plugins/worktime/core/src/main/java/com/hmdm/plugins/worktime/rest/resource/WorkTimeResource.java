package com.hmdm.plugins.worktime.rest.resource;

import javax.inject.Inject;
import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.hmdm.plugins.worktime.model.WorkTimeDevicePolicy;
import com.hmdm.plugins.worktime.model.WorkTimeDeviceOverride;
import com.hmdm.plugins.worktime.persistence.WorkTimeDAO;
import com.hmdm.persistence.domain.DeviceApplication;
import com.hmdm.persistence.UserDAO;
import com.hmdm.persistence.DeviceDAO;
import com.hmdm.persistence.domain.User;
import com.hmdm.persistence.domain.Device;
import com.hmdm.notification.PushService;
import com.hmdm.notification.persistence.domain.PushMessage;
import com.hmdm.rest.json.Response;
import com.hmdm.security.SecurityContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Path("/plugins/worktime/private")
@Produces(MediaType.APPLICATION_JSON)
public class WorkTimeResource {

    private static final Logger log = LoggerFactory.getLogger(WorkTimeResource.class);
    private static final ZoneId WORKTIME_ZONE = ZoneId.of("Asia/Kolkata");
    private static final ScheduledExecutorService PUSH_RETRY_EXECUTOR = Executors.newSingleThreadScheduledExecutor();

    private final WorkTimeDAO workTimeDAO;
    private final UserDAO userDAO;
    private final DeviceDAO deviceDAO;
    private final PushService pushService;

    @Inject
    public WorkTimeResource(WorkTimeDAO workTimeDAO, UserDAO userDAO, DeviceDAO deviceDAO, PushService pushService) {
        this.workTimeDAO = workTimeDAO;
        this.userDAO = userDAO;
        this.deviceDAO = deviceDAO;
        this.pushService = pushService;
    }

    private int getCustomerId() {
        return SecurityContext.get()
                .getCurrentUser()
                .orElseThrow(() -> new WebApplicationException("Unauthorized", 401))
                .getCustomerId();
    }

    private Device getScopedDeviceOrNull(int deviceId) {
        try {
            return this.deviceDAO.getDeviceById(deviceId);
        } catch (Exception e) {
            return null;
        }
    }

    private boolean isValidTime(String value) {
        if (value == null) {
            return false;
        }
        try {
            LocalTime.parse(value, DateTimeFormatter.ofPattern("HH:mm"));
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    private void normalizeDevicePolicy(WorkTimeDevicePolicy policy) {
        if (policy.getDaysOfWeek() == null) {
            policy.setDaysOfWeek(31);
        }
        if (policy.getAllowedAppsDuringWork() == null) {
            policy.setAllowedAppsDuringWork("");
        }
        if (policy.getAllowedAppsOutsideWork() == null) {
            policy.setAllowedAppsOutsideWork("*");
        }
        if (policy.getEnabled() == null) {
            policy.setEnabled(true);
        }
    }

    private boolean isExceptionOverride(WorkTimeDeviceOverride override) {
        return override != null
                && !override.isEnabled()
                && override.getStartDateTime() != null
                && override.getEndDateTime() != null;
    }

    private WorkTimeDeviceOverride createDefaultDeviceOverride(Device device, int customerId) {
        WorkTimeDeviceOverride override = new WorkTimeDeviceOverride();
        override.setCustomerId(customerId);
        override.setDeviceId(device.getId());
        override.setDeviceName(device.getNumber());
        override.setEnabled(true);
        override.setExceptions(new ArrayList<>());
        return override;
    }

    private WorkTimeDeviceOverride copyDeviceOverride(WorkTimeDeviceOverride source, Device device) {
        WorkTimeDeviceOverride target = new WorkTimeDeviceOverride();
        target.setId(source.getId());
        target.setCustomerId(source.getCustomerId());
        target.setDeviceId(source.getDeviceId());
        target.setDeviceName(device.getNumber());
        target.setEnabled(source.isEnabled());
        target.setStartTime(source.getStartTime());
        target.setEndTime(source.getEndTime());
        target.setStartDateTime(source.getStartDateTime());
        target.setEndDateTime(source.getEndDateTime());
        target.setDaysOfWeek(source.getDaysOfWeek());
        target.setAllowedAppsDuringWork(source.getAllowedAppsDuringWork());
        target.setAllowedAppsOutsideWork(source.getAllowedAppsOutsideWork());
        target.setPriority(source.getPriority());
        target.setCreatedAt(source.getCreatedAt());
        target.setUpdatedAt(source.getUpdatedAt());
        target.setStartBoundaryPushSent(source.getStartBoundaryPushSent());
        target.setEndBoundaryPushSent(source.getEndBoundaryPushSent());
        target.setExceptions(new ArrayList<>());
        return target;
    }

    private Map<String, Object> buildExceptionMap(WorkTimeDeviceOverride override,
                                                  LocalDateTime now,
                                                  DateTimeFormatter dateFmt,
                                                  DateTimeFormatter timeFmt) {
        Map<String, Object> ex = new HashMap<>();
        ex.put("id", override.getId());
        ex.put("dateFrom", override.getStartDateTime().toLocalDateTime().toLocalDate().format(dateFmt));
        ex.put("dateTo", override.getEndDateTime().toLocalDateTime().toLocalDate().format(dateFmt));
        ex.put("timeFrom", override.getStartDateTime().toLocalDateTime().toLocalTime().format(timeFmt));
        ex.put("timeTo", override.getEndDateTime().toLocalDateTime().toLocalTime().format(timeFmt));
        ex.put("active", !now.isBefore(override.getStartDateTime().toLocalDateTime())
                && !now.isAfter(override.getEndDateTime().toLocalDateTime()));
        ex.put("startDateTime", override.getStartDateTime());
        ex.put("endDateTime", override.getEndDateTime());
        return ex;
    }

    private void sendConfigUpdatedTwice(int deviceId) {
        // Reliability fallback: some devices may miss a single push notification.
        // Send once immediately and once with a short delay.
        PushMessage immediate = new PushMessage();
        immediate.setDeviceId(deviceId);
        immediate.setMessageType(PushMessage.TYPE_CONFIG_UPDATED);
        pushService.send(immediate);
        PUSH_RETRY_EXECUTOR.schedule(() -> {
            try {
                PushMessage delayed = new PushMessage();
                delayed.setDeviceId(deviceId);
                delayed.setMessageType(PushMessage.TYPE_CONFIG_UPDATED);
                pushService.send(delayed);
            } catch (Exception e) {
                log.warn("Failed to send delayed config update push to device {}", deviceId, e);
            }
        }, 2, TimeUnit.SECONDS);
    }

    // --- Per-device policy endpoints ---
    @GET
    @Path("/policy")
    public Response getPolicyByQuery(@QueryParam("deviceId") Integer deviceId) {
        if (deviceId == null || deviceId <= 0) {
            return Response.ERROR("deviceId query parameter is required");
        }
        return getPolicy(deviceId);
    }

    @GET
    @Path("/policy/{deviceId}")
    public Response getPolicy(@PathParam("deviceId") int deviceId) {
        // Check authentication
        User current = SecurityContext.get().getCurrentUser().orElse(null);
        if (current == null) {
            log.error("Unauthorized attempt to access worktime device policy - not authenticated");
            return Response.PERMISSION_DENIED();
        }

        if (!SecurityContext.get().isSuperAdmin() && !this.userDAO.isOrgAdmin(current)) {
            log.warn("User {} is not allowed to get policy: must be admin", current.getLogin());
            return Response.PERMISSION_DENIED();
        }

        if (deviceId <= 0) {
            return Response.ERROR("Invalid device ID");
        }

        if (getScopedDeviceOrNull(deviceId) == null) {
            return Response.DEVICE_NOT_FOUND_ERROR();
        }

        int customerId = getCustomerId();
        WorkTimeDevicePolicy policy = workTimeDAO.getDevicePolicy(customerId, deviceId);

        if (policy == null) {
            policy = new WorkTimeDevicePolicy();
            policy.setCustomerId(customerId);
            policy.setDeviceId(deviceId);
            policy.setStartTime("09:00");
            policy.setEndTime("17:00");
            policy.setDaysOfWeek(31);
            policy.setAllowedAppsDuringWork("");
            policy.setAllowedAppsOutsideWork("*");
            policy.setEnabled(true);
        }

        return Response.OK(policy);
    }

    @GET
    @Path("/policies")
    public Response getPolicies() {
        User current = SecurityContext.get().getCurrentUser().orElse(null);
        if (current == null) {
            return Response.PERMISSION_DENIED();
        }
        if (!SecurityContext.get().isSuperAdmin() && !this.userDAO.isOrgAdmin(current)) {
            return Response.PERMISSION_DENIED();
        }

        int customerId = getCustomerId();
        return Response.OK(workTimeDAO.getDevicePolicies(customerId));
    }

    @POST
    @Path("/policy")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response savePolicy(WorkTimeDevicePolicy policy) {
        // Check if user is admin or has worktime permission
        User current = SecurityContext.get().getCurrentUser().orElse(null);
        if (current == null) {
            log.error("Unauthorized attempt to save worktime device policy - not authenticated");
            return Response.PERMISSION_DENIED();
        }

        if (!SecurityContext.get().isSuperAdmin() && !this.userDAO.isOrgAdmin(current)) {
            log.warn("User {} is not allowed to save policy: must be admin", current.getLogin());
            return Response.PERMISSION_DENIED();
        }

        if (policy == null) {
            return Response.ERROR("Policy payload is required");
        }
        if (policy.getDeviceId() <= 0) {
            return Response.ERROR("Invalid device ID");
        }

        if (getScopedDeviceOrNull(policy.getDeviceId()) == null) {
            return Response.DEVICE_NOT_FOUND_ERROR();
        }

        if (!isValidTime(policy.getStartTime()) || !isValidTime(policy.getEndTime())) {
            return Response.ERROR("Invalid time format, expected HH:mm");
        }
        if (policy.getDaysOfWeek() != null && (policy.getDaysOfWeek() < 0 || policy.getDaysOfWeek() > 127)) {
            return Response.ERROR("Invalid daysOfWeek bitmask");
        }

        int customerId = getCustomerId();
        policy.setCustomerId(customerId);
        normalizeDevicePolicy(policy);
        workTimeDAO.saveDevicePolicy(policy);

        // Notify target device about policy update
        sendConfigUpdatedTwice(policy.getDeviceId());

        log.info("Saved WorkTime device policy for customer {}, device {}",
                customerId, policy.getDeviceId());

        return Response.OK(policy);
    }

    // --- Device override endpoints (admin only) ---
    @GET
    @Path("/devices")
    public Response getDeviceOverrides() {
        User current = SecurityContext.get().getCurrentUser().orElse(null);
        if (current == null) {
            log.error("Unauthorized attempt to access device overrides - not authenticated");
            return Response.PERMISSION_DENIED();
        }

        if (!SecurityContext.get().isSuperAdmin() && !this.userDAO.isOrgAdmin(current)) {
            log.warn("User {} is not allowed to list overrides: must be admin", current.getLogin());
            return Response.PERMISSION_DENIED();
        }

        int customerId = getCustomerId();

        // Get all devices in the current customer's scope
        List<Device> allDevices = deviceDAO.getAllDevices();

        // Get overrides for those devices
        List<WorkTimeDeviceOverride> overrides = workTimeDAO.getDeviceOverrides(customerId);

        Map<Integer, List<WorkTimeDeviceOverride>> overridesByDevice = new HashMap<>();
        for (WorkTimeDeviceOverride override : overrides) {
            overridesByDevice.computeIfAbsent(override.getDeviceId(), ignored -> new ArrayList<>()).add(override);
        }

        // Combine devices with their overrides
        List<WorkTimeDeviceOverride> result = new java.util.ArrayList<>();
        DateTimeFormatter dateFmt = DateTimeFormatter.ISO_LOCAL_DATE;
        DateTimeFormatter timeFmt = DateTimeFormatter.ofPattern("HH:mm");
        LocalDateTime now = LocalDateTime.now(WORKTIME_ZONE);
        for (Device device : allDevices) {
            List<WorkTimeDeviceOverride> deviceOverrides = overridesByDevice.getOrDefault(device.getId(), Collections.emptyList());
            WorkTimeDeviceOverride summary = null;
            List<Map<String, Object>> exceptionList = new ArrayList<>();

            for (WorkTimeDeviceOverride override : deviceOverrides) {
                if (!isExceptionOverride(override)) {
                    continue;
                }

                LocalDateTime end = override.getEndDateTime().toLocalDateTime();
                if (now.isAfter(end)) {
                    workTimeDAO.deleteDeviceOverrideById(customerId, override.getId());
                    continue;
                }

                if (summary == null) {
                    summary = copyDeviceOverride(override, device);
                }
                exceptionList.add(buildExceptionMap(override, now, dateFmt, timeFmt));
            }

            if (summary == null) {
                summary = createDefaultDeviceOverride(device, customerId);
            }
            summary.setExceptions(exceptionList);
            result.add(summary);
        }

        return Response.OK(result);
    }

    @GET
    @Path("/device/{deviceId}/applications")
    public Response getDeviceInstalledApplications(@PathParam("deviceId") int deviceId) {
        User current = SecurityContext.get().getCurrentUser().orElse(null);
        if (current == null) {
            return Response.PERMISSION_DENIED();
        }
        if (!SecurityContext.get().isSuperAdmin() && !this.userDAO.isOrgAdmin(current)) {
            return Response.PERMISSION_DENIED();
        }
        if (deviceId <= 0) {
            return Response.ERROR("Invalid device ID");
        }

        if (getScopedDeviceOrNull(deviceId) == null) {
            return Response.DEVICE_NOT_FOUND_ERROR();
        }

        List<DeviceApplication> applications = this.deviceDAO.getDeviceInstalledApplications(deviceId);
        return Response.OK(applications);
    }

    @POST
    @Path("/device")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response saveDeviceOverride(WorkTimeDeviceOverride override) {
        // Check permissions
        User current = SecurityContext.get().getCurrentUser().orElse(null);
        if (current == null) {
            return Response.PERMISSION_DENIED();
        }
        if (!SecurityContext.get().isSuperAdmin() && !this.userDAO.isOrgAdmin(current)) {
            return Response.PERMISSION_DENIED();
        }

        if (override == null) {
            return Response.ERROR("Override payload is required");
        }

        int customerId = getCustomerId();
        override.setCustomerId(customerId);

        // Validation needs to be updated to check deviceId instead of userId
        if (override.getDeviceId() <= 0) {
            return Response.ERROR("Invalid device ID");
        }

        if (getScopedDeviceOrNull(override.getDeviceId()) == null) {
            return Response.DEVICE_NOT_FOUND_ERROR();
        }

        if (override.isEnabled()) {
            return Response.ERROR("Override endpoint is for device exceptions only; use device policy endpoint for normal policy");
        }
        if (override.getStartDateTime() == null || override.getEndDateTime() == null) {
            return Response.ERROR("Device exception requires startDateTime and endDateTime");
        }
        if (!override.getEndDateTime().after(override.getStartDateTime())) {
            return Response.ERROR("endDateTime must be after startDateTime");
        }

        if (override.getId() != null && override.getId() > 0) {
            WorkTimeDeviceOverride existing = workTimeDAO.getDeviceOverrideById(customerId, override.getId());
            if (existing == null) {
                return Response.DEVICE_NOT_FOUND_ERROR();
            }
        }

        if (override.getPriority() == null) {
            override.setPriority(0);
        }

        workTimeDAO.saveDeviceOverride(override);

        // Notify device about policy update
        sendConfigUpdatedTwice(override.getDeviceId());

        return Response.OK(override);
    }

    @DELETE
    @Path("/device/{id}")
    public Response deleteDeviceOverride(@PathParam("id") int deviceId) {
        User current = SecurityContext.get().getCurrentUser().orElse(null);
        if (current == null) {
            return Response.PERMISSION_DENIED();
        }
        if (!SecurityContext.get().isSuperAdmin() && !this.userDAO.isOrgAdmin(current)) {
            return Response.PERMISSION_DENIED();
        }

        int customerId = getCustomerId();
        if (getScopedDeviceOrNull(deviceId) == null) {
            return Response.DEVICE_NOT_FOUND_ERROR();
        }

        workTimeDAO.deleteDeviceOverridesForDevice(customerId, deviceId);

        // Notify device about policy update
        sendConfigUpdatedTwice(deviceId);

        return Response.OK();
    }

    @DELETE
    @Path("/device/exception/{id}")
    public Response deleteDeviceException(@PathParam("id") int exceptionId) {
        User current = SecurityContext.get().getCurrentUser().orElse(null);
        if (current == null) {
            return Response.PERMISSION_DENIED();
        }
        if (!SecurityContext.get().isSuperAdmin() && !this.userDAO.isOrgAdmin(current)) {
            return Response.PERMISSION_DENIED();
        }

        int customerId = getCustomerId();
        WorkTimeDeviceOverride existing = workTimeDAO.getDeviceOverrideById(customerId, exceptionId);
        if (existing == null) {
            return Response.DEVICE_NOT_FOUND_ERROR();
        }

        workTimeDAO.deleteDeviceOverrideById(customerId, exceptionId);
        sendConfigUpdatedTwice(existing.getDeviceId());

        return Response.OK();
    }
}
