package com.hmdm.plugins.worktime.rest.resource;

import javax.inject.Inject;
import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import com.hmdm.plugins.worktime.WorkTimeZone;
import java.sql.Date;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.hmdm.plugins.worktime.model.WorkTimeDevicePolicy;
import com.hmdm.plugins.worktime.model.WorkTimeDeviceOverride;
import com.hmdm.plugins.worktime.model.WorkTimeGeneralHoliday;
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
    private static final ZoneId WORKTIME_ZONE = WorkTimeZone.ZONE;
    private static final ScheduledExecutorService PUSH_RETRY_EXECUTOR = Executors.newSingleThreadScheduledExecutor();

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            PUSH_RETRY_EXECUTOR.shutdown();
        }, "worktime-push-executor-shutdown"));
    }

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
        ex.put("dateFrom", override.getStartDateTime().toInstant().atZone(WORKTIME_ZONE).toLocalDateTime().toLocalDate().format(dateFmt));
        ex.put("dateTo", override.getEndDateTime().toInstant().atZone(WORKTIME_ZONE).toLocalDateTime().toLocalDate().format(dateFmt));
        ex.put("timeFrom", override.getStartDateTime().toInstant().atZone(WORKTIME_ZONE).toLocalDateTime().toLocalTime().format(timeFmt));
        ex.put("timeTo", override.getEndDateTime().toInstant().atZone(WORKTIME_ZONE).toLocalDateTime().toLocalTime().format(timeFmt));
        ex.put("active", !now.isBefore(override.getStartDateTime().toInstant().atZone(WORKTIME_ZONE).toLocalDateTime())
                && !now.isAfter(override.getEndDateTime().toInstant().atZone(WORKTIME_ZONE).toLocalDateTime()));
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
        if (policy.getAllowedAppsDuringWork() != null && policy.getAllowedAppsDuringWork().length() > 4096) {
            return Response.ERROR("allowedAppsDuringWork exceeds maximum length of 4096");
        }
        if (policy.getAllowedAppsOutsideWork() != null && policy.getAllowedAppsOutsideWork().length() > 4096) {
            return Response.ERROR("allowedAppsOutsideWork exceeds maximum length of 4096");
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

                LocalDateTime end = override.getEndDateTime().toInstant().atZone(WORKTIME_ZONE).toLocalDateTime();
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

    // ==================================================================================
    // General (organization-wide) holidays
    // ==================================================================================

    private boolean isAdmin() {
        User current = SecurityContext.get().getCurrentUser().orElse(null);
        if (current == null) {
            return false;
        }
        return SecurityContext.get().isSuperAdmin() || this.userDAO.isOrgAdmin(current);
    }

    /**
     * Pushes a config-updated notification to every device of the current customer so that a general
     * holiday change (create/update/delete/import) is applied immediately across all devices.
     */
    private void pushToAllCustomerDevices() {
        try {
            List<Device> devices = deviceDAO.getAllDevices();
            for (Device device : devices) {
                sendConfigUpdatedTwice(device.getId());
            }
            log.info("Pushed worktime holiday update to {} device(s)", devices.size());
        } catch (Exception e) {
            log.error("Failed to push holiday update to devices", e);
        }
    }

    private String holidayStatus(WorkTimeGeneralHoliday holiday, LocalDate today) {
        LocalDate start = holiday.getStartDate().toLocalDate();
        LocalDate end = holiday.getEndDate().toLocalDate();
        if (today.isBefore(start)) {
            return "upcoming";
        }
        if (today.isAfter(end)) {
            return "expired";
        }
        return "active";
    }

    private Map<String, Object> toHolidayMap(WorkTimeGeneralHoliday holiday, LocalDate today) {
        Map<String, Object> map = new LinkedHashMap<>();
        String status = holidayStatus(holiday, today);
        map.put("id", holiday.getId());
        map.put("name", holiday.getName());
        map.put("startDate", holiday.getStartDate().toString());
        map.put("endDate", holiday.getEndDate().toString());
        map.put("status", status);
        map.put("active", "active".equals(status));
        // Editing is allowed only while the holiday has not fully ended.
        map.put("editable", !today.isAfter(holiday.getEndDate().toLocalDate()));
        return map;
    }

    /**
     * Validates the fields of a holiday. Returns an error message, or {@code null} if valid.
     */
    private String validateHoliday(String name, Date startDate, Date endDate, LocalDate today) {
        if (name == null || name.trim().isEmpty()) {
            return "Holiday name is required";
        }
        if (name.trim().length() > 255) {
            return "Holiday name is too long (max 255 characters)";
        }
        if (startDate == null) {
            return "Start date is required";
        }
        if (endDate == null) {
            return "End date is required";
        }
        LocalDate start = startDate.toLocalDate();
        LocalDate end = endDate.toLocalDate();
        if (start.isBefore(today)) {
            return "Start date cannot be before today";
        }
        if (end.isBefore(today)) {
            return "End date cannot be before today";
        }
        if (end.isBefore(start)) {
            return "End date cannot be earlier than start date";
        }
        return null;
    }

    @GET
    @Path("/holidays")
    public Response getHolidays() {
        if (!isAdmin()) {
            return Response.PERMISSION_DENIED();
        }
        int customerId = getCustomerId();
        LocalDate today = LocalDate.now(WORKTIME_ZONE);
        List<WorkTimeGeneralHoliday> holidays = workTimeDAO.getHolidays(customerId);
        List<Map<String, Object>> result = new ArrayList<>();
        for (WorkTimeGeneralHoliday holiday : holidays) {
            result.add(toHolidayMap(holiday, today));
        }
        return Response.OK(result);
    }

    @GET
    @Path("/holidays/active")
    public Response getActiveHoliday() {
        if (!isAdmin()) {
            return Response.PERMISSION_DENIED();
        }
        int customerId = getCustomerId();
        LocalDate today = LocalDate.now(WORKTIME_ZONE);
        List<WorkTimeGeneralHoliday> active = workTimeDAO.getActiveHolidays(customerId, Date.valueOf(today));
        List<Map<String, Object>> result = new ArrayList<>();
        for (WorkTimeGeneralHoliday holiday : active) {
            result.add(toHolidayMap(holiday, today));
        }
        return Response.OK(result);
    }

    @POST
    @Path("/holiday")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response saveHoliday(WorkTimeGeneralHoliday holiday) {
        if (!isAdmin()) {
            return Response.PERMISSION_DENIED();
        }
        if (holiday == null) {
            return Response.ERROR("Holiday payload is required");
        }

        int customerId = getCustomerId();
        LocalDate today = LocalDate.now(WORKTIME_ZONE);

        String error = validateHoliday(holiday.getName(), holiday.getStartDate(), holiday.getEndDate(), today);
        if (error != null) {
            return Response.ERROR(error);
        }

        holiday.setCustomerId(customerId);
        holiday.setName(holiday.getName().trim());

        if (holiday.getId() != null && holiday.getId() > 0) {
            WorkTimeGeneralHoliday existing = workTimeDAO.getHolidayById(customerId, holiday.getId());
            if (existing == null) {
                return Response.ERROR("Holiday not found");
            }
            // Editing is only allowed while the holiday has not fully ended.
            if (today.isAfter(existing.getEndDate().toLocalDate())) {
                return Response.ERROR("Cannot edit a holiday that has already ended");
            }
            workTimeDAO.updateHoliday(holiday);
        } else {
            holiday.setId(null);
            workTimeDAO.insertHoliday(holiday);
        }

        pushToAllCustomerDevices();

        log.info("Saved general holiday '{}' for customer {} ({} to {})",
                holiday.getName(), customerId, holiday.getStartDate(), holiday.getEndDate());

        return Response.OK(toHolidayMap(holiday, today));
    }

    @DELETE
    @Path("/holiday/{id}")
    public Response deleteHoliday(@PathParam("id") int id) {
        if (!isAdmin()) {
            return Response.PERMISSION_DENIED();
        }
        int customerId = getCustomerId();
        WorkTimeGeneralHoliday existing = workTimeDAO.getHolidayById(customerId, id);
        if (existing == null) {
            return Response.ERROR("Holiday not found");
        }
        workTimeDAO.deleteHolidayById(customerId, id);
        pushToAllCustomerDevices();
        return Response.OK();
    }

    public static class HolidayImportRequest {
        private String content;

        public String getContent() {
            return content;
        }

        public void setContent(String content) {
            this.content = content;
        }
    }

    @POST
    @Path("/holidays/import")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response importHolidays(HolidayImportRequest request) {
        if (!isAdmin()) {
            return Response.PERMISSION_DENIED();
        }
        if (request == null || request.getContent() == null || request.getContent().trim().isEmpty()) {
            return Response.ERROR("CSV content is empty");
        }

        int customerId = getCustomerId();
        LocalDate today = LocalDate.now(WORKTIME_ZONE);

        String[] rawLines = request.getContent().replace("\r\n", "\n").replace("\r", "\n").split("\n");

        // Find the header line (first non-empty line).
        int headerIndex = -1;
        for (int i = 0; i < rawLines.length; i++) {
            if (!rawLines[i].trim().isEmpty()) {
                headerIndex = i;
                break;
            }
        }
        if (headerIndex < 0) {
            return Response.ERROR("CSV is empty");
        }

        List<String> header = parseCsvLine(rawLines[headerIndex]);
        if (header.size() != 3
                || !"name".equalsIgnoreCase(header.get(0).trim())
                || !"from".equalsIgnoreCase(header.get(1).trim())
                || !"to".equalsIgnoreCase(header.get(2).trim())) {
            return Response.ERROR("CSV headers must be exactly: name, from, to");
        }

        int imported = 0;
        List<Map<String, Object>> failed = new ArrayList<>();
        int dataRowNumber = 0;

        for (int i = headerIndex + 1; i < rawLines.length; i++) {
            String line = rawLines[i];
            if (line.trim().isEmpty()) {
                continue; // skip blank lines
            }
            dataRowNumber++;

            List<String> cols = parseCsvLine(line);
            String name = cols.size() > 0 ? cols.get(0).trim() : "";
            String fromStr = cols.size() > 1 ? cols.get(1).trim() : "";
            String toStr = cols.size() > 2 ? cols.get(2).trim() : "";

            Date fromDate = parseCsvDate(fromStr);
            Date toDate = parseCsvDate(toStr);

            String rowError = null;
            if (cols.size() < 3) {
                rowError = "Row must have 3 columns (name, from, to)";
            } else if (fromDate == null) {
                rowError = "Invalid 'from' date (expected DD/MM/YYYY): '" + fromStr + "'";
            } else if (toDate == null) {
                rowError = "Invalid 'to' date (expected DD/MM/YYYY): '" + toStr + "'";
            } else {
                rowError = validateHoliday(name, fromDate, toDate, today);
            }

            if (rowError != null) {
                Map<String, Object> fail = new LinkedHashMap<>();
                fail.put("row", dataRowNumber);
                fail.put("name", name);
                fail.put("reason", rowError);
                failed.add(fail);
                continue;
            }

            try {
                WorkTimeGeneralHoliday holiday = new WorkTimeGeneralHoliday();
                holiday.setCustomerId(customerId);
                holiday.setName(name);
                holiday.setStartDate(fromDate);
                holiday.setEndDate(toDate);
                workTimeDAO.insertHoliday(holiday);
                imported++;
            } catch (Exception e) {
                log.error("Failed to import holiday row {}", dataRowNumber, e);
                Map<String, Object> fail = new LinkedHashMap<>();
                fail.put("row", dataRowNumber);
                fail.put("name", name);
                fail.put("reason", "Database error while saving");
                failed.add(fail);
            }
        }

        if (imported > 0) {
            pushToAllCustomerDevices();
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("imported", imported);
        result.put("failed", failed);
        result.put("total", dataRowNumber);
        return Response.OK(result);
    }

    /**
     * Minimal CSV line parser supporting double-quoted fields (with escaped "" quotes).
     */
    private List<String> parseCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        if (line == null) {
            return fields;
        }
        // Strip a leading UTF-8 BOM if present.
        if (!line.isEmpty() && line.charAt(0) == '\uFEFF') {
            line = line.substring(1);
        }
        StringBuilder sb = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        sb.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    sb.append(c);
                }
            } else {
                if (c == '"') {
                    inQuotes = true;
                } else if (c == ',') {
                    fields.add(sb.toString());
                    sb.setLength(0);
                } else {
                    sb.append(c);
                }
            }
        }
        fields.add(sb.toString());
        return fields;
    }

    // CSV dates are entered in the Indian standard DD/MM/YYYY format; ISO yyyy-MM-dd is also accepted
    // as a convenience/back-compat fallback.
    private static final DateTimeFormatter[] CSV_DATE_FORMATS = {
            DateTimeFormatter.ofPattern("d/M/uuuu"),
            DateTimeFormatter.ofPattern("uuuu-M-d"),
    };

    private Date parseCsvDate(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        String trimmed = value.trim();
        for (DateTimeFormatter format : CSV_DATE_FORMATS) {
            try {
                return Date.valueOf(LocalDate.parse(trimmed, format));
            } catch (Exception ignored) {
                // try next format
            }
        }
        return null;
    }
}
