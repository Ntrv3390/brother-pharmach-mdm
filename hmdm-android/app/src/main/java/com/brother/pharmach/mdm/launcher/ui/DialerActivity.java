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

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.CallLog;
import android.telecom.TelecomManager;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.brother.pharmach.mdm.launcher.Const;
import com.brother.pharmach.mdm.launcher.R;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/**
 * Default-dialer home with two tabs — <b>Keypad</b> and <b>Recents</b> (Contacts omitted by
 * design). Keypad places outgoing calls via Telecom; Recents lists the call log and taps a row to
 * call the number back. Registered as the DIAL / VIEW tel: / CALL_BUTTON handler.
 */
public class DialerActivity extends AppCompatActivity {

    private static final String TAG = "DialerActivity";
    private static final int REQ_CALL_PHONE = 2201;
    private static final int REQ_READ_CALL_LOG = 2202;
    private static final int RECENTS_LIMIT = 200;

    private static final int COLOR_TAB_ON = 0xFF111111;
    private static final int COLOR_TAB_OFF = 0xFF8A9199;

    private final StringBuilder number = new StringBuilder();

    private View keypadContainer;
    private View recentsContainer;
    private TextView numberView;
    private View backspace;

    private ImageView tabKeypadIcon, tabRecentsIcon;
    private TextView tabKeypadLabel, tabRecentsLabel;

