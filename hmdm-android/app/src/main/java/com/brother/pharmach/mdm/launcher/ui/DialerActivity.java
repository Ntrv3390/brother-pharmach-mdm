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

import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.telecom.TelecomManager;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.brother.pharmach.mdm.launcher.Const;
import com.brother.pharmach.mdm.launcher.R;

/**
 * Dial pad for placing outgoing calls while the app is the default phone app. Registered as the
 * {@code ACTION_DIAL} / {@code ACTION_VIEW tel:} / {@code CALL_BUTTON} handler, so tapping the
 * phone shortcut or a {@code tel:} link opens this screen. Placing a call routes through Telecom
 * to our own {@code CustomInCallService} → {@code IncomingCallActivity} (the outgoing/in-call UI).
 */
public class DialerActivity extends AppCompatActivity {

    private static final String TAG = "DialerActivity";
    private static final int REQ_CALL_PHONE = 2201;

    private final StringBuilder number = new StringBuilder();
    private TextView numberView;
    private View backspace;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_dialpad);

        numberView = findViewById(R.id.dial_number);
        backspace = findViewById(R.id.dial_backspace);

        bindKey(R.id.key_1, '1');
        bindKey(R.id.key_2, '2');
        bindKey(R.id.key_3, '3');
        bindKey(R.id.key_4, '4');
        bindKey(R.id.key_5, '5');
        bindKey(R.id.key_6, '6');
        bindKey(R.id.key_7, '7');
        bindKey(R.id.key_8, '8');
        bindKey(R.id.key_9, '9');
        bindKey(R.id.key_star, '*');
        bindKey(R.id.key_hash, '#');

        // 0 appends '0'; long-press appends '+' (international prefix).
        View key0 = findViewById(R.id.key_0);
        key0.setOnClickListener(v -> append('0'));
        key0.setOnLongClickListener(v -> {
            append('+');
            return true;
        });

        backspace.setOnClickListener(v -> {
            if (number.length() > 0) {
                number.deleteCharAt(number.length() - 1);
                render();
            }
        });
        backspace.setOnLongClickListener(v -> {
            number.setLength(0);
            render();
            return true;
        });

        findViewById(R.id.dial_call).setOnClickListener(v -> placeCall());

        prefillFromIntent(getIntent());
        render();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        prefillFromIntent(intent);
        render();
    }

    /** Pre-fill the number from a tel: DIAL/VIEW intent (e.g. tapping a phone-number link). */
    private void prefillFromIntent(Intent intent) {
        if (intent == null || intent.getData() == null) {
            return;
        }
        Uri data = intent.getData();
        if ("tel".equalsIgnoreCase(data.getScheme())) {
            String n = data.getSchemeSpecificPart();
            if (n != null) {
                number.setLength(0);
                number.append(Uri.decode(n));
            }
        }
    }

    private void bindKey(int id, char c) {
        View v = findViewById(id);
        if (v != null) {
            v.setOnClickListener(view -> append(c));
        }
    }

    private void append(char c) {
        number.append(c);
        render();
    }

    private void render() {
        numberView.setText(number.toString());
        backspace.setVisibility(number.length() > 0 ? View.VISIBLE : View.INVISIBLE);
    }

    private void placeCall() {
        String n = number.toString().trim();
        if (TextUtils.isEmpty(n)) {
            Toast.makeText(this, R.string.dialpad_no_number, Toast.LENGTH_SHORT).show();
            return;
        }
        Uri uri = Uri.fromParts("tel", n, null);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (checkSelfPermission(android.Manifest.permission.CALL_PHONE)
                        != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{android.Manifest.permission.CALL_PHONE},
                            REQ_CALL_PHONE);
                    return;
                }
                TelecomManager tm = (TelecomManager) getSystemService(TELECOM_SERVICE);
                if (tm != null) {
                    tm.placeCall(uri, null);
                    // The in-call UI (IncomingCallActivity) takes over via the InCallService; leave
                    // the dial pad so the number is not left on screen behind the call UI.
                    finish();
                    return;
                }
            }
            // Fallback (pre-M or no TelecomManager): direct ACTION_CALL.
            Intent call = new Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(n)));
            startActivity(call);
            finish();
        } catch (SecurityException se) {
            Log.w(TAG, "placeCall permission error: " + se.getMessage());
            Toast.makeText(this, R.string.dialpad_call_failed, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Log.w(TAG, "placeCall failed: " + e.getMessage());
            Toast.makeText(this, R.string.dialpad_call_failed, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_CALL_PHONE
                && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            placeCall();
        }
    }
}
