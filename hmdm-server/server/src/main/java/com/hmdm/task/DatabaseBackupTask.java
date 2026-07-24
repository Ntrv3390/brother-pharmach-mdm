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

package com.hmdm.task;

import com.google.inject.Inject;
import com.hmdm.persistence.BackupSettingsDAO;
import com.hmdm.persistence.domain.BackupSettings;
import com.hmdm.service.DatabaseExportService;
import com.hmdm.service.EmailService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * <p>Runs on a short interval (hourly) and, once per week, emails a database backup.</p>
 *
 * <p>The backup is sent on the first tick at or after <strong>12:00 Sunday IST (Asia/Kolkata)</strong>,
 * but only when both a destination email is configured and SMTP is configured. The last-sent timestamp
 * stored in {@code backup_settings} guarantees at most one email per Sunday, and makes the job resilient
 * to server restarts (a missed noon tick is caught by any later tick the same Sunday).</p>
 */
public class DatabaseBackupTask implements Runnable {

    private static final Logger logger = LoggerFactory.getLogger(DatabaseBackupTask.class);

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final BackupSettingsDAO backupSettingsDAO;
    private final DatabaseExportService databaseExportService;
    private final EmailService emailService;

    @Inject
    public DatabaseBackupTask(BackupSettingsDAO backupSettingsDAO,
                              DatabaseExportService databaseExportService,
                              EmailService emailService) {
        this.backupSettingsDAO = backupSettingsDAO;
        this.databaseExportService = databaseExportService;
        this.emailService = emailService;
    }

    @Override
    public void run() {
        try {
            ZonedDateTime nowIst = ZonedDateTime.now(IST);

            // Only fire at or after noon on Sundays (IST)
            if (nowIst.getDayOfWeek() != DayOfWeek.SUNDAY || nowIst.getHour() < 12) {
                return;
            }

            BackupSettings config = backupSettingsDAO.getConfig();
            String email = config == null ? null : config.getEmail();

            // No destination email -> nothing to send
            if (email == null || email.trim().isEmpty()) {
                return;
            }

            // Email configured but SMTP is not -> the weekly job must not run
            if (!emailService.isConfigured()) {
                logger.warn("Weekly database backup skipped: SMTP is not configured (destination email is set to {})", email);
                return;
            }

            // Already sent this Sunday (IST)?
            if (config.getLastSentAt() != null) {
                LocalDate lastSentDate = Instant.ofEpochMilli(config.getLastSentAt()).atZone(IST).toLocalDate();
                if (lastSentDate.equals(nowIst.toLocalDate())) {
                    return;
                }
            }

            logger.info("Running weekly database backup, emailing to {}", email);

            byte[] dump = databaseExportService.generateSqlDump();
            byte[] gzipped = databaseExportService.gzip(dump);
            String filename = databaseExportService.buildFilename("sql.gz");

            String timestamp = nowIst.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) + " IST";
            String subject = "Headwind MDM weekly database backup - " + timestamp;
            String body = "<p>Attached is the automatic weekly Headwind MDM database backup generated on " +
                    timestamp + ".</p>" +
                    "<p>The dump is gzip-compressed (" + (gzipped.length / 1024) + " KB). " +
                    "Rename to <code>.sql</code> after decompressing to restore it.</p>";

            boolean sent = emailService.sendEmailWithAttachment(email, subject, body, gzipped, filename, "application/gzip");
            if (sent) {
                backupSettingsDAO.updateLastSent(System.currentTimeMillis());
                logger.info("Weekly database backup emailed successfully to {}", email);
            } else {
                logger.error("Weekly database backup failed to send to {} (will retry on the next tick this Sunday)", email);
            }
        } catch (Exception e) {
            logger.error("Unexpected error during weekly database backup", e);
        }
    }
}
