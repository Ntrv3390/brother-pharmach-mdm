package com.brother.pharmach.mdm.launcher;

public final class Constants {

    private Constants() {}

    // Broadcast sent by BatteryOptimizationMonitor when the device becomes compliant
    public static final String ACTION_COMPLIANCE_RESTORED =
            "com.brother.pharmach.mdm.launcher.ACTION_COMPLIANCE_RESTORED";

    // Polling interval for the battery optimization compliance check (30 seconds)
    public static final long BATTERY_POLL_INTERVAL_MS = 30_000L;

    // Notification channel and ID for the compliance foreground service
    public static final String NOTIFICATION_CHANNEL_ID_COMPLIANCE = "mdm_compliance_monitor";
    public static final int NOTIFICATION_ID_COMPLIANCE = 9001;
}
