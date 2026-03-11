package com.hmdm.plugins.worktime.persistence.postgres;

import com.google.inject.Module;
import com.hmdm.plugin.PluginTaskModule;
import com.hmdm.plugins.worktime.persistence.WorkTimePersistenceConfiguration;
import com.hmdm.plugins.worktime.persistence.postgres.guice.module.*;

import javax.servlet.ServletContext;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

public class WorkTimePostgresPersistenceConfiguration implements WorkTimePersistenceConfiguration {

    public WorkTimePostgresPersistenceConfiguration() {
    }

    @Override
    public List<Module> getPersistenceModules(ServletContext context) {
        List<Module> modules = new ArrayList<>();

        modules.add(new WorkTimePostgresLiquibaseModule(context));
        modules.add(new WorkTimePostgresServiceModule());
        modules.add(new WorkTimePostgresPersistenceModule(context));

        return modules;
    }

    @Override
    public Optional<List<Class<? extends PluginTaskModule>>> getTaskModules(ServletContext context) {
        return Optional.of(Collections.singletonList(WorkTimePostgresTaskModule.class));
    }
}
