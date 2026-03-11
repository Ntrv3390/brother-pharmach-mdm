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
        // Note: typically it points to dedicated domain objects if different from
        // model,
        // but here we can reuse model or if we created domain objects in postgres
        // module.
        // Since we fetch directly to WorkTimePolicy, we can point to model or just
        // leave it.
        // Actually AbstractPersistenceModule typically scans for TypeHandlers or
        // Aliases.
        // We'll point to our mapper package or model package.
    }
}
