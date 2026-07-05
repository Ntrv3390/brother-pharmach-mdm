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

import android.app.Activity;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.content.Context;
import android.view.WindowManager;

import com.brother.pharmach.mdm.launcher.Const;
import com.brother.pharmach.mdm.launcher.util.Utils;

/**
 * A thin, transparent, touchable window pinned over the very top of the screen —
 * the region occupied by the system status bar (carrier / time / battery).
 *
 * The system status bar is a SystemUI-owned window, so touches that start on it
 * never reach the launcher activity, which is why a pull-down that begins on the
 * status info strip did nothing. This overlay sits on top of that strip (using
 * the same WindowManager overlay mechanism the launcher already uses for its
 * exit/info buttons), captures a downward swipe there, and drives the quick
 * panel's reveal via its external-drag API — so the pull now works when started
 * on the status bar itself, from anywhere across its width.
 *
 * It does NOT interfere with the existing status-bar lockdown: it is transparent,
 * reacts only to downward swipes to open our own panel, and never re-enables the
 * system shade. Below this strip, MainActivity.dispatchTouchEvent continues to
 * capture pulls that start in the launcher content area.
 */
public class QuickPanelEdgeOverlay {

    /** Callbacks into the controller/panel to drive the reveal. */
    public interface DragTarget {
        boolean canStartReveal();
        void onRevealStart();
        void onRevealBy(float totalDy);
        void onRevealEnd(float velocityY);
    }

    private final Activity activity;
    private final DragTarget target;
    private final int touchSlop;

    private View overlayView;
    private VelocityTracker velocityTracker;
    private float downY, downX;
    private boolean dragging;

    public QuickPanelEdgeOverlay(Activity activity, DragTarget target) {
        this.activity = activity;
        this.target = target;
        this.touchSlop = ViewConfiguration.get(activity).getScaledTouchSlop();
    }

    private int statusBarHeightPx() {
        int resId = activity.getResources().getIdentifier("status_bar_height", "dimen", "android");
        int h = resId > 0 ? activity.getResources().getDimensionPixelSize(resId) : 0;
        if (h <= 0) {
            h = (int) (28 * activity.getResources().getDisplayMetrics().density);
        }
        // A little taller than the bare status bar so the swipe target is comfortable,
        // while staying thin enough not to swallow taps on launcher content below it.
        return h + (int) (16 * activity.getResources().getDisplayMetrics().density);
    }

    /** Adds the overlay window. Idempotent; safe to call from onResume. */
    public void show() {
        if (overlayView != null) {
            return;
        }
        WindowManager wm = (WindowManager) activity.getApplicationContext().getSystemService(Context.WINDOW_SERVICE);
        if (wm == null) {
            return;
        }

        overlayView = new View(activity);
        overlayView.setBackgroundColor(Color.TRANSPARENT);
        overlayView.setOnTouchListener(this::onTouch);

        WindowManager.LayoutParams params = new WindowManager.LayoutParams();
        params.type = Utils.OverlayWindowType();
        params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
        params.width = WindowManager.LayoutParams.MATCH_PARENT;
        params.height = statusBarHeightPx();
        params.gravity = Gravity.TOP | Gravity.START;
        params.format = PixelFormat.TRANSPARENT;

        try {
            wm.addView(overlayView, params);
        } catch (Exception e) {
            // No overlay permission / not allowed — fall back to the activity-level
            // dispatch band, which still catches pulls starting below the status bar
            Log.w(Const.LOG_TAG, "QuickPanel: edge overlay unavailable: " + e.getMessage());
            overlayView = null;
        }
    }

    /**
     * Enables or disables touch capture. While the panel is open the overlay must
     * be pass-through so it never covers the panel header; it is re-enabled when
     * the panel closes to catch the next pull.
     */
    public void setCaptureEnabled(boolean enabled) {
        if (overlayView == null) {
            return;
        }
        WindowManager wm = (WindowManager) activity.getApplicationContext().getSystemService(Context.WINDOW_SERVICE);
        if (wm == null) {
            return;
        }
        try {
            WindowManager.LayoutParams params = (WindowManager.LayoutParams) overlayView.getLayoutParams();
            int notTouchable = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            if (enabled) {
                params.flags &= ~notTouchable;
            } else {
                params.flags |= notTouchable;
                dragging = false;
            }
            wm.updateViewLayout(overlayView, params);
        } catch (Exception e) {
            Log.w(Const.LOG_TAG, "QuickPanel: edge overlay update failed: " + e.getMessage());
        }
    }

    public void remove() {
        if (overlayView == null) {
            return;
        }
        WindowManager wm = (WindowManager) activity.getApplicationContext().getSystemService(Context.WINDOW_SERVICE);
        if (wm != null) {
            try {
                wm.removeView(overlayView);
            } catch (Exception ignored) {
            }
        }
        overlayView = null;
        recycleVelocity();
    }

    private boolean onTouch(View v, MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (!target.canStartReveal()) {
                    return false;
                }
                downX = event.getRawX();
                downY = event.getRawY();
                dragging = false;
                obtainVelocity();
                velocityTracker.addMovement(event);
                // Capture the gesture so we keep receiving MOVE/UP even as the finger
                // travels below this thin strip into the launcher content area.
                return true;

            case MotionEvent.ACTION_MOVE:
                if (velocityTracker != null) {
                    velocityTracker.addMovement(event);
                }
                float dy = event.getRawY() - downY;
                float absDx = Math.abs(event.getRawX() - downX);
                if (!dragging) {
                    if (dy >= touchSlop && dy >= absDx * 0.6f) {
                        dragging = true;
                        target.onRevealStart();
                        target.onRevealBy(dy);
                    }
                    return true;
                }
                target.onRevealBy(dy);
                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (dragging) {
                    float velocityY = 0;
                    if (velocityTracker != null) {
                        velocityTracker.addMovement(event);
                        velocityTracker.computeCurrentVelocity(1000);
                        velocityY = velocityTracker.getYVelocity();
                    }
                    target.onRevealEnd(velocityY);
                    dragging = false;
                }
                recycleVelocity();
                return true;
        }
        return false;
    }

    private void obtainVelocity() {
        if (velocityTracker == null) {
            velocityTracker = VelocityTracker.obtain();
        } else {
            velocityTracker.clear();
        }
    }

    private void recycleVelocity() {
        if (velocityTracker != null) {
            velocityTracker.recycle();
            velocityTracker = null;
        }
    }
}
