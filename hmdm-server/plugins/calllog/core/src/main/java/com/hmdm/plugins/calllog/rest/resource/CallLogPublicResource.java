package com.hmdm.plugins.calllog.rest.resource;

import com.hmdm.persistence.UnsecureDAO;
import com.hmdm.persistence.domain.Device;
import com.hmdm.plugins.calllog.model.CallLogRecord;
import com.hmdm.plugins.calllog.model.CallLogSettings;
import com.hmdm.plugins.calllog.persistence.CallLogDAO;
import com.hmdm.rest.json.Response;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import java.util.List;

/**
 * REST API for Call Log plugin (Android devices)
 */
@Api(tags = {"Call Log Plugin - Public"})
@Path("/plugins/calllog/public")
@Produces(MediaType.APPLICATION_JSON)
public class CallLogPublicResource {

    private static final Logger log = LoggerFactory.getLogger(CallLogPublicResource.class);

    private final CallLogDAO callLogDAO;
    private final UnsecureDAO unsecureDAO;

    @Inject
    public CallLogPublicResource(CallLogDAO callLogDAO, UnsecureDAO unsecureDAO) {
        this.callLogDAO = callLogDAO;
        this.unsecureDAO = unsecureDAO;
    }

    /**
     * Submit call logs from Android device
     */
    @POST
    @Path("/submit/{deviceNumber}")
    @Consumes(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Submit call logs from device", notes = "Endpoint for Android devices to upload call logs")
    public Response submitCallLogs(
            @ApiParam("Device number") @PathParam("deviceNumber") String deviceNumber,
            List<CallLogRecord> logs
    ) {
        try {
            // Find device by number
            Device device = unsecureDAO.getDeviceByNumber(deviceNumber);
            if (device == null) {
                log.warn("Call log submission failed: device not found: {}", deviceNumber);
                return Response.ERROR("error.device.not.found");
            }

            // Check if plugin is enabled for this customer
            CallLogSettings settings = callLogDAO.getSettings(device.getCustomerId());
            if (settings != null && !settings.isEnabled()) {
                log.debug("Call log plugin disabled for customer {}", device.getCustomerId());
                return Response.OK();
            }

            if (logs == null || logs.isEmpty()) {
                log.debug("No call logs received from device {}", deviceNumber);
                return Response.OK();
            }

            // Set device ID and customer ID for all records
            long currentTime = System.currentTimeMillis();
            for (CallLogRecord record : logs) {
                record.setDeviceId(device.getId());
                record.setCustomerId(device.getCustomerId());
                record.setCreateTime(currentTime);
            }

            // Insert logs in batch
            callLogDAO.insertCallLogRecordsBatch(logs);

            log.info("Received {} call log records from device {}", logs.size(), deviceNumber);

            return Response.OK();

        } catch (Exception e) {
            log.error("Error processing call logs from device {}", deviceNumber, e);
            return Response.ERROR("error.internal");
        }
    }

    /**
     * Check if call log collection is enabled for a device
     */
    @GET
    @Path("/enabled/{deviceNumber}")
    @ApiOperation(value = "Check if call log collection is enabled")
    public Response isEnabled(
            @ApiParam("Device number") @PathParam("deviceNumber") String deviceNumber
    ) {
        try {
            Device device = unsecureDAO.getDeviceByNumber(deviceNumber);
            if (device == null) {
                return Response.ERROR("error.device.not.found");
            }

            CallLogSettings settings = callLogDAO.getSettings(device.getCustomerId());
            boolean enabled = settings == null || settings.isEnabled();

            return Response.OK(enabled);

        } catch (Exception e) {
            log.error("Error checking call log status for device {}", deviceNumber, e);
            return Response.ERROR("error.internal");
        }
    }
}
