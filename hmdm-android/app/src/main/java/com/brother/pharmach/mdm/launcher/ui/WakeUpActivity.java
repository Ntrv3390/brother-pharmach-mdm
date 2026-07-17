package com.brother.pharmach.mdm.launcher.ui;

import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowManager;

import com.brother.pharmach.mdm.launcher.Const;
import com.brother.pharmach.mdm.launcher.util.RemoteLogger;

/**
 * Invisible, short-lived activity whose only job is to turn the screen on — the one wake
 * mechanism modern Android exposes as a public, non-deprecated API (setTurnScreenOn /
 * setShowWhenLocked, API 27+). A lit screen ends Doze entirely, which unblocks GNSS for the
 * urgent location capture. This is the same mechanism alarm-clock apps use to wake the phone,
 * and unlike the deprecated SCREEN_BRIGHT wake lock it is honored by OEM skins (ColorOS/MIUI).
 *
 * Launched by DozeExitHelper/DozeExitReceiver. Finishes itself after ~3 seconds; translucent
 * and excluded from recents, so the kiosk UI underneath is never disturbed.
 */
public class WakeUpActivity extends Activity {

    private static final long AUTO_FINISH_MS = 3_000L;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        RemoteLogger.log(this, Const.LOG_INFO,
                "WakeUpActivity: screen wake requested (turnScreenOn API) — finishing in "
                        + AUTO_FINISH_MS + "ms");
        new Handler(Looper.getMainLooper()).postDelayed(this::finish, AUTO_FINISH_MS);
    }
}
