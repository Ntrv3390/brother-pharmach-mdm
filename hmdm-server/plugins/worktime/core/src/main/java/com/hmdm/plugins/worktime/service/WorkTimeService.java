package com.hmdm.plugins.worktime.service;

import com.hmdm.plugins.worktime.WorkTimeZone;
import com.hmdm.plugins.worktime.model.WorkTimeDevicePolicy;
import com.hmdm.plugins.worktime.model.WorkTimeDeviceOverride;
import com.hmdm.plugins.worktime.persistence.WorkTimeDAO;

import javax.inject.Inject;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.HashSet;
import java.util.Set;

public class WorkTimeService {

    private static final ZoneId WORKTIME_ZONE = WorkTimeZone.ZONE;

    private final WorkTimeDAO dao;
    private final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    @Inject
    public WorkTimeService(WorkTimeDAO dao) {
        this.dao = dao;
    }

    public EffectiveWorkTimePolicy resolveEffectivePolicy(int customerId, int deviceId, LocalDateTime now) {
        WorkTimeDevicePolicy base = dao.getDevicePolicy(customerId, deviceId);

        String start = base != null && base.getStartTime() != null ? base.getStartTime() : "09:00";
        String end = base != null && base.getEndTime() != null ? base.getEndTime() : "17:00";
        int days = base != null && base.getDaysOfWeek() != null ? base.getDaysOfWeek() : 31;
        Set<String> during = parseAllowed(base != null ? base.getAllowedAppsDuringWork() : "");
        Set<String> outside = parseAllowed(base != null ? base.getAllowedAppsOutsideWork() : "*");
        boolean enforcementEnabled = base == null || base.getEnabled() == null || base.getEnabled();

        List<EffectiveWorkTimePolicy.ExceptionWindow> exceptionWindows = mergeExceptionWindows(
                dao.getDeviceOverridesForDevice(customerId, deviceId),
                now
        );
        EffectiveWorkTimePolicy.ExceptionWindow activeWindow = findActiveWindow(exceptionWindows, now);

        // If base policy disabled => no enforcement (except active exception metadata exposure)
        if (!enforcementEnabled) {
            return new EffectiveWorkTimePolicy(false, start, end, days, during, outside,
                    activeWindow == null ? null : activeWindow.getStartDateTime(),
                    activeWindow == null ? null : activeWindow.getEndDateTime(),
                    exceptionWindows);
        }

        if (activeWindow != null) {
            return new EffectiveWorkTimePolicy(false,
                    start,
                    end,
                    days,
                    during,
                    outside,
                    activeWindow.getStartDateTime(),
                    activeWindow.getEndDateTime(),
                    exceptionWindows);
        }

        // Default path: per-device policy only
        return new EffectiveWorkTimePolicy(true, start, end, days, during, outside,
                null, null, exceptionWindows);
    }

    private List<EffectiveWorkTimePolicy.ExceptionWindow> mergeExceptionWindows(List<WorkTimeDeviceOverride> overrides,
                                                                               LocalDateTime now) {
        List<Interval> intervals = new ArrayList<>();
        for (WorkTimeDeviceOverride override : overrides) {
            if (override == null || override.isEnabled() || override.getStartDateTime() == null || override.getEndDateTime() == null) {
                continue;
            }

            LocalDateTime start = override.getStartDateTime().toLocalDateTime();
            LocalDateTime end = override.getEndDateTime().toLocalDateTime();
            if (end.isBefore(now)) {
                continue;
            }
            intervals.add(new Interval(start, end));
        }

        if (intervals.isEmpty()) {
            return new ArrayList<>();
        }

        intervals.sort(Comparator.comparing(Interval::getStart).thenComparing(Interval::getEnd));
        List<Interval> merged = new ArrayList<>();
        Interval current = intervals.get(0);
        for (int i = 1; i < intervals.size(); i++) {
            Interval next = intervals.get(i);
            if (!next.getStart().isAfter(current.getEnd())) {
                if (next.getEnd().isAfter(current.getEnd())) {
                    current = new Interval(current.getStart(), next.getEnd());
                }
            } else {
                merged.add(current);
                current = next;
            }
        }
        merged.add(current);

        List<EffectiveWorkTimePolicy.ExceptionWindow> windows = new ArrayList<>();
        for (Interval interval : merged) {
            if (interval.getEnd().isBefore(now)) {
                continue;
            }
            windows.add(new EffectiveWorkTimePolicy.ExceptionWindow(
                    interval.getStart().atZone(WORKTIME_ZONE).toInstant().toEpochMilli(),
                    interval.getEnd().atZone(WORKTIME_ZONE).toInstant().toEpochMilli()));
        }
        return windows;
    }

