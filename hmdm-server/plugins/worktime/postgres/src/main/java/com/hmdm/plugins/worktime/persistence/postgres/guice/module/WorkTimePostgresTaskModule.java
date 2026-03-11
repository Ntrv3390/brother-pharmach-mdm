package com.hmdm.plugins.worktime.persistence.postgres.guice.module;

import com.google.inject.Inject;
import com.hmdm.plugin.PluginTaskModule;
import com.hmdm.plugins.worktime.task.ExpiredExceptionCleanupTask;
import com.hmdm.util.BackgroundTaskRunnerService;

import java.util.concurrent.TimeUnit;

/**
 * Module for initializing background tasks for the WorkTime plugin.
 * Schedules the task for processing exception boundaries and purging expired exceptions.
 */
public class WorkTimePostgresTaskModule implements PluginTaskModule {

    /**
     * The cleanup task for expired exceptions.
     */
    private final ExpiredExceptionCleanupTask cleanupTask;

    /**
     * A runner for repeatable background tasks.
     */
    private final BackgroundTaskRunnerService taskRunner;

    /**
     * Constructs new WorkTimePostgresTaskModule instance.
     */
    @Inject
    public WorkTimePostgresTaskModule(ExpiredExceptionCleanupTask cleanupTask, BackgroundTaskRunnerService taskRunner) {
        this.cleanupTask = cleanupTask;
        this.taskRunner = taskRunner;
    }

    /**
     * Initializes this module. Schedules the task for processing start/end boundaries
     * and purging expired exceptions from DB every minute.
     */
    @Override
    public void init() {
        // Run every minute to ensure near real-time exception start/end handling
        taskRunner.submitRepeatableTask(cleanupTask::cleanupExpiredExceptions, 1, 1, TimeUnit.MINUTES);
    }
}