    private RecyclerView recentsList;
    private TextView recentsEmpty;
    private RecentsAdapter recentsAdapter;
    private boolean showingKeypad;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_dialer);

        keypadContainer = findViewById(R.id.keypad_container);
        recentsContainer = findViewById(R.id.recents_container);
        numberView = findViewById(R.id.dial_number);
        backspace = findViewById(R.id.dial_backspace);
        tabKeypadIcon = findViewById(R.id.tab_keypad_icon);
        tabRecentsIcon = findViewById(R.id.tab_recents_icon);
        tabKeypadLabel = findViewById(R.id.tab_keypad_label);
        tabRecentsLabel = findViewById(R.id.tab_recents_label);
        recentsList = findViewById(R.id.recents_list);
        recentsEmpty = findViewById(R.id.recents_empty);

        setupKeypad();
        setupTabs();
        setupRecents();

        boolean prefilled = prefillFromIntent(getIntent());
        render();
        // Land on the keypad when a number was passed (e.g. tapping a tel: link), else Recents.
        if (prefilled) {
            showKeypad();
        } else {
            showRecents();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (prefillFromIntent(intent)) {
            render();
            showKeypad();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadRecents(); // refresh so a just-finished call shows up
    }

    // ---------------------------------------------------------------------------------------------
    // Tabs
    // ---------------------------------------------------------------------------------------------

    private void setupTabs() {
        findViewById(R.id.tab_keypad).setOnClickListener(v -> showKeypad());
        findViewById(R.id.tab_recents).setOnClickListener(v -> showRecents());
    }

    private void showKeypad() {
        showingKeypad = true;
        keypadContainer.setVisibility(View.VISIBLE);
        recentsContainer.setVisibility(View.GONE);
        applyTabColors();
    }

    private void showRecents() {
        showingKeypad = false;
        keypadContainer.setVisibility(View.GONE);
        recentsContainer.setVisibility(View.VISIBLE);
        applyTabColors();
    }

    private void applyTabColors() {
        int kp = showingKeypad ? COLOR_TAB_ON : COLOR_TAB_OFF;
        int rc = showingKeypad ? COLOR_TAB_OFF : COLOR_TAB_ON;
        tabKeypadIcon.setColorFilter(kp);
        tabKeypadLabel.setTextColor(kp);
        tabRecentsIcon.setColorFilter(rc);
        tabRecentsLabel.setTextColor(rc);
    }

    // ---------------------------------------------------------------------------------------------
    // Keypad
    // ---------------------------------------------------------------------------------------------

    private void setupKeypad() {
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

        findViewById(R.id.dial_call).setOnClickListener(v -> {
            String n = number.toString().trim();
            if (TextUtils.isEmpty(n)) {
                Toast.makeText(this, R.string.dialpad_no_number, Toast.LENGTH_SHORT).show();
                return;
            }
            placeCall(n);
        });
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

    private boolean prefillFromIntent(Intent intent) {
        if (intent == null || intent.getData() == null) {
            return false;
        }
        Uri data = intent.getData();
        if ("tel".equalsIgnoreCase(data.getScheme())) {
            String n = data.getSchemeSpecificPart();
            if (n != null) {
                number.setLength(0);
                number.append(Uri.decode(n));
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------------------------------------
    // Placing calls
    // ---------------------------------------------------------------------------------------------

    private void placeCall(String n) {
        Uri uri = Uri.fromParts("tel", n, null);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (checkSelfPermission(Manifest.permission.CALL_PHONE)
                        != PackageManager.PERMISSION_GRANTED) {
                    pendingCallNumber = n;
                    requestPermissions(new String[]{Manifest.permission.CALL_PHONE}, REQ_CALL_PHONE);
                    return;
                }
                TelecomManager tm = (TelecomManager) getSystemService(TELECOM_SERVICE);
                if (tm != null) {
                    tm.placeCall(uri, null);
                    return;
                }
            }
            startActivity(new Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(n))));
        } catch (SecurityException se) {
            Log.w(TAG, "placeCall permission error: " + se.getMessage());
            Toast.makeText(this, R.string.dialpad_call_failed, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Log.w(TAG, "placeCall failed: " + e.getMessage());
            Toast.makeText(this, R.string.dialpad_call_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private String pendingCallNumber;

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        boolean granted = grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (requestCode == REQ_CALL_PHONE && granted && pendingCallNumber != null) {
            String n = pendingCallNumber;
            pendingCallNumber = null;
            placeCall(n);
        } else if (requestCode == REQ_READ_CALL_LOG && granted) {
            loadRecents();
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Recents (call log)
    // ---------------------------------------------------------------------------------------------

    private void setupRecents() {
        recentsAdapter = new RecentsAdapter(this::placeCall);
        recentsList.setLayoutManager(new LinearLayoutManager(this));
        recentsList.setAdapter(recentsAdapter);
    }

    private void loadRecents() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && checkSelfPermission(Manifest.permission.READ_CALL_LOG)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.READ_CALL_LOG}, REQ_READ_CALL_LOG);
            return;
        }
        new Thread(() -> {
            final List<RecentsAdapter.Row> rows = queryCallLog();
            runOnUiThread(() -> {
                if (isFinishing()) {
                    return;
                }
                recentsAdapter.setRows(rows);
                boolean empty = rows.isEmpty();
                recentsEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
                recentsList.setVisibility(empty ? View.GONE : View.VISIBLE);
            });
        }, "recents-load").start();
    }

    private List<RecentsAdapter.Row> queryCallLog() {
        List<RecentsAdapter.Row> rows = new ArrayList<>();
        String[] projection = {
                CallLog.Calls.NUMBER,
                CallLog.Calls.CACHED_NAME,
                CallLog.Calls.TYPE,
                CallLog.Calls.DATE
        };
        Cursor c = null;
        try {
            c = getContentResolver().query(CallLog.Calls.CONTENT_URI, projection,
                    null, null, CallLog.Calls.DATE + " DESC");
            if (c == null) {
                return rows;
            }
            int idxNumber = c.getColumnIndex(CallLog.Calls.NUMBER);
            int idxName = c.getColumnIndex(CallLog.Calls.CACHED_NAME);
            int idxType = c.getColumnIndex(CallLog.Calls.TYPE);
            int idxDate = c.getColumnIndex(CallLog.Calls.DATE);

            String lastBucket = null;
            int count = 0;
            while (c.moveToNext() && count < RECENTS_LIMIT) {
                String num = idxNumber >= 0 ? c.getString(idxNumber) : null;
                String name = idxName >= 0 ? c.getString(idxName) : null;
                int type = idxType >= 0 ? c.getInt(idxType) : CallLog.Calls.INCOMING_TYPE;
                long date = idxDate >= 0 ? c.getLong(idxDate) : 0L;

                String bucket = dayBucket(date);
                if (!bucket.equals(lastBucket)) {
                    rows.add(RecentsAdapter.Row.header(bucket));
                    lastBucket = bucket;
                }

                String display = !TextUtils.isEmpty(name) ? name
                        : (!TextUtils.isEmpty(num) ? num : getString(R.string.unknown_caller));
                String subtitle = !TextUtils.isEmpty(name) ? num : null; // show number under a name
                rows.add(RecentsAdapter.Row.call(display, subtitle, formatTime(date),
                        iconForType(type), num));
                count++;
            }
        } catch (SecurityException se) {
            Log.w(TAG, "READ_CALL_LOG denied: " + se.getMessage());
        } catch (Exception e) {
            Log.w(TAG, "queryCallLog failed: " + e.getMessage());
        } finally {
            if (c != null) {
                c.close();
            }
        }
        return rows;
    }

    private int iconForType(int type) {
        switch (type) {
            case CallLog.Calls.OUTGOING_TYPE:
                return R.drawable.ic_log_outgoing;
            case CallLog.Calls.MISSED_TYPE:
                return R.drawable.ic_log_missed;
            case CallLog.Calls.REJECTED_TYPE:
            case CallLog.Calls.BLOCKED_TYPE:
                return R.drawable.ic_log_rejected;
            case CallLog.Calls.INCOMING_TYPE:
            default:
                return R.drawable.ic_log_incoming;
        }
    }

    /** "Today" / "Yesterday" / "12 Jul" bucket for grouping. */
    private String dayBucket(long millis) {
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        long startOfToday = cal.getTimeInMillis();
        long startOfYesterday = startOfToday - 24L * 60 * 60 * 1000;
        if (millis >= startOfToday) {
            return getString(R.string.recents_today);
        } else if (millis >= startOfYesterday) {
            return getString(R.string.recents_yesterday);
        }
        return new SimpleDateFormat("d MMM", Locale.getDefault()).format(millis);
    }

    private String formatTime(long millis) {
        return new SimpleDateFormat("h:mm a", Locale.getDefault())
                .format(millis).toLowerCase(Locale.getDefault());
    }
}
