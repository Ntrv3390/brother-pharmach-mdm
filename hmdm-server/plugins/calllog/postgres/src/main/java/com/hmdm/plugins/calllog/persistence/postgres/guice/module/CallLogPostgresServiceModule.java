package com.hmdm.plugins.calllog.persistence.postgres.guice.module;

import com.google.inject.AbstractModule;
import javax.inject.Singleton;
import com.hmdm.plugins.calllog.persistence.CallLogDAO;
import com.hmdm.plugins.calllog.persistence.postgres.CallLogPostgresDAO;

/**
 * A module used to bind the service interfaces to specific implementations provided by the Postgres
 * persistence layer for Call Log plugin.
 */
public class CallLogPostgresServiceModule extends AbstractModule {

    /**
     * Constructs new CallLogPostgresServiceModule instance.
     */
    public CallLogPostgresServiceModule() {
    }

    /**
     * Configures the services exposed by the Postgres persistence layer for Call Log plugin.
     */
    @Override
    protected void configure() {
        bind(CallLogDAO.class).to(CallLogPostgresDAO.class).in(Singleton.class);
    }
}
