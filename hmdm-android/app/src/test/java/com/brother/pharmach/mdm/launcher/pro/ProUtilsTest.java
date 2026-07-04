package com.brother.pharmach.mdm.launcher.pro;

import static org.junit.Assert.assertEquals;

import android.os.Build;
import android.view.WindowInsetsController;

import org.junit.Test;

public class ProUtilsTest {

    @Test
    public void usesTouchBehaviorForAndroid12AndAbove() {
        assertEquals(
                WindowInsetsController.BEHAVIOR_SHOW_BARS_BY_TOUCH,
                ProUtils.getSystemBarsBehavior(Build.VERSION_CODES.S)
        );
    }

    @Test
    public void keepsTransientBehaviorForOlderPlatforms() {
        assertEquals(
                WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE,
                ProUtils.getSystemBarsBehavior(Build.VERSION_CODES.Q)
        );
    }
}