    private EffectiveWorkTimePolicy.ExceptionWindow findActiveWindow(List<EffectiveWorkTimePolicy.ExceptionWindow> windows,
                                                                     LocalDateTime now) {
        long nowMillis = now.atZone(WORKTIME_ZONE).toInstant().toEpochMilli();
        for (EffectiveWorkTimePolicy.ExceptionWindow window : windows) {
            if (window.getStartDateTime() == null || window.getEndDateTime() == null) {
                continue;
            }
            if (nowMillis >= window.getStartDateTime() && nowMillis <= window.getEndDateTime()) {
                return window;
            }
        }
        return null;
    }

    private static class Interval {
        private final LocalDateTime start;
        private final LocalDateTime end;

        Interval(LocalDateTime start, LocalDateTime end) {
            this.start = start;
            this.end = end;
        }

        LocalDateTime getStart() {
            return start;
        }

        LocalDateTime getEnd() {
            return end;
        }
    }

    private Set<String> parseAllowed(String raw) {
        if (raw == null) return new HashSet<>();
        raw = raw.trim();
        if (raw.equals("*")) {
            Set<String> s = new HashSet<>();
            s.add("*");
            return s;
        }
        if (raw.isEmpty()) return new HashSet<>();
        String[] parts = raw.split("\\s*,\\s*");
        Set<String> res = new HashSet<>(Arrays.asList(parts));
        return res;
    }

    public boolean isAppAllowed(int customerId, int deviceId, String pkg, LocalDateTime now) {
        EffectiveWorkTimePolicy p = resolveEffectivePolicy(customerId, deviceId, now);

        if (!p.isEnforcementEnabled()) return true;

        LocalTime time = now.toLocalTime();
        LocalTime start = LocalTime.parse(p.getStartTime(), TIME);
        LocalTime end = LocalTime.parse(p.getEndTime(), TIME);

        boolean withinWork;
        if (!start.equals(end)) {
            if (start.isBefore(end) || start.equals(end)) {
                withinWork = !time.isBefore(start) && !time.isAfter(end);
            } else {
                // overnight: start > end
                withinWork = !time.isBefore(start) || !time.isAfter(end);
            }
        } else {
            // equal times -> treat as full day
            withinWork = true;
        }

        // Enforce days-of-week: if the current moment does not fall into a configured work day,
        // treat it as outside work. For overnight windows we attribute the after-midnight
        // portion to the previous day (so overnight windows that start on Monday and end on Tuesday
        // will be considered Monday's work window).
        if (withinWork) {
            DayOfWeek checkDay;
            if (start.isBefore(end) || start.equals(end)) {
                // normal window -> current day
                checkDay = now.getDayOfWeek();
            } else {
                // overnight -> if time >= start it belongs to the start day; otherwise to previous day
                if (!time.isBefore(start)) {
                    checkDay = now.getDayOfWeek();
                } else {
                    checkDay = now.toLocalDate().minusDays(1).getDayOfWeek();
                }
            }

            if (!p.hasDay(checkDay)) {
                withinWork = false;
            }
        }

        if (withinWork) {
            if (p.isWildcardAllowedDuring()) return true;
            return p.getAllowedDuring().contains(pkg);
        } else {
            if (p.isWildcardAllowedOutside()) return true;
            return p.getAllowedOutside().contains(pkg);
        }
    }

    /**
     * Determines if the given time falls within work hours according to the policy.
     *
     * @param startTime start time in HH:mm format
     * @param endTime end time in HH:mm format
     * @param daysOfWeek bitmask for days of week
     * @param now current date/time
     * @return true if current time is within work hours, false otherwise
     */
    public boolean isWorkTime(String startTime, String endTime, int daysOfWeek, LocalDateTime now) {
        LocalTime time = now.toLocalTime();
        LocalTime start = LocalTime.parse(startTime, TIME);
        LocalTime end = LocalTime.parse(endTime, TIME);

        boolean withinWork;
        if (!start.equals(end)) {
            if (start.isBefore(end) || start.equals(end)) {
                withinWork = !time.isBefore(start) && !time.isAfter(end);
            } else {
                // overnight: start > end
                withinWork = !time.isBefore(start) || !time.isAfter(end);
            }
        } else {
            // equal times -> treat as full day
            withinWork = true;
        }

        // Check day of week
        if (withinWork) {
            DayOfWeek checkDay;
            if (start.isBefore(end) || start.equals(end)) {
                checkDay = now.getDayOfWeek();
            } else {
                // overnight window
                if (!time.isBefore(start)) {
                    checkDay = now.getDayOfWeek();
                } else {
                    checkDay = now.toLocalDate().minusDays(1).getDayOfWeek();
                }
            }

            int dayBit = 1 << (checkDay.getValue() - 1);
            if ((daysOfWeek & dayBit) == 0) {
                withinWork = false;
            }
        }

        return withinWork;
    }
}
