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

package com.brother.pharmach.mdm.launcher.ui.quickpanel;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.core.content.ContextCompat;

import com.brother.pharmach.mdm.launcher.R;

/**
 * Pill-shaped slider matching the reference quick settings design: a fully
 * rounded track whose filled portion tracks the value, with an icon embedded
 * inside the leading edge of the fill. Supports live scrubbing — the listener
 * fires on every drag movement, not just on release.
 */
public class PillSliderView extends View {

    public interface OnValueChangeListener {
        void onValueChanged(float fraction, boolean fromUser, boolean dragging);
    }

    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private Drawable icon;
    private float fraction = 0.5f;
    private boolean dragging = false;
    private boolean userInteractionEnabled = true;
    private Runnable onDisabledTouch;
    private OnValueChangeListener listener;

    public PillSliderView(Context context) {
        this(context, null);
    }

    public PillSliderView(Context context, AttributeSet attrs) {
        super(context, attrs);
        trackPaint.setColor(ContextCompat.getColor(context, R.color.qpSliderTrack));
        fillPaint.setColor(ContextCompat.getColor(context, R.color.qpSliderFill));
    }

    public void setIcon(int iconRes) {
        icon = ContextCompat.getDrawable(getContext(), iconRes);
        if (icon != null) {
            icon = icon.mutate();
            icon.setColorFilter(ContextCompat.getColor(getContext(), R.color.qpIconOnActive),
                    PorterDuff.Mode.SRC_IN);
        }
        invalidate();
    }

    public void setOnValueChangeListener(OnValueChangeListener listener) {
        this.listener = listener;
    }

    /**
     * Read-only mode for server-managed values: the fill still reflects the
     * live system state, but touches are ignored (an optional callback lets the
     * host explain why, e.g. a "managed by administrator" toast).
     */
    public void setUserInteractionEnabled(boolean enabled, Runnable onDisabledTouch) {
        this.userInteractionEnabled = enabled;
        this.onDisabledTouch = onDisabledTouch;
        setAlpha(enabled ? 1f : 0.5f);
        if (!enabled) {
            dragging = false;
        }
    }

    /** Programmatic update (from ContentObserver / volume broadcast), no callback. */
    public void setFraction(float fraction) {
        if (!dragging) {
            this.fraction = clamp(fraction);
            invalidate();
        }
    }

    public float getFraction() {
        return fraction;
    }

    private static float clamp(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        float radius = h / 2f;

        rect.set(0, 0, w, h);
        canvas.drawRoundRect(rect, radius, radius, trackPaint);

        // Keep the fill at least one cap wide so the pill shape never collapses
        float fillWidth = Math.max(h, w * fraction);
        rect.set(0, 0, fillWidth, h);
        canvas.drawRoundRect(rect, radius, radius, fillPaint);

        if (icon != null) {
            int iconSize = (int) (h * 0.5f);
            int left = (int) (radius - iconSize / 2f) + (int) (h * 0.12f);
            int top = (h - iconSize) / 2;
            icon.setBounds(left, top, left + iconSize, top + iconSize);
            icon.draw(canvas);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!userInteractionEnabled) {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN && onDisabledTouch != null) {
                onDisabledTouch.run();
            }
            // Consume so the touch doesn't fall through, but never change the value
            return true;
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                dragging = true;
                // Keep the gesture even when the panel/scroll parent wants it
                getParent().requestDisallowInterceptTouchEvent(true);
                updateFromTouch(event.getX(), true);
                return true;
            case MotionEvent.ACTION_MOVE:
                updateFromTouch(event.getX(), true);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                updateFromTouch(event.getX(), false);
                return true;
        }
        return super.onTouchEvent(event);
    }

    private void updateFromTouch(float x, boolean stillDragging) {
        int w = getWidth();
        if (w <= 0) {
            return;
        }
        fraction = clamp(x / w);
        invalidate();
        if (listener != null) {
            listener.onValueChanged(fraction, true, stillDragging);
        }
    }
}
