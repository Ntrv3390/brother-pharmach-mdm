package com.hmdm.plugins.smslog.persistence.postgres;

import com.google.inject.Module;
import com.hmdm.plugins.smslog.persistence.SmsLogPersistenceConfiguration;
import com.hmdm.plugins.smslog.persistence.postgres.guice.module.SmsLogPostgresLiquibaseModule;
import com.hmdm.plugins.smslog.persistence.postgres.guice.module.SmsLogPostgresPersistenceModule;
import com.hmdm.plugins.smslog.persistence.postgres.guice.module.SmsLogPostgresServiceModule;

import javax.servlet.ServletContext;
import java.util.ArrayList;
import java.util.List;

/**
 * PostgreSQL persistence configuration for call log plugin
 */
public class SmsLogPostgresPersistenceConfiguration implements SmsLogPersistenceConfiguration {

    @Override
    public List<Module> getPersistenceModules(ServletContext context) {
        List<Module> modules = new ArrayList<>();
        modules.add(new SmsLogPostgresLiquibaseModule(context));
        modules.add(new SmsLogPostgresServiceModule());
        modules.add(new SmsLogPostgresPersistenceModule(context));
        return modules;
    }
}
