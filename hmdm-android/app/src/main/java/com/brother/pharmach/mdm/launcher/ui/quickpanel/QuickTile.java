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

import android.content.Context;

/**
 * One toggle in the quick panel (grid tile or top pill).
 *
 * Tiles are registered in QuickPanelController; isVisible() lets the MDM policy
 * layer (ServerConfig) hide a tile when the corresponding radio is pinned by the
 * server, so the user can never fight the StatusControlService enforcement loop.
 */
public abstract class QuickTile {
    public final String id;
    public final int iconRes;
    public final int labelRes;

    public QuickTile(String id, int iconRes, int labelRes) {
        this.id = id;
        this.iconRes = iconRes;
        this.labelRes = labelRes;
    }

    /** Whether the tile is shown at all (policy-pinned radios return false). */
    public boolean isVisible(Context context) {
        return true;
    }

    /** Current on/off state, used to pick the active/inactive background. */
    public abstract boolean isActive(Context context);

    /** Toggle to the opposite state. Must never throw. */
    public abstract SystemActionResult toggle(Context context);
}
