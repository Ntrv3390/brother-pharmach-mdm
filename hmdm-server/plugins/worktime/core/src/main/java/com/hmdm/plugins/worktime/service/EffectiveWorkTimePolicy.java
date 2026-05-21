package com.hmdm.plugins.worktime.service;

import java.io.Serializable;
import java.time.DayOfWeek;
import java.util.Collections;
import java.util.List;
import java.util.Set;

public class EffectiveWorkTimePolicy {

    private final boolean enforcementEnabled;
    private final String startTime; // HH:mm
    private final String endTime;   // HH:mm
    private final int daysOfWeek; // bitmask 1..64
    private final Set<String> allowedDuring;
    private final Set<String> allowedOutside;
    private final Long exceptionStartDateTime;
    private final Long exceptionEndDateTime;
    private final List<ExceptionWindow> exceptionWindows;

    public EffectiveWorkTimePolicy(boolean enforcementEnabled,
                                   String startTime,
                                   String endTime,
                                   int daysOfWeek,
                                   Set<String> allowedDuring,
                                   Set<String> allowedOutside) {
        this(enforcementEnabled, startTime, endTime, daysOfWeek, allowedDuring, allowedOutside, null, null, Collections.emptyList());
    }

    public EffectiveWorkTimePolicy(boolean enforcementEnabled,
                                   String startTime,
                                   String endTime,
                                   int daysOfWeek,
                                   Set<String> allowedDuring,
                                   Set<String> allowedOutside,
                                   Long exceptionStartDateTime,
                                   Long exceptionEndDateTime) {
        this(enforcementEnabled, startTime, endTime, daysOfWeek, allowedDuring, allowedOutside, exceptionStartDateTime, exceptionEndDateTime, Collections.emptyList());
    }

    public EffectiveWorkTimePolicy(boolean enforcementEnabled,
                                   String startTime,
                                   String endTime,
                                   int daysOfWeek,
                                   Set<String> allowedDuring,
                                   Set<String> allowedOutside,
                                   Long exceptionStartDateTime,
                                   Long exceptionEndDateTime,
                                   List<ExceptionWindow> exceptionWindows) {
        this.enforcementEnabled = enforcementEnabled;
        this.startTime = startTime;
        this.endTime = endTime;
        this.daysOfWeek = daysOfWeek;
        this.allowedDuring = allowedDuring == null ? Collections.emptySet() : allowedDuring;
        this.allowedOutside = allowedOutside == null ? Collections.emptySet() : allowedOutside;
        this.exceptionStartDateTime = exceptionStartDateTime;
        this.exceptionEndDateTime = exceptionEndDateTime;
        this.exceptionWindows = exceptionWindows == null ? Collections.emptyList() : exceptionWindows;
    }

    public boolean isEnforcementEnabled() {
        return enforcementEnabled;
    }

    public String getStartTime() {
        return startTime;
    }

    public String getEndTime() {
        return endTime;
    }

    public int getDaysOfWeek() {
        return daysOfWeek;
    }

    public Set<String> getAllowedDuring() {
        return allowedDuring;
    }

    public Set<String> getAllowedOutside() {
        return allowedOutside;
    }

    public Long getExceptionStartDateTime() {
        return exceptionStartDateTime;
    }

    public Long getExceptionEndDateTime() {
        return exceptionEndDateTime;
    }

    public List<ExceptionWindow> getExceptionWindows() {
        return exceptionWindows;
    }

    public boolean isWildcardAllowedDuring() {
        return allowedDuring.contains("*");
    }

    public boolean isWildcardAllowedOutside() {
        return allowedOutside.contains("*");
    }

    public boolean hasDay(DayOfWeek dow) {
        int mask = 1 << (dow.getValue() - 1);
        return (this.daysOfWeek & mask) == mask;
    }

    public static class ExceptionWindow implements Serializable {
        private static final long serialVersionUID = 1L;

        private Long startDateTime;
        private Long endDateTime;

        public ExceptionWindow() {
        }

        public ExceptionWindow(Long startDateTime, Long endDateTime) {
            this.startDateTime = startDateTime;
            this.endDateTime = endDateTime;
        }

        public Long getStartDateTime() {
            return startDateTime;
        }

        public void setStartDateTime(Long startDateTime) {
            this.startDateTime = startDateTime;
        }

        public Long getEndDateTime() {
            return endDateTime;
        }

        public void setEndDateTime(Long endDateTime) {
            this.endDateTime = endDateTime;
        }
    }
}
