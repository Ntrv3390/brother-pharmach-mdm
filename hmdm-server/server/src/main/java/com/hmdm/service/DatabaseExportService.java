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

package com.hmdm.service;

import com.google.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

/**
 * <p>Produces a full PostgreSQL SQL dump of the current database. Shared by the manual export endpoint
 * and the scheduled backup task so both generate identical output.</p>
 *
 * <p>Log tables are dumped with only the last hour of data (matching the manual export behaviour).</p>
 */
@Singleton
public class DatabaseExportService {

    private static final Logger log = LoggerFactory.getLogger(DatabaseExportService.class);

    /**
     * <p>Generates the complete SQL dump (schema + data) of the database as an in-memory byte array.</p>
     *
     * @return the raw {@code .sql} bytes.
     * @throws Exception if pg_dump cannot be launched or exits abnormally.
     */
    public byte[] generateSqlDump() throws Exception {
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

        ByteArrayOutputStream out = new ByteArrayOutputStream();

        // Full schema + data dump (log table data excluded above)
        try (InputStream in = process.getInputStream()) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = in.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
            }
        }
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IllegalStateException("pg_dump exited with code: " + exitCode);
        }

        // Append last 1 hour of each log table as COPY statements
        for (Map.Entry<String, String> entry : logTableFilters.entrySet()) {
            String table = entry.getKey();
            String filter = entry.getValue();

            String copyHeader = "\n-- Last 1 hour of " + table + "\n" +
                    "COPY " + table + " FROM stdin;\n";
            out.write(copyHeader.getBytes(StandardCharsets.UTF_8));

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
                    out.write(buffer, 0, bytesRead);
                }
            }
            int copyExit = copyProcess.waitFor();
            if (copyExit != 0) {
                log.warn("psql COPY for {} exited with code: {}", table, copyExit);
            }

            out.write("\\.\n".getBytes(StandardCharsets.UTF_8));
        }

        return out.toByteArray();
    }

    /**
     * <p>Gzips the given bytes.</p>
     */
    public byte[] gzip(byte[] data) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(baos)) {
            gzip.write(data);
        }
        return baos.toByteArray();
    }

    /**
     * <p>Builds a timestamped backup file name with the given extension (e.g. "sql" or "sql.gz").</p>
     */
    public String buildFilename(String extension) {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        return "brothers_mdm_backup_" + timestamp + "." + extension;
    }

    private static String getEnv(String name, String defaultValue) {
        String val = System.getenv(name);
        return (val != null && !val.isEmpty()) ? val : defaultValue;
    }
}
