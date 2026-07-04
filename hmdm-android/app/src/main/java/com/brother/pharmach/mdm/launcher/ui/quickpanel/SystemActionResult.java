/*
 * Brother Pharmamach MDM: Open Source Android MDM Software
 * https://h-mdm.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */

package com.brother.pharmach.mdm.launcher.ui.quickpanel;

/**
 * Outcome of a quick-panel system action. System toggle APIs differ per Android
 * version (6..16) and per OEM, so every action reports how it ended instead of
 * failing silently; the UI turns non-success results into a toast.
 */
public enum SystemActionResult {
    SUCCESS,
    PERMISSION_DENIED,
    UNSUPPORTED_ON_OS_VERSION,
    FAILURE
}
