package com.hmdm.plugins.calllog;

import com.google.inject.Module;
import com.hmdm.plugin.PluginConfiguration;
import com.hmdm.plugin.PluginTaskModule;
import com.hmdm.plugins.calllog.guice.module.CallLogLiquibaseModule;
import com.hmdm.plugins.calllog.guice.module.CallLogRestModule;
import com.hmdm.plugins.calllog.persistence.CallLogPersistenceConfiguration;

import javax.servlet.ServletContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Main configuration class for Call Log plugin
 */
public class CallLogPluginConfigurationImpl implements PluginConfiguration {

    public static final String PLUGIN_ID = "calllog";

    public CallLogPluginConfigurationImpl() {
    }

    @Override
    public String getPluginId() {
        return PLUGIN_ID;
    }

    @Override
    public String getRootPackage() {
        return "com.hmdm.plugins.calllog";
    }

    @Override
    public List<Module> getPluginModules(ServletContext context) {
        try {
            List<Module> modules = new ArrayList<>();

            // Add Liquibase module for core changelog
            modules.add(new CallLogLiquibaseModule(context));

            // Load persistence configuration from context parameter
            final String configClass = context.getInitParameter("plugin.calllog.persistence.config.class");
            if (configClass != null && !configClass.trim().isEmpty()) {
                CallLogPersistenceConfiguration config = (CallLogPersistenceConfiguration) Class.forName(configClass)
                        .newInstance();
                modules.addAll(config.getPersistenceModules(context));
            }

            // Add REST module
            modules.add(new CallLogRestModule());

            return modules;

        } catch (InstantiationException | IllegalAccessException | ClassNotFoundException e) {
            throw new IllegalArgumentException(
                    "Could not initialize persistence layer for CallLog plugin", e);
        }
    }

    @Override
    public Optional<List<Class<? extends PluginTaskModule>>> getTaskModules(ServletContext context) {
        return Optional.empty();
    }
}
