package com.hmdm.plugins.calllog.rest.resource;

import com.hmdm.plugins.calllog.model.CallLogRecord;
import com.hmdm.plugins.calllog.model.CallLogSettings;
import com.hmdm.plugins.calllog.persistence.CallLogDAO;
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
 * REST API for Call Log plugin (Admin Panel)
 */
@Api(tags = {"Call Log Plugin"})
@Path("/plugins/calllog/private")
@Produces(MediaType.APPLICATION_JSON)
public class CallLogResource {

    private static final Logger log = LoggerFactory.getLogger(CallLogResource.class);

    private final CallLogDAO callLogDAO;
    private final DeviceDAO deviceDAO;
    private final UserDAO userDAO;

    @Inject
    public CallLogResource(CallLogDAO callLogDAO, DeviceDAO deviceDAO, UserDAO userDAO) {
        this.callLogDAO = callLogDAO;
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
     * Get call logs for a specific device
     */
    @GET
    @Path("/device/{deviceId}")
    @ApiOperation(value = "Get call logs for a device")
    public Response getDeviceCallLogs(
            @ApiParam("Device ID") @PathParam("deviceId") int deviceId,
            @ApiParam("Page number (0-based)") @QueryParam("page") @DefaultValue("0") int page,
            @ApiParam("Page size") @QueryParam("pageSize") @DefaultValue("50") int pageSize
    ) {
        if (!checkPermission()) {
            log.error("Unauthorized attempt to access call logs");
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
            log.warn("User {} attempted to access call logs for device {} from different customer",
                    currentUser.getLogin(), deviceId);
            return Response.PERMISSION_DENIED();
        }

        int offset = page * pageSize;
        List<CallLogRecord> logs;
        int total;
        try {
            logs = callLogDAO.getCallLogsByDevicePaged(deviceId, customerId, pageSize, offset);
            total = callLogDAO.getCallLogsCountByDevice(deviceId, customerId);
        } catch (Exception e) {
            log.error("Failed to load call logs for device {} and customer {}", deviceId, customerId, e);
            return Response.ERROR("plugin.calllog.error.backend.not.ready");
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
    @ApiOperation(value = "Get call log plugin settings")
    public Response getSettings() {
        if (!checkPermission()) {
            return Response.PERMISSION_DENIED();
        }

        int customerId = getCustomerId();
        CallLogSettings settings = callLogDAO.getSettings(customerId);

        if (settings == null) {
            settings = new CallLogSettings();
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
    @ApiOperation(value = "Save call log plugin settings")
    public Response saveSettings(CallLogSettings settings) {
        User current = SecurityContext.get().getCurrentUser().orElse(null);
        if (current == null) {
            log.error("Unauthorized attempt to save call log settings");
            return Response.PERMISSION_DENIED();
        }

        // Only admins can change settings
        if (!SecurityContext.get().isSuperAdmin() && !this.userDAO.isOrgAdmin(current)) {
            log.warn("User {} is not allowed to save call log settings: must be admin", current.getLogin());
            return Response.PERMISSION_DENIED();
        }

        int customerId = getCustomerId();
        settings.setCustomerId(customerId);
        callLogDAO.saveSettings(settings);

        return Response.OK(settings);
    }

    /**
     * Delete call logs for a device
     */
    @DELETE
    @Path("/device/{deviceId}")
    @ApiOperation(value = "Delete all call logs for a device")
    public Response deleteDeviceCallLogs(
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

        int deleted = callLogDAO.deleteCallLogsByDevice(deviceId, customerId);

        Map<String, Object> result = new HashMap<>();
        result.put("deletedCount", deleted);

        return Response.OK(result);
    }
}
