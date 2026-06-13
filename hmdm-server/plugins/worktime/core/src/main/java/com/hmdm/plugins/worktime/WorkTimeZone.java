package com.hmdm.plugins.worktime;

import java.time.ZoneId;

/** Shared timezone for all worktime plugin components. Override with -Dworktime.timezone=<zone>. */
public final class WorkTimeZone {
    private WorkTimeZone() {}

    public static final ZoneId ZONE = ZoneId.of(
            System.getProperty("worktime.timezone", "UTC"));
}
