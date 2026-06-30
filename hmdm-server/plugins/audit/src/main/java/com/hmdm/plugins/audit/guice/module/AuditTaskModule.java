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

package com.hmdm.plugins.audit.guice.module;

import com.google.inject.Inject;
import com.hmdm.plugin.PluginTaskModule;
import com.hmdm.plugins.audit.persistence.AuditDAO;
import com.hmdm.util.BackgroundTaskRunnerService;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.TimeUnit;

/**
 * <p>A module used for initializing the background tasks for the Audit plugin.</p>
 */
public class AuditTaskModule implements PluginTaskModule {

    private final AuditDAO auditDAO;
    private final BackgroundTaskRunnerService taskRunner;

    @Inject
    public AuditTaskModule(AuditDAO auditDAO, BackgroundTaskRunnerService taskRunner) {
        this.auditDAO = auditDAO;
        this.taskRunner = taskRunner;
    }

    /**
     * <p>Schedules a task at midnight every day to hard-delete all audit log records
     * older than 48 hours (2 days) across all customers.</p>
     */
    @Override
    public void init() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime nextMidnight = now.toLocalDate().plusDays(1).atStartOfDay();
        long initialDelaySeconds = ChronoUnit.SECONDS.between(now, nextMidnight);
        long periodSeconds = 24L * 60 * 60;
        taskRunner.submitRepeatableTask(
                () -> auditDAO.purgeOldAuditRecords(48),
                initialDelaySeconds,
                periodSeconds,
                TimeUnit.SECONDS
        );
    }
}
