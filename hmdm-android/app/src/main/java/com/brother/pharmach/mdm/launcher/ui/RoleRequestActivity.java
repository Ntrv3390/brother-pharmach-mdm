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

package com.brother.pharmach.mdm.launcher.ui;

import android.app.Activity;
import android.app.role.RoleManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.telecom.TelecomManager;
import android.util.Log;

import com.brother.pharmach.mdm.launcher.Const;
import com.brother.pharmach.mdm.launcher.helper.DefaultDialerHelper;
import com.brother.pharmach.mdm.launcher.phone.DefaultDialerGate;
import com.brother.pharmach.mdm.launcher.util.RemoteLogger;

/**
 * Tiny transparent activity whose only job is to present the system default-dialer picker <b>the
 * right way</b> — via {@code startActivityForResult} from a real Activity.
 *
 * <p>The overlay gate can't do this: the PermissionController that shows "Set as default phone app"
 * reads the calling package, and a plain {@code startActivity()} from an overlay/service has no
 * calling activity, so the dialog is rejected and closes instantly (that is why the green button
 * "did nothing" on Android 14/15/16). Routing the request through this activity fixes that.
 *
 * <p>After the picker returns it verifies whether the role was actually granted and tells
 * {@link DefaultDialerGate} to keep blocking (and disable the button + guide the user to the manual
 * Settings path) if it was not.
 */
public class RoleRequestActivity extends Activity {

    private static final String TAG = "RoleRequestActivity";
    private static final int REQ_DIALER = 3301;

    public static Intent newIntent(Context context) {
        Intent i = new Intent(context, RoleRequestActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        return i;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || DefaultDialerHelper.isDefaultDialer(this)) {
            DefaultDialerGate.onRoleRequestFinished(getApplicationContext());
            finish();
            return;
        }
        try {
            Intent intent = null;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                RoleManager rm = (RoleManager) getSystemService(Context.ROLE_SERVICE);
                if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_DIALER)) {
                    intent = rm.createRequestRoleIntent(RoleManager.ROLE_DIALER);
                }
            }
            if (intent == null) {
                // API 23-28
                intent = new Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER);
                intent.putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME,
                        getPackageName());
            }
            startActivityForResult(intent, REQ_DIALER);
            RemoteLogger.log(this, Const.LOG_INFO,
                    "Default-dialer picker shown (startActivityForResult from RoleRequestActivity)");
        } catch (Exception e) {
            Log.w(TAG, "role request failed: " + e.getMessage());
            RemoteLogger.log(this, Const.LOG_ERROR,
                    "Role request launch failed: " + e.getMessage());
            DefaultDialerGate.onRoleRequestFinished(getApplicationContext());
            finish();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_DIALER) {
            finish();
            return;
        }
        boolean nowDefault = DefaultDialerHelper.isDefaultDialer(this);
        RemoteLogger.log(this, Const.LOG_INFO,
                "Default-dialer picker returned: resultCode=" + resultCode
                        + " isDefaultDialerNow=" + nowDefault);
        if (nowDefault) {
            DefaultDialerHelper.grantCallPermissions(this);
        }
        // Either way, end the launch grace and re-evaluate: granted → gate dismisses; cancelled →
        // gate re-blocks immediately (the green button stays enabled — the picker path works).
        DefaultDialerGate.onRoleRequestFinished(getApplicationContext());
        finish();
    }
}
