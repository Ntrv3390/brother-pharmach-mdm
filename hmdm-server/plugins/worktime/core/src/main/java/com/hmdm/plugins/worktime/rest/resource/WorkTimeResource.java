package com.hmdm.plugins.worktime.rest.resource;

import javax.inject.Inject;
import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
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

import com.hmdm.plugins.worktime.model.WorkTimePolicy;
import com.hmdm.plugins.worktime.model.WorkTimeDeviceOverride;
import com.hmdm.plugins.worktime.persistence.WorkTimeDAO;
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

    private void normalizeGlobalPolicy(WorkTimePolicy policy) {
        if (policy.getDaysOfWeek() == null) {
            policy.setDaysOfWeek(127);
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

    // --- Global policy endpoints ---
    @GET
    @Path("/policy")
    public Response getPolicy() {
        // Check authentication
        User current = SecurityContext.get().getCurrentUser().orElse(null);
        if (current == null) {
            log.error("Unauthorized attempt to access worktime policy - not authenticated");
            return Response.PERMISSION_DENIED();
        }

        int customerId = getCustomerId();
        WorkTimePolicy policy = workTimeDAO.getGlobalPolicy(customerId);
        return Response.OK(policy);
    }

    @POST
    @Path("/policy")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response savePolicy(WorkTimePolicy policy) {
        // Check if user is admin or has worktime permission
        User current = SecurityContext.get().getCurrentUser().orElse(null);
        if (current == null) {
            log.error("Unauthorized attempt to save worktime policy - not authenticated");
            return Response.PERMISSION_DENIED();
        }

        if (!SecurityContext.get().isSuperAdmin() && !this.userDAO.isOrgAdmin(current)) {
            log.warn("User {} is not allowed to save policy: must be admin", current.getLogin());
            return Response.PERMISSION_DENIED();
        }

        if (policy == null) {
            return Response.ERROR("Policy payload is required");
        }
        if (!isValidTime(policy.getStartTime()) || !isValidTime(policy.getEndTime())) {
            return Response.ERROR("Invalid time format, expected HH:mm");
        }
        if (policy.getDaysOfWeek() != null && (policy.getDaysOfWeek() < 0 || policy.getDaysOfWeek() > 127)) {
            return Response.ERROR("Invalid daysOfWeek bitmask");
        }

        int customerId = getCustomerId();
        policy.setCustomerId(customerId);
        normalizeGlobalPolicy(policy);
        workTimeDAO.saveGlobalPolicy(policy);

        // Notify all devices about policy update
        List<Device> devices = deviceDAO.getAllDevices();
        int notified = 0;
        for (Device device : devices) {
            sendConfigUpdatedTwice(device.getId());
            notified++;
        }

        log.info("Saved WorkTime global policy for customer {} and sent double config update to {} devices",
                customerId, notified);

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

        // Combine devices with their overrides
        List<WorkTimeDeviceOverride> result = new java.util.ArrayList<>();
        DateTimeFormatter dateFmt = DateTimeFormatter.ISO_LOCAL_DATE;
        DateTimeFormatter timeFmt = DateTimeFormatter.ofPattern("HH:mm");
        LocalDateTime now = LocalDateTime.now(WORKTIME_ZONE);
        for (Device device : allDevices) {
            WorkTimeDeviceOverride override = overrides.stream()
                    .filter(o -> o.getDeviceId() == device.getId())
                    .findFirst()
                    .orElse(null);

            if (override == null) {
                // Create a default override (no exceptions, enabled)
                override = new WorkTimeDeviceOverride();
                override.setCustomerId(customerId);
                override.setDeviceId(device.getId());
                override.setDeviceName(device.getNumber());
                override.setEnabled(true);
                override.setExceptions(new java.util.ArrayList<>());
            } else {
                override.setDeviceName(device.getNumber());
            }
            if (override.getExceptions() == null) {
                override.setExceptions(new java.util.ArrayList<>());
            }
            if (!override.isEnabled() && override.getStartDateTime() != null && override.getEndDateTime() != null) {
                LocalDateTime start = override.getStartDateTime().toLocalDateTime();
                LocalDateTime end = override.getEndDateTime().toLocalDateTime();
                if (now.isAfter(end)) {
                    workTimeDAO.deleteDeviceOverride(customerId, device.getId());
                    override.setEnabled(true);
                    override.setStartDateTime((java.sql.Timestamp) null);
                    override.setEndDateTime((java.sql.Timestamp) null);
                    override.setExceptions(new java.util.ArrayList<>());
                    result.add(override);
                    continue;
                }
                boolean active = !now.isBefore(start) && !now.isAfter(end);
                Map<String, Object> ex = new java.util.HashMap<>();
                ex.put("dateFrom", start.toLocalDate().format(dateFmt));
                ex.put("dateTo", end.toLocalDate().format(dateFmt));
                ex.put("timeFrom", start.toLocalTime().format(timeFmt));
                ex.put("timeTo", end.toLocalTime().format(timeFmt));
                ex.put("active", active);
                override.getExceptions().add(ex);
            }
            result.add(override);
        }

        return Response.OK(result);
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

        if (!override.isEnabled()) {
            if (override.getStartDateTime() == null || override.getEndDateTime() == null) {
                return Response.ERROR("Disabled override requires startDateTime and endDateTime");
            }
            if (!override.getEndDateTime().after(override.getStartDateTime())) {
                return Response.ERROR("endDateTime must be after startDateTime");
            }
        } else {
            if (override.getStartTime() != null && !override.getStartTime().trim().isEmpty() && !isValidTime(override.getStartTime())) {
                return Response.ERROR("Invalid startTime format, expected HH:mm");
            }
            if (override.getEndTime() != null && !override.getEndTime().trim().isEmpty() && !isValidTime(override.getEndTime())) {
                return Response.ERROR("Invalid endTime format, expected HH:mm");
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
        workTimeDAO.deleteDeviceOverride(customerId, deviceId);

        // Notify device about policy update
        sendConfigUpdatedTwice(deviceId);

        return Response.OK();
    }
}
