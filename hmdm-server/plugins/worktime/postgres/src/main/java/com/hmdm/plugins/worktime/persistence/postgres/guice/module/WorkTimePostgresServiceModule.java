package com.hmdm.plugins.worktime.persistence.postgres.guice.module;

import com.google.inject.AbstractModule;
import javax.inject.Singleton;
import com.hmdm.plugins.worktime.persistence.WorkTimeDAO;
import com.hmdm.plugins.worktime.persistence.postgres.dao.PostgresWorkTimeDAO;

public class WorkTimePostgresServiceModule extends AbstractModule {

    public WorkTimePostgresServiceModule() {
    }

    @Override
    protected void configure() {
        bind(WorkTimeDAO.class).to(PostgresWorkTimeDAO.class).in(Singleton.class);
    }
}
