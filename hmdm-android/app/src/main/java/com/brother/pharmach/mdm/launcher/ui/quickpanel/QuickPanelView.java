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

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ScrollView;

import com.brother.pharmach.mdm.launcher.R;

/**
 * Full-screen overlay hosting the swipe-down quick settings panel.
 *
 * Added as the last child of the MainActivity root layout, so it lives inside
 * the same window/task as the launcher content — no WindowManager overlay, no
 * extra Activity, nothing lock-task can treat as a foreign window. The system
 * status bar / shade is already disabled elsewhere (Device Owner policy).
 *
 * Touch model:
 *  - closed: this view is fully transparent to touches. The opening gesture is
 *    detected at MainActivity.dispatchTouchEvent level (QuickPanelController)
 *    so a swipe down from the top works anywhere across the screen width, even
 *    when it starts over app icons — the controller then drives the reveal via
 *    the external-drag API below;
 *  - open: all touches are consumed — tap on the scrim closes, swipe up on the
 *    panel (when its inner scroll is at the end) closes, panel children handle
 *    their own clicks/drags.
 */
public class QuickPanelView extends FrameLayout {

    public interface Listener {
        void onPanelOpened();
        void onPanelClosed();
        /** 0 = fully closed .. 1 = fully open; drives the scrim/backdrop blur. */
        void onOpenFractionChanged(float fraction);
    }

    private static final float OPEN_COMMIT_FRACTION = 0.4f;
    private static final int ANIMATION_DURATION_MS = 240;
    private static final float PANEL_MAX_HEIGHT_RATIO = 0.85f;

    private final View scrim;
    private final ScrollView panel;
    private final int touchSlop;
    private final int minFlingVelocity;

    private Listener listener;
    private boolean open = false;
    private boolean dragging = false;
    private float downX, downY;
    private float dragStartTranslation;
    private VelocityTracker velocityTracker;
    private ValueAnimator animator;

