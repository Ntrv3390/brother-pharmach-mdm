package com.hmdm.plugins.worktime.service;

import com.hmdm.plugins.worktime.model.WorkTimeDevicePolicy;
import com.hmdm.plugins.worktime.model.WorkTimeDeviceOverride;
import com.hmdm.plugins.worktime.persistence.WorkTimeDAO;

import javax.inject.Inject;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public class WorkTimeService {

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

        // If base policy disabled => no enforcement (except active exception metadata exposure)
        if (!enforcementEnabled) {
            return new EffectiveWorkTimePolicy(false, start, end, days, during, outside);
        }

        // Check device override
        WorkTimeDeviceOverride override = dao.getDeviceOverride(customerId, deviceId);
        if (override != null && !override.isEnabled()) {
            Long exceptionStartMillis = override.getStartDateTime() != null ? override.getStartDateTime().getTime() : null;
            Long exceptionEndMillis = override.getEndDateTime() != null ? override.getEndDateTime().getTime() : null;
            if (isExceptionActive(override, now)) {
                return new EffectiveWorkTimePolicy(false, start, end, days,
                        during,
                        outside,
                        exceptionStartMillis,
                        exceptionEndMillis);
            }
            if (isExceptionExpired(override, now)) {
                dao.deleteDeviceOverride(customerId, deviceId);
            } else {
                return new EffectiveWorkTimePolicy(true,
                        start,
                        end,
                        days,
                        during,
                        outside,
                        exceptionStartMillis,
                        exceptionEndMillis);
            }
        }

        // Default path: per-device policy only
        return new EffectiveWorkTimePolicy(true, start, end, days, during, outside);
    }

    private boolean isExceptionActive(WorkTimeDeviceOverride override, LocalDateTime now) {
        if (override.getStartDateTime() == null || override.getEndDateTime() == null) {
            return false;
        }
        LocalDateTime start = override.getStartDateTime().toLocalDateTime();
        LocalDateTime end = override.getEndDateTime().toLocalDateTime();
        return !now.isBefore(start) && !now.isAfter(end);
    }

    private boolean isExceptionExpired(WorkTimeDeviceOverride override, LocalDateTime now) {
        if (override.getEndDateTime() == null) {
            return false;
        }
        return now.isAfter(override.getEndDateTime().toLocalDateTime());
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
                    checkDay = now.minusDays(1).getDayOfWeek();
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
                    checkDay = now.minusDays(1).getDayOfWeek();
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
