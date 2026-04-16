package com.hmdm.plugins.smslog.persistence.postgres.guice.module;

import com.hmdm.guice.module.AbstractPersistenceModule;

import javax.servlet.ServletContext;

/**
 * PostgreSQL-specific Guice module for call log plugin
 */
public class SmsLogPostgresPersistenceModule extends AbstractPersistenceModule {

    public SmsLogPostgresPersistenceModule(ServletContext context) {
        super(context);
    }

    @Override
    protected String getMapperPackageName() {
        return "com.hmdm.plugins.smslog.persistence.postgres.mapper";
    }

    @Override
    protected String getDomainObjectsPackageName() {
        return "com.hmdm.plugins.smslog.model";
    }
}
