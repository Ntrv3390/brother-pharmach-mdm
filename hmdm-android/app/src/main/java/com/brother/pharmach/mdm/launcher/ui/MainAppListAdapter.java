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

import com.brother.pharmach.mdm.launcher.util.AppInfo;

import java.util.List;

/**
 * Created by Ivan Lozenko on 21.02.2017.
 */

public class MainAppListAdapter extends BaseAppListAdapter {

    public MainAppListAdapter(Activity parentActivity, OnAppChooseListener appChooseListener, SwitchAdapterListener switchAdapterListener) {
        super(parentActivity, appChooseListener, switchAdapterListener);
        items = AppShortcutManager.getInstance().getInstalledApps(parentActivity, false);
        initShortcuts();
    }

    /**
     * Builds an adapter over a pre-sliced list of apps — used to render a single page of the
     * paged home screen (see {@link PagedAppListAdapter}) instead of rebuilding the whole list.
     */
    public MainAppListAdapter(Activity parentActivity, OnAppChooseListener appChooseListener, SwitchAdapterListener switchAdapterListener, List<AppInfo> items) {
        super(parentActivity, appChooseListener, switchAdapterListener);
        this.items = items;
        initShortcuts();
    }
}
