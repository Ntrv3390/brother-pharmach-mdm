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

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.brother.pharmach.mdm.launcher.R;

import java.util.ArrayList;
import java.util.List;

/** Recents (call-log) list: date-section headers + tappable call rows. */
public class RecentsAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_CALL = 1;

    /** One row — either a date header or a call entry. */
    public static class Row {
        final boolean header;
        final String title;      // header text OR caller name/number
        final String subtitle;   // number (when name is shown) — may be null
        final String time;       // formatted time
        final int typeIcon;      // drawable res for the call type
        final String number;     // dial-back target

        private Row(boolean header, String title, String subtitle, String time,
                    int typeIcon, String number) {
            this.header = header;
            this.title = title;
            this.subtitle = subtitle;
            this.time = time;
            this.typeIcon = typeIcon;
            this.number = number;
        }

        public static Row header(String title) {
            return new Row(true, title, null, null, 0, null);
        }

        public static Row call(String title, String subtitle, String time, int typeIcon,
                               String number) {
            return new Row(false, title, subtitle, time, typeIcon, number);
        }
    }

    public interface OnCallClick {
        void onCall(String number);
    }

    private final List<Row> rows = new ArrayList<>();
    private final OnCallClick callback;

    public RecentsAdapter(OnCallClick callback) {
        this.callback = callback;
    }

    public void setRows(List<Row> newRows) {
        rows.clear();
        if (newRows != null) {
            rows.addAll(newRows);
        }
        notifyDataSetChanged();
    }

    @Override
    public int getItemViewType(int position) {
        return rows.get(position).header ? TYPE_HEADER : TYPE_CALL;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == TYPE_HEADER) {
            return new HeaderVH(inflater.inflate(R.layout.item_call_log_header, parent, false));
        }
        return new CallVH(inflater.inflate(R.layout.item_call_log, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        Row row = rows.get(position);
        if (holder instanceof HeaderVH) {
            ((HeaderVH) holder).header.setText(row.title);
            return;
        }
        CallVH vh = (CallVH) holder;
        vh.name.setText(row.title);
        vh.time.setText(row.time);
        vh.typeIcon.setImageResource(row.typeIcon);
        if (row.subtitle != null && !row.subtitle.isEmpty()) {
            vh.subtitle.setVisibility(View.VISIBLE);
            vh.subtitle.setText(row.subtitle);
        } else {
            vh.subtitle.setVisibility(View.GONE);
        }
        vh.itemView.setOnClickListener(v -> {
            if (callback != null && row.number != null && !row.number.isEmpty()) {
                callback.onCall(row.number);
            }
        });
    }

    @Override
    public int getItemCount() {
        return rows.size();
    }

    static class HeaderVH extends RecyclerView.ViewHolder {
        final TextView header;
        HeaderVH(@NonNull View itemView) {
            super(itemView);
            header = itemView.findViewById(R.id.log_header);
        }
    }

    static class CallVH extends RecyclerView.ViewHolder {
        final ImageView typeIcon;
        final TextView name;
        final TextView subtitle;
        final TextView time;
        CallVH(@NonNull View itemView) {
            super(itemView);
            typeIcon = itemView.findViewById(R.id.log_type_icon);
            name = itemView.findViewById(R.id.log_name);
            subtitle = itemView.findViewById(R.id.log_subtitle);
            time = itemView.findViewById(R.id.log_time);
        }
    }
}
