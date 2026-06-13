package com.hmdm.plugins.smslog.persistence;

import com.google.inject.Module;
import com.hmdm.plugin.PluginTaskModule;

import javax.servlet.ServletContext;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Configuration interface for SMS log persistence layer
 */
public interface SmsLogPersistenceConfiguration {

    /**
     * Get Guice modules for persistence layer
     */
    List<Module> getPersistenceModules(ServletContext context);

    /**
     * Get task modules for background tasks (e.g. retention cleanup).
     * Defaults to empty — override in persistence implementations.
     */
    default Optional<List<Class<? extends PluginTaskModule>>> getTaskModules(ServletContext context) {
        return Optional.of(Collections.emptyList());
    }
}
