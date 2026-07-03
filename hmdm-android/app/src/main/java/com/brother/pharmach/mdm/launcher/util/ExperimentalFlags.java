package com.brother.pharmach.mdm.launcher.util;

/**
 * Independently-toggleable experimental fixes for the "Get Latest GPS" investigation.
 *
 * Both default to false (current, already-shipped behavior). Flip exactly ONE per test round
 * so the resulting log can be attributed to a single change — flipping both at once confounds
 * whether H1 (Doze suppression) or H2 (orphaned/concurrent requests) explains any improvement.
 */
public final class ExperimentalFlags {

    private ExperimentalFlags() {}

    /**
     * H2 fix: makes cancellation actually cooperative.
     *  - tryRequestLiveUpdate()'s fallback chain aborts immediately when the calling thread is
     *    interrupted, instead of proceeding to the next fallback tier (main-looper rescue,
     *    FusedLocationProvider) after the outer caller has already given up.
     *  - captureAndUpload()'s provider-request phase is gated by a single-flight semaphore so a
     *    periodic and an urgent invocation cannot run requestProvidersInParallel() concurrently.
     */
    public static volatile boolean ABORT_ON_INTERRUPT_ENABLED = false;

    /**
     * H1 experiment: fires an ~80ms vibration pulse immediately before an urgent capture begins,
     * waits ~400ms, and logs Doze state before/after — to see whether nudging the device out of
     * Doze changes the outcome. Reversible, low-risk, logging-heavy by design.
     */
    public static volatile boolean DOZE_VIBRATION_PULSE_ENABLED = false;
}
