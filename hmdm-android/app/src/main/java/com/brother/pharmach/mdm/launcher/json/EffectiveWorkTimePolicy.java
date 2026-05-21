package com.brother.pharmach.mdm.launcher.json;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Collections;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class EffectiveWorkTimePolicy {
    private boolean enforcementEnabled;
    private String startTime; // HH:mm
    private String endTime;   // HH:mm
    private int daysOfWeek; // bitmask 1..64
    private List<String> allowedDuring;
    private List<String> allowedOutside;
    private Long exceptionStartDateTime;
    private Long exceptionEndDateTime;
    private List<ExceptionWindow> exceptionWindows;

    public boolean isEnforcementEnabled() {
        return enforcementEnabled;
    }

    public void setEnforcementEnabled(boolean enforcementEnabled) {
        this.enforcementEnabled = enforcementEnabled;
    }

    public String getStartTime() {
        return startTime;
    }

    public void setStartTime(String startTime) {
        this.startTime = startTime;
    }

    public String getEndTime() {
        return endTime;
    }

    public void setEndTime(String endTime) {
        this.endTime = endTime;
    }

    public int getDaysOfWeek() {
        return daysOfWeek;
    }

    public void setDaysOfWeek(int daysOfWeek) {
        this.daysOfWeek = daysOfWeek;
    }

    public List<String> getAllowedDuring() {
        return allowedDuring;
    }

    public void setAllowedDuring(List<String> allowedDuring) {
        this.allowedDuring = allowedDuring;
    }

    public List<String> getAllowedOutside() {
        return allowedOutside;
    }

    public void setAllowedOutside(List<String> allowedOutside) {
        this.allowedOutside = allowedOutside;
    }

    public Long getExceptionStartDateTime() {
        return exceptionStartDateTime;
    }

    public void setExceptionStartDateTime(Long exceptionStartDateTime) {
        this.exceptionStartDateTime = exceptionStartDateTime;
    }

    public Long getExceptionEndDateTime() {
        return exceptionEndDateTime;
    }

    public void setExceptionEndDateTime(Long exceptionEndDateTime) {
        this.exceptionEndDateTime = exceptionEndDateTime;
    }

    public List<ExceptionWindow> getExceptionWindows() {
        return exceptionWindows == null ? Collections.emptyList() : exceptionWindows;
    }

    public void setExceptionWindows(List<ExceptionWindow> exceptionWindows) {
        this.exceptionWindows = exceptionWindows;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ExceptionWindow {
        private Long startDateTime;
        private Long endDateTime;

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
