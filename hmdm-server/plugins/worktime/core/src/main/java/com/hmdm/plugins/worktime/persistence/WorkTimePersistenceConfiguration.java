package com.hmdm.plugins.worktime.persistence;

import com.google.inject.Module;
import com.hmdm.plugin.PluginTaskModule;

import javax.servlet.ServletContext;
import java.util.List;
import java.util.Optional;

public interface WorkTimePersistenceConfiguration {

    List<Module> getPersistenceModules(ServletContext context);

    default Optional<List<Class<? extends PluginTaskModule>>> getTaskModules(ServletContext context) {
        return Optional.empty();
    }
}
