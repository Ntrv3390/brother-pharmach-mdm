package com.brother.pharmach.mdm.launcher.ui;

import android.app.Activity;
import android.view.LayoutInflater;

import com.brother.pharmach.mdm.launcher.util.AppInfo;

import java.util.List;

public class BottomAppListAdapter extends BaseAppListAdapter {
    private LayoutInflater layoutInflater;

    public BottomAppListAdapter(Activity parentActivity, OnAppChooseListener appChooseListener, SwitchAdapterListener switchAdapterListener) {
        super(parentActivity, appChooseListener, switchAdapterListener);
        items = AppShortcutManager.getInstance().getInstalledApps(parentActivity, true);
        initShortcuts();
    }

    /**
     * Builds the dock adapter from an already-enumerated list, so the expensive package scan can
     * run once on a background thread instead of on the UI thread. See MainActivity.showContent.
     */
    public BottomAppListAdapter(Activity parentActivity, OnAppChooseListener appChooseListener, SwitchAdapterListener switchAdapterListener, List<AppInfo> items) {
        super(parentActivity, appChooseListener, switchAdapterListener);
        this.items = items;
        initShortcuts();
    }
}
