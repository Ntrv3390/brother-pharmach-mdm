package com.hmdm.plugins.smslog.guice.module;

import com.hmdm.guice.module.AbstractLiquibaseModule;
import com.hmdm.plugin.guice.module.PluginLiquibaseResourceAccessor;
import liquibase.resource.ResourceAccessor;

import javax.servlet.ServletContext;

/**
 * Liquibase module for call log plugin
 */
public class SmsLogLiquibaseModule extends AbstractLiquibaseModule {

    public SmsLogLiquibaseModule(ServletContext context) {
        super(context);
    }

    @Override
    protected String getChangeLogResourcePath() {
        String path = this.getClass().getResource("/liquibase/smslog.changelog.xml").getPath();
        if (!path.startsWith("jar:")) {
            path = "jar:" + path;
        }
        return path;
    }

    @Override
    protected ResourceAccessor getResourceAccessor() {
        return new PluginLiquibaseResourceAccessor();
    }
}
