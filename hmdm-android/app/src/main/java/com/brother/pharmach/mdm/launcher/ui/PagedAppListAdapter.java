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

    private List<List<AppInfo>> slicePages(List<AppInfo> allApps, int span, int rows) {
        int perPage = Math.max(1, span * Math.max(1, rows));
        List<List<AppInfo>> result = new ArrayList<>();
        if (allApps != null) {
            for (int i = 0; i < allApps.size(); i += perPage) {
                result.add(new ArrayList<>(allApps.subList(i, Math.min(i + perPage, allApps.size()))));
            }
        }
        if (result.isEmpty()) {
            result.add(new ArrayList<>());
        }
        return result;
    }

    /**
     * Splits the full app list into pages and does a full (re)bind. Used for the first render.
     */
    public void setData(List<AppInfo> allApps, int spanCount, int rows) {
        this.spanCount = Math.max(1, spanCount);
        pages = slicePages(allApps, this.spanCount, rows);
        pageAdapters.clear();
        notifyDataSetChanged();
    }

    /**
     * Smoothly updates the pages in place. When the page count and column count are unchanged,
     * each visible page diffs its own items (only added/removed icons animate). Only a change in
     * the number of pages or columns falls back to a full rebind. This avoids the whole-screen
     * "refresh" flash that recreating the adapter used to cause on every worktime transition.
     */
    public void updateData(List<AppInfo> allApps, int columns, int rows) {
        int newSpan = Math.max(1, columns);
        List<List<AppInfo>> newPages = slicePages(allApps, newSpan, rows);
        boolean structureChanged = newSpan != this.spanCount || newPages.size() != pages.size();
        this.spanCount = newSpan;
        this.pages = newPages;
        if (structureChanged) {
            pageAdapters.clear();
            notifyDataSetChanged();
        } else {
            for (int i = 0; i < pages.size(); i++) {
                MainAppListAdapter pageAdapter = pageAdapters.get(i);
                if (pageAdapter != null) {
                    pageAdapter.updateItems(pages.get(i));
                } else {
                    notifyItemChanged(i);
                }
            }
        }
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
        // A page never scrolls: the row count is chosen so every item fits the visible height,
        // and disabling vertical scroll also prevents a stray scroll gesture from fighting the
        // swipe-down-to-open-status-bar gesture.
        GridLayoutManager lm = new GridLayoutManager(activity, spanCount) {
            @Override
            public boolean canScrollVertically() {
                return false;
            }
        };
        holder.recyclerView.setLayoutManager(lm);
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
