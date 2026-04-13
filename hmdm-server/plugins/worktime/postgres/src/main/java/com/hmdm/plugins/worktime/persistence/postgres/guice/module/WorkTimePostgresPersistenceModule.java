package com.hmdm.plugins.worktime.persistence.postgres.guice.module;

import com.hmdm.guice.module.AbstractPersistenceModule;

import javax.servlet.ServletContext;

public class WorkTimePostgresPersistenceModule extends AbstractPersistenceModule {

    public WorkTimePostgresPersistenceModule(ServletContext context) {
        super(context);
    }

    @Override
    protected String getMapperPackageName() {
        return "com.hmdm.plugins.worktime.persistence.postgres.dao.mapper";
    }

    @Override
    protected String getDomainObjectsPackageName() {
        return "com.hmdm.plugins.worktime.model";
    }
}
