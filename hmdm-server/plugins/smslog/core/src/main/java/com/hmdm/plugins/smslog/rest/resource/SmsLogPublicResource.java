package com.hmdm.plugins.smslog.rest.resource;

import com.hmdm.persistence.UnsecureDAO;
import com.hmdm.persistence.domain.Device;
import com.hmdm.plugins.smslog.model.SmsLogRecord;
import com.hmdm.plugins.smslog.model.SmsLogSettings;
import com.hmdm.plugins.smslog.persistence.SmsLogDAO;
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
 * REST API for SMS Log plugin (Android devices)
 */
@Api(tags = {"SMS Log Plugin - Public"})
@Path("/plugins/smslog/public")
@Produces(MediaType.APPLICATION_JSON)
public class SmsLogPublicResource {

    private static final Logger log = LoggerFactory.getLogger(SmsLogPublicResource.class);

    private final SmsLogDAO smsLogDAO;
    private final UnsecureDAO unsecureDAO;

    @Inject
    public SmsLogPublicResource(SmsLogDAO smsLogDAO, UnsecureDAO unsecureDAO) {
        this.smsLogDAO = smsLogDAO;
        this.unsecureDAO = unsecureDAO;
    }

    /**
     * Submit SMS logs from Android device
     */
    @POST
    @Path("/submit/{deviceNumber}")
    @Consumes(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Submit SMS logs from device", notes = "Endpoint for Android devices to upload SMS logs")
    public Response submitSmsLogs(
            @ApiParam("Device number") @PathParam("deviceNumber") String deviceNumber,
            List<SmsLogRecord> logs
    ) {
        try {
            // Find device by number
            Device device = unsecureDAO.getDeviceByNumber(deviceNumber);
            if (device == null) {
                log.warn("SMS log submission failed: device not found: {}", deviceNumber);
                return Response.ERROR("error.device.not.found");
            }

            // Check if plugin is enabled for this customer
            SmsLogSettings settings = smsLogDAO.getSettings(device.getCustomerId());
            if (settings != null && !settings.isEnabled()) {
                log.debug("SMS log plugin disabled for customer {}", device.getCustomerId());
                return Response.OK();
            }

            if (logs == null || logs.isEmpty()) {
                log.debug("No SMS logs received from device {}", deviceNumber);
                return Response.OK();
            }

            // Set device ID and customer ID for all records
            long currentTime = System.currentTimeMillis();
            for (SmsLogRecord record : logs) {
                record.setDeviceId(device.getId());
                record.setCustomerId(device.getCustomerId());
                record.setCreateTime(currentTime);
            }

            // Insert logs in batch
            smsLogDAO.insertSmsLogRecordsBatch(logs);

            log.info("Received {} SMS log records from device {}", logs.size(), deviceNumber);

            return Response.OK();

        } catch (Exception e) {
            log.error("Error processing SMS logs from device {}", deviceNumber, e);
            return Response.ERROR("error.internal");
        }
    }

    /**
     * Check if SMS log collection is enabled for a device
     */
    @GET
    @Path("/enabled/{deviceNumber}")
    @ApiOperation(value = "Check if SMS log collection is enabled")
    public Response isEnabled(
            @ApiParam("Device number") @PathParam("deviceNumber") String deviceNumber
    ) {
        try {
            Device device = unsecureDAO.getDeviceByNumber(deviceNumber);
            if (device == null) {
                return Response.ERROR("error.device.not.found");
            }

            SmsLogSettings settings = smsLogDAO.getSettings(device.getCustomerId());
            boolean enabled = settings == null || settings.isEnabled();

            return Response.OK(enabled);

        } catch (Exception e) {
            log.error("Error checking SMS log status for device {}", deviceNumber, e);
            return Response.ERROR("error.internal");
        }
    }
}
