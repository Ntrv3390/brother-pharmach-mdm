package com.hmdm.plugins.calllog.persistence.postgres;

import com.google.inject.Module;
import com.hmdm.plugin.PluginTaskModule;
import com.hmdm.plugins.calllog.persistence.CallLogPersistenceConfiguration;
import com.hmdm.plugins.calllog.persistence.postgres.guice.module.CallLogPostgresLiquibaseModule;
import com.hmdm.plugins.calllog.persistence.postgres.guice.module.CallLogPostgresPersistenceModule;
import com.hmdm.plugins.calllog.persistence.postgres.guice.module.CallLogPostgresServiceModule;
import com.hmdm.plugins.calllog.persistence.postgres.guice.module.CallLogRetentionTaskModule;

import javax.servlet.ServletContext;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * PostgreSQL persistence configuration for call log plugin
 */
public class CallLogPostgresPersistenceConfiguration implements CallLogPersistenceConfiguration {

    @Override
    public List<Module> getPersistenceModules(ServletContext context) {
        List<Module> modules = new ArrayList<>();
        modules.add(new CallLogPostgresLiquibaseModule(context));
        modules.add(new CallLogPostgresServiceModule());
        modules.add(new CallLogPostgresPersistenceModule(context));
        return modules;
    }

    @Override
    public Optional<List<Class<? extends PluginTaskModule>>> getTaskModules(ServletContext context) {
        return Optional.of(Collections.singletonList(CallLogRetentionTaskModule.class));
    }
}
