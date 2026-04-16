package com.hmdm.plugins.smslog;

import com.google.inject.Module;
import com.hmdm.plugin.PluginConfiguration;
import com.hmdm.plugin.PluginTaskModule;
import com.hmdm.plugins.smslog.guice.module.SmsLogLiquibaseModule;
import com.hmdm.plugins.smslog.guice.module.SmsLogRestModule;
import com.hmdm.plugins.smslog.persistence.SmsLogPersistenceConfiguration;

import javax.servlet.ServletContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Main configuration class for SMS Log plugin
 */
public class SmsLogPluginConfigurationImpl implements PluginConfiguration {

    public static final String PLUGIN_ID = "smslog";

    public SmsLogPluginConfigurationImpl() {
    }

    @Override
    public String getPluginId() {
        return PLUGIN_ID;
    }

    @Override
    public String getRootPackage() {
        return "com.hmdm.plugins.smslog";
    }

    @Override
    public List<Module> getPluginModules(ServletContext context) {
        try {
            List<Module> modules = new ArrayList<>();

            // Add Liquibase module for core changelog
            modules.add(new SmsLogLiquibaseModule(context));

            // Load persistence configuration from context parameter
            final String configClass = context.getInitParameter("plugin.smslog.persistence.config.class");
            if (configClass != null && !configClass.trim().isEmpty()) {
                SmsLogPersistenceConfiguration config = (SmsLogPersistenceConfiguration) Class.forName(configClass)
                        .newInstance();
                modules.addAll(config.getPersistenceModules(context));
            }

            // Add REST module
            modules.add(new SmsLogRestModule());

            return modules;

        } catch (InstantiationException | IllegalAccessException | ClassNotFoundException e) {
            throw new IllegalArgumentException(
                    "Could not initialize persistence layer for SmsLog plugin", e);
        }
    }

    @Override
    public Optional<List<Class<? extends PluginTaskModule>>> getTaskModules(ServletContext context) {
        return Optional.empty();
    }
}
