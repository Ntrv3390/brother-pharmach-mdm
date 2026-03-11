package com.hmdm.plugins.calllog.persistence;

import com.google.inject.Module;

import javax.servlet.ServletContext;
import java.util.List;

/**
 * Configuration interface for call log persistence layer
 */
public interface CallLogPersistenceConfiguration {

    /**
     * Get Guice modules for persistence layer
     */
    List<Module> getPersistenceModules(ServletContext context);
}
