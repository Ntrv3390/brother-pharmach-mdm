/*
 * Brother Pharmamach MDM: Open Source Android MDM Software
 * https://h-mdm.com
 *
 * Copyright (C) 2019 Headwind Solutions LLC (http://h-sms.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.brother.pharmach.mdm.launcher.util;

import android.view.View;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

/**
 * Window-inset helper. Apps targeting SDK 35 (Android 15) draw edge-to-edge by default, so a screen
 * that does not consume insets renders under the status bar / display cutout (notch) and behind the
 * navigation bar / gesture handle. This pads the given root by the system-bar + cutout insets so
 * content stays inside the safe area on both button-nav and gesture-nav devices, API 23-36.
 */
public final class InsetsUtils {

    private InsetsUtils() {}

    /**
     * Adds system-bar + display-cutout insets as padding on top of the view's existing padding.
     * Call once after setContentView with the screen's root view.
     */
    public static void applySystemBarPadding(final View root) {
        if (root == null) {
            return;
        }
        final int basePl = root.getPaddingLeft();
        final int basePt = root.getPaddingTop();
        final int basePr = root.getPaddingRight();
        final int basePb = root.getPaddingBottom();

        ViewCompat.setOnApplyWindowInsetsListener(root, (v, windowInsets) -> {
            Insets bars = windowInsets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            v.setPadding(basePl + bars.left, basePt + bars.top,
                    basePr + bars.right, basePb + bars.bottom);
            return windowInsets;
        });
        ViewCompat.requestApplyInsets(root);
    }
}
