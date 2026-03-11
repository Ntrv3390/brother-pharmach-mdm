package com.hmdm.plugins.calllog.persistence.postgres.guice.module;

import com.hmdm.guice.module.AbstractPersistenceModule;

import javax.servlet.ServletContext;

/**
 * PostgreSQL-specific Guice module for call log plugin
 */
public class CallLogPostgresPersistenceModule extends AbstractPersistenceModule {

    public CallLogPostgresPersistenceModule(ServletContext context) {
        super(context);
    }

    @Override
    protected String getMapperPackageName() {
        return "com.hmdm.plugins.calllog.persistence.postgres.mapper";
    }

    @Override
    protected String getDomainObjectsPackageName() {
        return "com.hmdm.plugins.calllog.model";
    }
}
