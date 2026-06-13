package com.hmdm.plugins.calllog.persistence.postgres.guice.module;

import com.google.inject.Inject;
import com.hmdm.persistence.CustomerDAO;
import com.hmdm.persistence.domain.Customer;
import com.hmdm.plugin.PluginTaskModule;
import com.hmdm.plugins.calllog.persistence.CallLogDAO;
import com.hmdm.util.BackgroundTaskRunnerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Schedules a daily task that enforces the per-customer call-log retention policy.
 * Without this module the retentionDays setting is stored but never acted upon.
 */
public class CallLogRetentionTaskModule implements PluginTaskModule {

    private static final Logger log = LoggerFactory.getLogger(CallLogRetentionTaskModule.class);

    private final CallLogDAO callLogDAO;
    private final CustomerDAO customerDAO;
    private final BackgroundTaskRunnerService taskRunner;

    @Inject
    public CallLogRetentionTaskModule(CallLogDAO callLogDAO,
                                      CustomerDAO customerDAO,
                                      BackgroundTaskRunnerService taskRunner) {
        this.callLogDAO = callLogDAO;
        this.customerDAO = customerDAO;
        this.taskRunner = taskRunner;
    }

    @Override
    public void init() {
        // Run once at startup (after 1 hour to let the server settle) then daily.
        taskRunner.submitRepeatableTask(this::runRetention, 1, 24, TimeUnit.HOURS);
        log.info("CallLog retention cleanup scheduled (every 24 hours)");
    }

    private void runRetention() {
        try {
            List<Customer> customers = customerDAO.getAllCustomers();
            for (Customer customer : customers) {
                try {
                    com.hmdm.plugins.calllog.model.CallLogSettings settings =
                            callLogDAO.getSettings(customer.getId());
                    if (settings != null && settings.getRetentionDays() > 0) {
                        int deleted = callLogDAO.deleteOldCallLogs(
                                customer.getId(), settings.getRetentionDays());
                        if (deleted > 0) {
                            log.info("CallLog retention: deleted {} records for customer {}",
                                    deleted, customer.getId());
                        }
                    }
                } catch (Exception e) {
                    log.error("CallLog retention failed for customer {}", customer.getId(), e);
                }
            }
        } catch (Exception e) {
            log.error("CallLog retention task failed", e);
        }
    }
}
