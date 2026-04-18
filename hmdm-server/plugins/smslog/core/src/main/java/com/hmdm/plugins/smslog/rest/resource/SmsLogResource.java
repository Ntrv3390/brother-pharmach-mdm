package com.hmdm.plugins.smslog.rest.resource;

import com.hmdm.plugins.smslog.model.SmsLogRecord;
import com.hmdm.plugins.smslog.model.SmsLogSettings;
import com.hmdm.plugins.smslog.persistence.SmsLogDAO;
import com.hmdm.persistence.DeviceDAO;
import com.hmdm.persistence.UserDAO;
import com.hmdm.persistence.domain.Device;
import com.hmdm.persistence.domain.User;
import com.hmdm.rest.json.Response;
import com.hmdm.security.SecurityContext;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * REST API for SMS Log plugin (Admin Panel)
 */
@Api(tags = {"SMS Log Plugin"})
@Path("/plugins/smslog/private")
@Produces(MediaType.APPLICATION_JSON)
public class SmsLogResource {

    private static final Logger log = LoggerFactory.getLogger(SmsLogResource.class);

    private final SmsLogDAO smsLogDAO;
    private final DeviceDAO deviceDAO;
    private final UserDAO userDAO;

    @Inject
    public SmsLogResource(SmsLogDAO smsLogDAO, DeviceDAO deviceDAO, UserDAO userDAO) {
        this.smsLogDAO = smsLogDAO;
        this.deviceDAO = deviceDAO;
        this.userDAO = userDAO;
    }

    private int getCustomerId() {
        return SecurityContext.get()
                .getCurrentUser()
                .orElseThrow(() -> new WebApplicationException("Unauthorized", 401))
                .getCustomerId();
    }

    private boolean checkPermission() {
        User current = SecurityContext.get().getCurrentUser().orElse(null);
        if (current == null) {
            return false;
        }
        return true;
    }

    /**
    * Get SMS logs for a specific device
     */
    @GET
    @Path("/device/{deviceId}")
    @ApiOperation(value = "Get SMS logs for a device")
    public Response getDeviceSmsLogs(
            @ApiParam("Device ID") @PathParam("deviceId") int deviceId,
            @ApiParam("Page number (0-based)") @QueryParam("page") @DefaultValue("0") int page,
            @ApiParam("Page size") @QueryParam("pageSize") @DefaultValue("50") int pageSize,
            @ApiParam("Filter by message type (1=incoming,2=outgoing)") @QueryParam("messageType") Integer messageType,
            @ApiParam("Filter by SIM slot (1 or 2)") @QueryParam("simSlot") Integer simSlot,
                @ApiParam("Search by phone number or contact name") @QueryParam("search") @DefaultValue("") String search
    ) {
        if (!checkPermission()) {
            log.error("Unauthorized attempt to access SMS logs");
            return Response.PERMISSION_DENIED();
        }

        int customerId = getCustomerId();

        // Verify device belongs to this customer
        Device device = deviceDAO.getDeviceById(deviceId);
        if (device == null) {
            return Response.ERROR("error.device.not.found");
        }

        // Check if user has access to this device (based on customer)
        User currentUser = SecurityContext.get().getCurrentUser().get();
        if (device.getCustomerId() != customerId) {
            log.warn("User {} attempted to access SMS logs for device {} from different customer",
                    currentUser.getLogin(), deviceId);
            return Response.PERMISSION_DENIED();
        }

        int offset = page * pageSize;
        List<SmsLogRecord> logs;
        int total;
        try {
            logs = smsLogDAO.getSmsLogsByDevicePagedFiltered(deviceId, customerId, messageType, simSlot, search, pageSize, offset);
            total = smsLogDAO.getSmsLogsCountByDeviceFiltered(deviceId, customerId, messageType, simSlot, search);
        } catch (Exception e) {
            log.error("Failed to load SMS logs for device {} and customer {}", deviceId, customerId, e);
            return Response.ERROR("plugin.smslog.error.backend.not.ready");
        }

        Map<String, Object> result = new HashMap<>();
        result.put("items", logs);
        result.put("total", total);
        result.put("page", page);
        result.put("pageSize", pageSize);

        return Response.OK(result);
    }

    /**
     * Get plugin settings
     */
    @GET
    @Path("/settings")
    @ApiOperation(value = "Get SMS log plugin settings")
    public Response getSettings() {
        if (!checkPermission()) {
            return Response.PERMISSION_DENIED();
        }

        int customerId = getCustomerId();
        SmsLogSettings settings = smsLogDAO.getSettings(customerId);

        if (settings == null) {
            settings = new SmsLogSettings();
            settings.setCustomerId(customerId);
            settings.setEnabled(true);
            settings.setRetentionDays(90);
        }

        return Response.OK(settings);
    }

    /**
     * Save plugin settings (admin only)
     */
    @POST
    @Path("/settings")
    @Consumes(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Save SMS log plugin settings")
    public Response saveSettings(SmsLogSettings settings) {
        User current = SecurityContext.get().getCurrentUser().orElse(null);
        if (current == null) {
            log.error("Unauthorized attempt to save SMS log settings");
            return Response.PERMISSION_DENIED();
        }

        // Only admins can change settings
        if (!SecurityContext.get().isSuperAdmin() && !this.userDAO.isOrgAdmin(current)) {
            log.warn("User {} is not allowed to save SMS log settings: must be admin", current.getLogin());
            return Response.PERMISSION_DENIED();
        }

        int customerId = getCustomerId();
        settings.setCustomerId(customerId);
        smsLogDAO.saveSettings(settings);

        return Response.OK(settings);
    }

    /**
    * Delete SMS logs for a device
     */
    @DELETE
    @Path("/device/{deviceId}")
    @ApiOperation(value = "Delete all SMS logs for a device")
    public Response deleteDeviceSmsLogs(
            @ApiParam("Device ID") @PathParam("deviceId") int deviceId
    ) {
        User current = SecurityContext.get().getCurrentUser().orElse(null);
        if (current == null) {
            return Response.PERMISSION_DENIED();
        }

        int customerId = getCustomerId();

        // Verify device belongs to this customer
        Device device = deviceDAO.getDeviceById(deviceId);
        if (device == null || device.getCustomerId() != customerId) {
            return Response.PERMISSION_DENIED();
        }

        int deleted = smsLogDAO.deleteSmsLogsByDevice(deviceId, customerId);

        Map<String, Object> result = new HashMap<>();
        result.put("deletedCount", deleted);

        return Response.OK(result);
    }
}