    public QuickPanelView(Context context) {
        super(context);
        LayoutInflater.from(context).inflate(R.layout.top_quick_panel, this, true);
        scrim = findViewById(R.id.qp_scrim);
        panel = findViewById(R.id.qp_panel);

        ViewConfiguration vc = ViewConfiguration.get(context);
        touchSlop = vc.getScaledTouchSlop();
        minFlingVelocity = vc.getScaledMinimumFlingVelocity() * 4;

        scrim.setOnClickListener(v -> close(true));
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public boolean isOpen() {
        return open;
    }

    /** True while an open/close animation is running. */
    public boolean isAnimating() {
        return animator != null && animator.isRunning();
    }

    public boolean isDragging() {
        return dragging;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        // Cap the panel at ~85% of the screen; the inner ScrollView takes over beyond that
        int maxPanelHeight = (int) (getMeasuredHeight() * PANEL_MAX_HEIGHT_RATIO);
        if (maxPanelHeight > 0 && panel.getMeasuredHeight() > maxPanelHeight) {
            panel.measure(
                    MeasureSpec.makeMeasureSpec(panel.getMeasuredWidth(), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(maxPanelHeight, MeasureSpec.EXACTLY));
        }
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        if (!open && !dragging && !isAnimating()) {
            panel.setTranslationY(-panel.getHeight());
        }
    }

    private float panelHeight() {
        int h = panel.getHeight();
        return h > 0 ? h : 1f;
    }

    private float openFraction() {
        return 1f + panel.getTranslationY() / panelHeight();
    }

    // ------------------------------------------------------------------ external drag API
    // Driven by QuickPanelController from MainActivity.dispatchTouchEvent while
    // the panel is closed, so the opening swipe works from anywhere along the
    // top of the screen (not just over this view's transparent area).

    /** Begin revealing the panel; the finger will drive translation via externalDragBy. */
    public void startExternalDrag() {
        cancelAnimator();
        dragging = true;
        dragStartTranslation = open ? panel.getTranslationY() : -panelHeight();
        showChildren();
    }

    /** @param totalDy total downward finger travel since the drag committed */
    public void externalDragBy(float totalDy) {
        if (!dragging) {
            return;
        }
        panel.setTranslationY(clampTranslation(dragStartTranslation + totalDy));
        publishFraction();
    }

    /** Finger lifted: commit to open/closed based on fling velocity or position. */
    public void externalDragEnd(float velocityY) {
        if (!dragging) {
            return;
        }
        dragging = false;
        settle(velocityY);
    }

    private void settle(float velocityY) {
        boolean shouldOpen;
        if (Math.abs(velocityY) > minFlingVelocity) {
            // Fast flick wins regardless of the distance dragged
            shouldOpen = velocityY > 0;
        } else {
            shouldOpen = openFraction() >= OPEN_COMMIT_FRACTION;
        }
        if (shouldOpen) {
            open(true);
        } else {
            close(true);
        }
    }

    // ------------------------------------------------------------------ touch (open state only)

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (!open) {
            return false;
        }
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = ev.getX();
                downY = ev.getY();
                trackVelocity(ev);
                return false;
            case MotionEvent.ACTION_MOVE:
                float dy = ev.getY() - downY;
                float dx = ev.getX() - downX;
                // Steal an upward, mostly-vertical drag to close the panel — but only
                // when its inner scroll cannot consume the movement itself
                if (dy < -touchSlop && Math.abs(dy) > Math.abs(dx)
                        && downY < panelBottom() && !panel.canScrollVertically(1)) {
                    cancelAnimator();
                    dragging = true;
                    dragStartTranslation = panel.getTranslationY() - dy;
                    return true;
                }
                return false;
        }
        return false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!open && !dragging) {
            // Closed panel is transparent to touches; opening is handled at the
            // activity dispatch level
            return false;
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getX();
                downY = event.getY();
                trackVelocity(event);
                return true;

            case MotionEvent.ACTION_MOVE:
                trackVelocity(event);
                float dy = event.getY() - downY;
                float dx = event.getX() - downX;
                if (!dragging && open && dy < -touchSlop && Math.abs(dy) > Math.abs(dx)) {
                    cancelAnimator();
                    dragging = true;
                    dragStartTranslation = panel.getTranslationY() - dy;
                }
                if (dragging) {
                    panel.setTranslationY(clampTranslation(dragStartTranslation + dy));
                    publishFraction();
                }
                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (dragging) {
                    dragging = false;
                    float velocityY = 0;
                    if (velocityTracker != null) {
                        velocityTracker.addMovement(event);
                        velocityTracker.computeCurrentVelocity(1000);
                        velocityY = velocityTracker.getYVelocity();
                    }
                    settle(velocityY);
                }
                recycleVelocity();
                return true;
        }
        return super.onTouchEvent(event);
    }

    private float panelBottom() {
        return panel.getHeight() + panel.getTranslationY();
    }

    private float clampTranslation(float translation) {
        float min = -panelHeight();
        if (translation > 0) {
            // Rubber-band past the fully open position
            translation = (float) (Math.sqrt(translation) * 4);
        }
        return Math.max(min, Math.min(translation, 60f));
    }

    private void trackVelocity(MotionEvent event) {
        if (velocityTracker == null) {
            velocityTracker = VelocityTracker.obtain();
        }
        velocityTracker.addMovement(event);
    }

    private void recycleVelocity() {
        if (velocityTracker != null) {
            velocityTracker.recycle();
            velocityTracker = null;
        }
    }

    // ------------------------------------------------------------------ open / close

    public void open(boolean animate) {
        cancelAnimator();
        showChildren();
        boolean wasOpen = open;
        open = true;
        if (animate) {
            animateTo(0f, null);
        } else {
            panel.setTranslationY(0f);
            publishFraction();
        }
        if (!wasOpen && listener != null) {
            listener.onPanelOpened();
        }
    }

    public void close(boolean animate) {
        cancelAnimator();
        boolean wasOpen = open;
        open = false;
        dragging = false;
        Runnable onDone = () -> {
            hideChildren();
            if (wasOpen && listener != null) {
                listener.onPanelClosed();
            }
        };
        if (animate && panel.getVisibility() == VISIBLE) {
            animateTo(-panelHeight(), onDone);
        } else {
            panel.setTranslationY(-panelHeight());
            publishFraction();
            onDone.run();
        }
    }

    private void animateTo(float target, Runnable endAction) {
        animator = ValueAnimator.ofFloat(panel.getTranslationY(), target);
        animator.setDuration(ANIMATION_DURATION_MS);
        animator.setInterpolator(new DecelerateInterpolator(1.5f));
        animator.addUpdateListener(a -> {
            panel.setTranslationY((Float) a.getAnimatedValue());
            publishFraction();
        });
        animator.addListener(new AnimatorListenerAdapter() {
            private boolean cancelled = false;

            @Override
            public void onAnimationCancel(Animator animation) {
                cancelled = true;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                if (!cancelled && endAction != null) {
                    endAction.run();
                }
            }
        });
        animator.start();
    }

    private void cancelAnimator() {
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
    }

    private void showChildren() {
        scrim.setVisibility(VISIBLE);
        panel.setVisibility(VISIBLE);
    }

    private void hideChildren() {
        scrim.setVisibility(INVISIBLE);
        panel.setVisibility(INVISIBLE);
    }

    private void publishFraction() {
        float fraction = Math.max(0f, Math.min(1f, openFraction()));
        scrim.setAlpha(fraction);
        if (listener != null) {
            listener.onOpenFractionChanged(fraction);
        }
    }
}
