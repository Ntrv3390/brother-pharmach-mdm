package com.hmdm.plugins.worktime;

import com.google.inject.Module;
import com.hmdm.plugin.PluginConfiguration;
import com.hmdm.plugin.PluginTaskModule;
import com.hmdm.plugins.worktime.guice.module.WorkTimeLiquibaseModule;
import com.hmdm.plugins.worktime.guice.module.WorkTimeRestModule;
import com.hmdm.plugins.worktime.persistence.WorkTimePersistenceConfiguration;

import javax.servlet.ServletContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class WorkTimePluginConfigurationImpl implements PluginConfiguration {

    public static final String PLUGIN_ID = "worktime";

    public WorkTimePluginConfigurationImpl() {
    }

    @Override
    public String getPluginId() {
        return PLUGIN_ID;
    }

    @Override
    public String getRootPackage() {
        return "com.hmdm.plugins.worktime";
    }

    @Override
    public List<Module> getPluginModules(ServletContext context) {
        try {
            List<Module> modules = new ArrayList<>();

            // Add Liquibase module for core changelog
            modules.add(new WorkTimeLiquibaseModule(context));

            // Load persistence configuration from context parameter
            final String configClass = context.getInitParameter("plugin.worktime.persistence.config.class");
            if (configClass != null && !configClass.trim().isEmpty()) {
                WorkTimePersistenceConfiguration config = (WorkTimePersistenceConfiguration) Class.forName(configClass)
                        .newInstance();
                modules.addAll(config.getPersistenceModules(context));
            }

            // Add REST module
            modules.add(new WorkTimeRestModule());

            return modules;

        } catch (InstantiationException | IllegalAccessException | ClassNotFoundException e) {
            throw new IllegalArgumentException(
                    "Could not initialize persistence layer for WorkTime plugin", e);
        }
    }

    @Override
    public Optional<List<Class<? extends PluginTaskModule>>> getTaskModules(ServletContext context) {
        try {
            List<Class<? extends PluginTaskModule>> modules = new ArrayList<>();

            final String configClass = context.getInitParameter("plugin.worktime.persistence.config.class");
            if (configClass != null && !configClass.trim().isEmpty()) {
                WorkTimePersistenceConfiguration config = (WorkTimePersistenceConfiguration) Class.forName(configClass)
                        .newInstance();
                config.getTaskModules(context).ifPresent(modules::addAll);
            }

            return Optional.of(modules);
        } catch (InstantiationException | IllegalAccessException | ClassNotFoundException e) {
            throw new IllegalArgumentException("Could not get list of task modules for WorkTime plugin", e);
        }
    }
}
