/*
 *
 * Headwind MDM: Open Source Android MDM Software
 * https://h-mdm.com
 *
 * Copyright (C) 2019 Headwind Solutions LLC (http://h-sms.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.hmdm.rest.resource;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.StreamingOutput;
import javax.ws.rs.core.HttpHeaders;

import org.glassfish.jersey.media.multipart.FormDataContentDisposition;
import org.glassfish.jersey.media.multipart.FormDataParam;

import com.hmdm.persistence.CustomerDAO;
import com.hmdm.persistence.UnsecureDAO;
import com.hmdm.persistence.UserRoleSettingsDAO;
import com.hmdm.persistence.domain.UserRoleSettings;
import com.hmdm.security.SecurityContext;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.Authorization;
import com.hmdm.persistence.CommonDAO;
import com.hmdm.persistence.domain.Settings;
import com.hmdm.rest.json.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Api(tags = {"Settings"}, authorizations = {@Authorization("Bearer Token")})
@Singleton
@Path("/private/settings")
public class SettingsResource {

    private static final Logger log = LoggerFactory.getLogger(SettingsResource.class);

    private CommonDAO commonDAO;
    private UserRoleSettingsDAO userRoleSettingsDAO;
    private UnsecureDAO unsecureDAO;

    /**
     * <p>A constructor required by Swagger.</p>
     */
    public SettingsResource() {
    }

    @Inject
    public SettingsResource(CommonDAO commonDAO, UserRoleSettingsDAO userRoleSettingsDAO, UnsecureDAO unsecureDAO) {
        this.commonDAO = commonDAO;
        this.userRoleSettingsDAO = userRoleSettingsDAO;
        this.unsecureDAO = unsecureDAO;
    }

    // =================================================================================================================
    @ApiOperation(
            value = "Get settings",
            notes = "Gets the current settings",
            response = Settings.class
    )
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response getSettings() {
        try {
            Settings settings = Optional.ofNullable(this.commonDAO.getSettings()).orElse(new Settings());
            settings.setSingleCustomer(unsecureDAO.isSingleCustomer());
            if (!settings.isSingleCustomer()) {
                this.commonDAO.loadCustomerSettings(settings);
            }
            return Response.OK(settings);
        } catch (Exception e) {
            log.error("Unexpected error when getting the settings for customer", e);
            return Response.INTERNAL_ERROR();
        }
    }

    // =================================================================================================================
    @ApiOperation(
            value = "Get user role settings",
            notes = "Gets the current settings for role of the current user",
            response = UserRoleSettings.class
    )
    @GET
    @Path("/userRole/{roleId}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getUserRoleSettings(@PathParam("roleId") int roleId) {
        try {
            UserRoleSettings settings = this.userRoleSettingsDAO.getUserRoleSettings(roleId);
            if (settings == null) {
                final UserRoleSettings defaultSettings = new UserRoleSettings();
                defaultSettings.setRoleId(roleId);

                settings = defaultSettings;
            }
            return Response.OK(settings);
        } catch (Exception e) {
            log.error("Unexpected error when getting the user role settings for current user", e);
            return Response.INTERNAL_ERROR();
        }
    }

    // =================================================================================================================
    @ApiOperation(
            value = "Save default design",
            notes = "Save the settings for Default Design for mobile application",
            response = Settings.class
    )
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Path("/design")
    public Response updateDefaultDesignSettings(Settings settings) {
        if (!SecurityContext.get().hasPermission("settings")) {
            log.error("Unauthorized attempt to update settings by user " +
                    SecurityContext.get().getCurrentUserName());
            return Response.PERMISSION_DENIED();
        }
        try {
            this.commonDAO.saveDefaultDesignSettings(settings);
            return Response.OK();
        } catch (Exception e) {
            log.error("Unexpected error when saving default design settings", e);
            return Response.INTERNAL_ERROR();
        }
    }

    // =================================================================================================================
    @ApiOperation(
            value = "Save user role common settings",
            notes = "Save the settings for user roles",
            response = Settings.class
    )
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Path("/userRoles/common")
    public Response updateUserRoleCommonSettings(List<UserRoleSettings> settings) {
        if (!SecurityContext.get().hasPermission("settings")) {
            log.error("Unauthorized attempt to update settings by user " +
                    SecurityContext.get().getCurrentUserName());
            return Response.PERMISSION_DENIED();
        }
        try {
            this.userRoleSettingsDAO.saveCommonSettings(settings);
            return Response.OK();
        } catch (Exception e) {
            log.error("Unexpected error when saving user roles common settings", e);
            return Response.INTERNAL_ERROR();
        }
    }

    // =================================================================================================================
    @ApiOperation(
            value = "Save language settings",
            notes = "Save the language settings for MDM web application",
            response = Settings.class
    )
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Path("/lang")
    public Response updateLanguageSettings(Settings settings) {
        if (!SecurityContext.get().hasPermission("settings")) {
            log.error("Unauthorized attempt to update settings by user " +
                    SecurityContext.get().getCurrentUserName());
            return Response.PERMISSION_DENIED();
        }
        try {
            this.commonDAO.saveLanguageSettings(settings);
            return Response.OK();
        } catch (Exception e) {
            log.error("Unexpected error when saving language settings", e);
            return Response.INTERNAL_ERROR();
        }
    }

    // =================================================================================================================
    @ApiOperation(
            value = "Save misc settings",
            notes = "Save the misc settings for MDM web application",
            response = Settings.class
    )
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Path("/misc")
    public Response updateMiscSettings(Settings settings) {
        if (!SecurityContext.get().hasPermission("settings")) {
            log.error("Unauthorized attempt to update settings by user " +
                    SecurityContext.get().getCurrentUserName());
            return Response.PERMISSION_DENIED();
        }
        try {
            if (!unsecureDAO.isSingleCustomer()) {
                // These settings are not allowed to setup in multi-tenant mode
                settings.setCreateNewDevices(false);
                settings.setNewDeviceGroupId(null);
                settings.setNewDeviceConfigurationId(null);
            }
            this.commonDAO.saveMiscSettings(settings);
            return Response.OK();
        } catch (Exception e) {
            log.error("Unexpected error when saving misc settings", e);
            return Response.INTERNAL_ERROR();
        }
    }

    // =================================================================================================================
    @ApiOperation(
            value = "Export database",
            notes = "Exports the full PostgreSQL database as a SQL dump file"
    )
    @GET
    @Path("/db/export")
    @Produces("application/octet-stream")
    public javax.ws.rs.core.Response exportDatabase() {
        if (!SecurityContext.get().hasPermission("settings")) {
            log.error("Unauthorized attempt to export database by user " +
                    SecurityContext.get().getCurrentUserName());
            return javax.ws.rs.core.Response.status(403).build();
        }
        try {
            String dbHost = getEnv("DB_HOST", "postgres");
            String dbPort = getEnv("DB_PORT", "5432");
            String dbName = getEnv("DB_NAME", "hmdm");
            String dbUser = getEnv("DB_USER", "hmdm");
            String dbPassword = getEnv("DB_PASSWORD", "hmdm");

            // Log tables: export only last 1 hour of data (timestamps are epoch milliseconds)
            Map<String, String> logTableFilters = new LinkedHashMap<>();
            logTableFilters.put("plugin_audit_log",
                    "createtime > EXTRACT(EPOCH FROM NOW())::BIGINT * 1000 - 3600000");
            logTableFilters.put("plugin_devicelog_log",
                    "createtime > EXTRACT(EPOCH FROM NOW())::BIGINT * 1000 - 3600000");

            List<String> pgDumpArgs = new ArrayList<>(Arrays.asList(
                    "pg_dump",
                    "-h", dbHost,
                    "-p", dbPort,
                    "-U", dbUser,
                    "--no-password",
                    "-d", dbName,
                    "--format=plain",
                    "--encoding=UTF8"
            ));
            for (String table : logTableFilters.keySet()) {
                pgDumpArgs.add("--exclude-table-data=" + table);
            }

            ProcessBuilder pb = new ProcessBuilder(pgDumpArgs);
            pb.environment().put("PGPASSWORD", dbPassword);
            pb.redirectErrorStream(false);

            Process process = pb.start();

            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            String filename = "brothers_mdm_backup_" + timestamp + ".sql";

            StreamingOutput stream = output -> {
                // Stream full dump (log table schemas included, but their data excluded)
                try (InputStream in = process.getInputStream()) {
                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    while ((bytesRead = in.read(buffer)) != -1) {
                        output.write(buffer, 0, bytesRead);
                    }
                }
                try {
                    int exitCode = process.waitFor();
                    if (exitCode != 0) {
                        log.error("pg_dump exited with code: {}", exitCode);
                    }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    log.warn("pg_dump wait interrupted");
                }

                // Append last 1 hour of each log table as COPY statements
                for (Map.Entry<String, String> entry : logTableFilters.entrySet()) {
                    String table = entry.getKey();
                    String filter = entry.getValue();

                    String copyHeader = "\n-- Last 1 hour of " + table + "\n" +
                            "COPY " + table + " FROM stdin;\n";
                    output.write(copyHeader.getBytes(StandardCharsets.UTF_8));

                    ProcessBuilder copyPb = new ProcessBuilder(
                            "psql",
                            "-h", dbHost,
                            "-p", dbPort,
                            "-U", dbUser,
                            "--no-password",
                            "-d", dbName,
                            "-c", "COPY (SELECT * FROM " + table + " WHERE " + filter + ") TO STDOUT"
                    );
                    copyPb.environment().put("PGPASSWORD", dbPassword);
                    copyPb.redirectErrorStream(false);

                    Process copyProcess = copyPb.start();
                    try (InputStream copyIn = copyProcess.getInputStream()) {
                        byte[] buffer = new byte[8192];
                        int bytesRead;
                        while ((bytesRead = copyIn.read(buffer)) != -1) {
                            output.write(buffer, 0, bytesRead);
                        }
                    }
                    try {
                        int exitCode = copyProcess.waitFor();
                        if (exitCode != 0) {
                            log.warn("psql COPY for {} exited with code: {}", table, exitCode);
                        }
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }

                    output.write("\\.\n".getBytes(StandardCharsets.UTF_8));
                }
                output.flush();
            };

            return javax.ws.rs.core.Response.ok(stream)
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                    .header("Content-Type", "application/octet-stream")
                    .build();

        } catch (Exception e) {
            log.error("Unexpected error during database export", e);
            return javax.ws.rs.core.Response.serverError().build();
        }
    }

    // =================================================================================================================
    @ApiOperation(
            value = "Import database",
            notes = "Imports a SQL file and replaces the current PostgreSQL database"
    )
    @POST
    @Path("/db/import")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Produces(MediaType.APPLICATION_JSON)
    public Response importDatabase(
            @FormDataParam("file") InputStream uploadedInputStream,
            @FormDataParam("file") FormDataContentDisposition fileDetail) {
        if (!SecurityContext.get().hasPermission("settings")) {
            log.error("Unauthorized attempt to import database by user " +
                    SecurityContext.get().getCurrentUserName());
            return Response.PERMISSION_DENIED();
        }
        File tempFile = null;
        try {
            String dbHost = getEnv("DB_HOST", "postgres");
            String dbPort = getEnv("DB_PORT", "5432");
            String dbName = getEnv("DB_NAME", "hmdm");
            String dbUser = getEnv("DB_USER", "hmdm");
            String dbPassword = getEnv("DB_PASSWORD", "hmdm");

            // Save uploaded SQL to a temp file
            tempFile = File.createTempFile("hmdm_import_", ".sql");
            try (FileOutputStream fos = new FileOutputStream(tempFile)) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = uploadedInputStream.read(buffer)) != -1) {
                    fos.write(buffer, 0, bytesRead);
                }
            }

            // Drop and recreate the database, then restore
            // We use psql to execute the SQL dump directly
            // First terminate all connections to the database
            ProcessBuilder terminatePb = new ProcessBuilder(
                    "psql",
                    "-h", dbHost,
                    "-p", dbPort,
                    "-U", dbUser,
                    "--no-password",
                    "-d", "postgres",
                    "-c", "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = '" + dbName + "' AND pid <> pg_backend_pid();"
            );
            terminatePb.environment().put("PGPASSWORD", dbPassword);
            terminatePb.redirectErrorStream(true);
            Process terminateProcess = terminatePb.start();
            terminateProcess.waitFor();

            // Drop database
            ProcessBuilder dropPb = new ProcessBuilder(
                    "psql",
                    "-h", dbHost,
                    "-p", dbPort,
                    "-U", dbUser,
                    "--no-password",
                    "-d", "postgres",
                    "-c", "DROP DATABASE IF EXISTS \"" + dbName + "\";"
            );
            dropPb.environment().put("PGPASSWORD", dbPassword);
            dropPb.redirectErrorStream(true);
            Process dropProcess = dropPb.start();
            byte[] dropOutput = dropProcess.getInputStream().readAllBytes();
            int dropExit = dropProcess.waitFor();
            if (dropExit != 0) {
                log.error("Failed to drop database: {}", new String(dropOutput));
                return Response.ERROR("error.db.import.failed");
            }

            // Recreate database
            ProcessBuilder createPb = new ProcessBuilder(
                    "psql",
                    "-h", dbHost,
                    "-p", dbPort,
                    "-U", dbUser,
                    "--no-password",
                    "-d", "postgres",
                    "-c", "CREATE DATABASE \"" + dbName + "\" WITH OWNER \"" + dbUser + "\" ENCODING 'UTF8';"
            );
            createPb.environment().put("PGPASSWORD", dbPassword);
            createPb.redirectErrorStream(true);
            Process createProcess = createPb.start();
            byte[] createOutput = createProcess.getInputStream().readAllBytes();
            int createExit = createProcess.waitFor();
            if (createExit != 0) {
                log.error("Failed to create database: {}", new String(createOutput));
                return Response.ERROR("error.db.import.failed");
            }

            // Run psql to import the SQL file
            ProcessBuilder importPb = new ProcessBuilder(
                    "psql",
                    "-h", dbHost,
                    "-p", dbPort,
                    "-U", dbUser,
                    "--no-password",
                    "-d", dbName,
                    "-f", tempFile.getAbsolutePath()
            );
            importPb.environment().put("PGPASSWORD", dbPassword);
            importPb.redirectErrorStream(true);
            Process importProcess = importPb.start();
            byte[] importOutput = importProcess.getInputStream().readAllBytes();
            int importExit = importProcess.waitFor();

            if (importExit != 0) {
                log.error("psql import failed (exit {}): {}", importExit, new String(importOutput));
                return Response.ERROR("error.db.import.failed");
            }

            log.info("Database import completed successfully by user: {}",
                    SecurityContext.get().getCurrentUserName());
            return Response.OK();

        } catch (Exception e) {
            log.error("Unexpected error during database import", e);
            return Response.INTERNAL_ERROR();
        } finally {
            if (tempFile != null) {
                tempFile.delete();
            }
        }
    }

    private static String getEnv(String name, String defaultValue) {
        String val = System.getenv(name);
        return (val != null && !val.isEmpty()) ? val : defaultValue;
    }
}
