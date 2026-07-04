package com.brother.pharmach.mdm.launcher.util;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class NotificationSuppressionPolicyTest {

    @Test
    public void suppressesNotificationsFromOtherPackages() {
        String selfPackage = "com.example.launcher";

        assertTrue(NotificationSuppressionPolicy.shouldSuppressNotification(
                "com.android.systemui", selfPackage));
        assertTrue(NotificationSuppressionPolicy.shouldSuppressNotification(
                "com.google.android.apps.messaging", selfPackage));
        assertFalse(NotificationSuppressionPolicy.shouldSuppressNotification(
                selfPackage, selfPackage));
    }

    @Test
    public void ignoresBlankPackageNames() {
        assertFalse(NotificationSuppressionPolicy.shouldSuppressNotification(null, "com.example.launcher"));
        assertFalse(NotificationSuppressionPolicy.shouldSuppressNotification("", "com.example.launcher"));
    }
}
