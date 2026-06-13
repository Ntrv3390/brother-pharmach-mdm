package com.hmdm.plugins.smslog.persistence.postgres.guice.module;

import com.google.inject.Inject;
import com.hmdm.persistence.CustomerDAO;
import com.hmdm.persistence.domain.Customer;
import com.hmdm.plugin.PluginTaskModule;
import com.hmdm.plugins.smslog.model.SmsLogSettings;
import com.hmdm.plugins.smslog.persistence.SmsLogDAO;
import com.hmdm.util.BackgroundTaskRunnerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Schedules a daily task that enforces the per-customer SMS log retention policy.
 * Without this module the retentionDays setting is stored but never acted upon.
 */
public class SmsLogRetentionTaskModule implements PluginTaskModule {

    private static final Logger log = LoggerFactory.getLogger(SmsLogRetentionTaskModule.class);

    private final SmsLogDAO smsLogDAO;
    private final CustomerDAO customerDAO;
    private final BackgroundTaskRunnerService taskRunner;

    @Inject
    public SmsLogRetentionTaskModule(SmsLogDAO smsLogDAO,
                                     CustomerDAO customerDAO,
                                     BackgroundTaskRunnerService taskRunner) {
        this.smsLogDAO = smsLogDAO;
        this.customerDAO = customerDAO;
        this.taskRunner = taskRunner;
    }

    @Override
    public void init() {
        taskRunner.submitRepeatableTask(this::runRetention, 1, 24, TimeUnit.HOURS);
        log.info("SmsLog retention cleanup scheduled (every 24 hours)");
    }

    private void runRetention() {
        try {
            List<Customer> customers = customerDAO.getAllCustomers();
            for (Customer customer : customers) {
                try {
                    SmsLogSettings settings = smsLogDAO.getSettings(customer.getId());
                    if (settings != null && settings.getRetentionDays() > 0) {
                        int deleted = smsLogDAO.deleteOldSmsLogs(
                                customer.getId(), settings.getRetentionDays());
                        if (deleted > 0) {
                            log.info("SmsLog retention: deleted {} records for customer {}",
                                    deleted, customer.getId());
                        }
                    }
                } catch (Exception e) {
                    log.error("SmsLog retention failed for customer {}", customer.getId(), e);
                }
            }
        } catch (Exception e) {
            log.error("SmsLog retention task failed", e);
        }
    }
}
