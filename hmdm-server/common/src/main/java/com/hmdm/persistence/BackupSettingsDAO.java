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

package com.hmdm.persistence;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.hmdm.persistence.domain.BackupSettings;
import com.hmdm.persistence.mapper.BackupSettingsMapper;

/**
 * <p>Data access object for the global (single-row) database backup configuration.</p>
 */
@Singleton
public class BackupSettingsDAO {

    private final BackupSettingsMapper mapper;

    @Inject
    public BackupSettingsDAO(BackupSettingsMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * <p>Returns the current backup configuration, or {@code null} if it was never saved.</p>
     */
    public BackupSettings getConfig() {
        return mapper.getConfig();
    }

    /**
     * <p>Returns the configured backup email, or {@code null}/empty if none was saved.</p>
     */
    public String getEmail() {
        BackupSettings config = mapper.getConfig();
        return config == null ? null : config.getEmail();
    }

    /**
     * <p>Creates or updates the destination email for database backups.</p>
     */
    public void saveEmail(String email, long updatedAt) {
        BackupSettings settings = new BackupSettings();
        settings.setEmail(email);
        settings.setUpdatedAt(updatedAt);
        mapper.saveConfig(settings);
    }

    /**
     * <p>Records the timestamp of the last successfully emailed backup.</p>
     */
    public void updateLastSent(long lastSentAt) {
        mapper.updateLastSent(lastSentAt);
    }
}
