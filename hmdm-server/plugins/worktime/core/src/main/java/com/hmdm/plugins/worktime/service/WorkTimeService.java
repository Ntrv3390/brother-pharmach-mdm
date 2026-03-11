package com.hmdm.plugins.worktime.service;

import com.hmdm.plugins.worktime.model.WorkTimePolicy;
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
        // load global
        WorkTimePolicy global = dao.getGlobalPolicy(customerId);

        if (global == null) {
            // default: enforcement disabled
            return new EffectiveWorkTimePolicy(false, "00:00", "00:00", 127, new HashSet<>(), new HashSet<>());
        }

        // If global disabled => no enforcement
        if (global.getEnabled() != null && !global.getEnabled()) {
            int globalDays = global.getDaysOfWeek() != null ? global.getDaysOfWeek() : 127;
            return new EffectiveWorkTimePolicy(false, global.getStartTime(), global.getEndTime(), globalDays, parseAllowed(global.getAllowedAppsDuringWork()), parseAllowed(global.getAllowedAppsOutsideWork()));
        }

        // Check device override
        WorkTimeDeviceOverride override = dao.getDeviceOverride(customerId, deviceId);
        if (override != null && !override.isEnabled()) {
            Long exceptionStartMillis = override.getStartDateTime() != null ? override.getStartDateTime().getTime() : null;
            Long exceptionEndMillis = override.getEndDateTime() != null ? override.getEndDateTime().getTime() : null;
            if (isExceptionActive(override, now)) {
                int globalDays = global.getDaysOfWeek() != null ? global.getDaysOfWeek() : 127;
                return new EffectiveWorkTimePolicy(false, global.getStartTime(), global.getEndTime(), globalDays,
                        parseAllowed(global.getAllowedAppsDuringWork()),
                        parseAllowed(global.getAllowedAppsOutsideWork()),
                        exceptionStartMillis,
                        exceptionEndMillis);
            }
            if (isExceptionExpired(override, now)) {
                dao.deleteDeviceOverride(customerId, deviceId);
            } else {
                int globalDays = global.getDaysOfWeek() != null ? global.getDaysOfWeek() : 127;
                return new EffectiveWorkTimePolicy(true,
                        global.getStartTime(),
                        global.getEndTime(),
                        globalDays,
                        parseAllowed(global.getAllowedAppsDuringWork()),
                        parseAllowed(global.getAllowedAppsOutsideWork()),
                        exceptionStartMillis,
                        exceptionEndMillis);
            }
        }
        if (override != null && override.isEnabled()) {
            // use override fields (fall back to global when null)
            String start = override.getStartTime() != null ? override.getStartTime() : global.getStartTime();
            String end = override.getEndTime() != null ? override.getEndTime() : global.getEndTime();
            Set<String> during = override.getAllowedAppsDuringWork() != null ? parseAllowed(override.getAllowedAppsDuringWork()) : parseAllowed(global.getAllowedAppsDuringWork());
            Set<String> outside = override.getAllowedAppsOutsideWork() != null ? parseAllowed(override.getAllowedAppsOutsideWork()) : parseAllowed(global.getAllowedAppsOutsideWork());

            int days = parseDaysOfWeek(override.getDaysOfWeek(), global.getDaysOfWeek());

            return new EffectiveWorkTimePolicy(true, start, end, days, during, outside);
        }

        // Fallback to global
        int globalDays = global.getDaysOfWeek() != null ? global.getDaysOfWeek() : 127;
        return new EffectiveWorkTimePolicy(true, global.getStartTime(), global.getEndTime(), globalDays, parseAllowed(global.getAllowedAppsDuringWork()), parseAllowed(global.getAllowedAppsOutsideWork()));
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

    private int parseDaysOfWeek(String overrideDays, Integer globalDays) {
        if (overrideDays == null || overrideDays.trim().isEmpty()) {
            return globalDays != null ? globalDays : 127;
        }
        String s = overrideDays.trim();
        // try numeric
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            // continue to parse csv names
        }

        int mask = 0;
        String[] parts = s.split("\\s*,\\s*");
        for (String t : parts) {
            String low = t.toLowerCase();
            switch (low) {
                case "mon": case "monday": mask |= 1; break;
                case "tue": case "tuesday": mask |= 2; break;
                case "wed": case "wednesday": mask |= 4; break;
                case "thu": case "thursday": mask |= 8; break;
                case "fri": case "friday": mask |= 16; break;
                case "sat": case "saturday": mask |= 32; break;
                case "sun": case "sunday": mask |= 64; break;
                default:
                    try {
                        int v = Integer.parseInt(t);
                        mask |= v;
                    } catch (NumberFormatException ex) {
                        // ignore unknown token
                    }
            }
        }
        if (mask == 0) return globalDays != null ? globalDays : 127;
        return mask;
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

    public boolean isAppAllowed(int customerId, int userId, String pkg, LocalDateTime now) {
        EffectiveWorkTimePolicy p = resolveEffectivePolicy(customerId, userId, now);

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
