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

package com.brother.pharmach.mdm.launcher.ui;

import android.app.Activity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.brother.pharmach.mdm.launcher.R;
import com.brother.pharmach.mdm.launcher.util.AppInfo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ViewPager2 adapter that renders the launcher's app grid across horizontally-swipeable pages.
 * Each page is a non-scrolling {@link GridLayoutManager} RecyclerView filled with a slice of the
 * full app list, so all of the existing per-icon logic (icon loading, sizing, work-time
 * filtering, tap/long-tap handling) is reused verbatim via {@link MainAppListAdapter}.
 */
public class PagedAppListAdapter extends RecyclerView.Adapter<PagedAppListAdapter.PageViewHolder> {

    private final Activity activity;
    private final BaseAppListAdapter.OnAppChooseListener appChooseListener;
    private final BaseAppListAdapter.SwitchAdapterListener switchAdapterListener;

    private List<List<AppInfo>> pages = new ArrayList<>();
    private int spanCount = 1;

    // position -> the page's inner adapter, so the activity can route hardware-key navigation
    // to the page currently on screen.
    private final Map<Integer, MainAppListAdapter> pageAdapters = new HashMap<>();

    public PagedAppListAdapter(Activity activity,
                               BaseAppListAdapter.OnAppChooseListener appChooseListener,
                               BaseAppListAdapter.SwitchAdapterListener switchAdapterListener) {
        this.activity = activity;
        this.appChooseListener = appChooseListener;
        this.switchAdapterListener = switchAdapterListener;
    }

    /**
     * Splits the full app list into pages of {@code spanCount * rows} items and refreshes the pager.
     */
    public void setData(List<AppInfo> allApps, int spanCount, int rows) {
        this.spanCount = Math.max(1, spanCount);
        int perPage = Math.max(1, this.spanCount * Math.max(1, rows));
        pages = new ArrayList<>();
        if (allApps != null) {
            for (int i = 0; i < allApps.size(); i += perPage) {
                pages.add(new ArrayList<>(allApps.subList(i, Math.min(i + perPage, allApps.size()))));
            }
        }
        if (pages.isEmpty()) {
            pages.add(new ArrayList<>());
        }
        pageAdapters.clear();
        notifyDataSetChanged();
    }

    public int getPageCount() {
        return pages.size();
    }

    public MainAppListAdapter getPageAdapter(int position) {
        return pageAdapters.get(position);
    }

    @NonNull
    @Override
    public PageViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.page_app_grid, parent, false);
        return new PageViewHolder((RecyclerView) view);
    }

    @Override
    public void onBindViewHolder(@NonNull PageViewHolder holder, int position) {
        MainAppListAdapter adapter = new MainAppListAdapter(
                activity, appChooseListener, switchAdapterListener, pages.get(position));
        adapter.setSpanCount(spanCount);
        holder.recyclerView.setLayoutManager(new GridLayoutManager(activity, spanCount));
        holder.recyclerView.setAdapter(adapter);
        pageAdapters.put(position, adapter);
    }

    @Override
    public int getItemCount() {
        return pages.size();
    }

    static final class PageViewHolder extends RecyclerView.ViewHolder {
        final RecyclerView recyclerView;

        PageViewHolder(RecyclerView itemView) {
            super(itemView);
            this.recyclerView = itemView;
        }
    }
}
