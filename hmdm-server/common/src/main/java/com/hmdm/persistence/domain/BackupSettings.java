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

package com.hmdm.persistence.domain;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;

import java.io.Serializable;

/**
 * <p>Global (single-row) configuration for scheduled and manual database backups sent by email.</p>
 */
@ApiModel(description = "Destination email settings for database backups")
public class BackupSettings implements Serializable {

    private static final long serialVersionUID = 1L;

    @ApiModelProperty("An identifier of the record (always a single row)")
    private Integer id;

    @ApiModelProperty("The destination email address for database backups")
    private String email;

    @ApiModelProperty("Timestamp (epoch millis) when a backup was last emailed successfully")
    private Long lastSentAt;

    @ApiModelProperty("Timestamp (epoch millis) when this configuration was last updated")
    private Long updatedAt;

    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public Long getLastSentAt() {
        return lastSentAt;
    }

    public void setLastSentAt(Long lastSentAt) {
        this.lastSentAt = lastSentAt;
    }

    public Long getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Long updatedAt) {
        this.updatedAt = updatedAt;
    }

    @Override
    public String toString() {
        return "BackupSettings{" +
                "id=" + id +
                ", email='" + email + '\'' +
                ", lastSentAt=" + lastSentAt +
                ", updatedAt=" + updatedAt +
                '}';
    }
}
