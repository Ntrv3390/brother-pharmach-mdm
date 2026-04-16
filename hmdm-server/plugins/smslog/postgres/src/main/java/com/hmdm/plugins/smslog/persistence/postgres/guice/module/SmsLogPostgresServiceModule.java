package com.hmdm.plugins.smslog.persistence.postgres.guice.module;

import com.google.inject.AbstractModule;
import javax.inject.Singleton;
import com.hmdm.plugins.smslog.persistence.SmsLogDAO;
import com.hmdm.plugins.smslog.persistence.postgres.SmsLogPostgresDAO;

/**
 * A module used to bind the service interfaces to specific implementations provided by the Postgres
 * persistence layer for SMS Log plugin.
 */
public class SmsLogPostgresServiceModule extends AbstractModule {

    /**
     * Constructs new SmsLogPostgresServiceModule instance.
     */
    public SmsLogPostgresServiceModule() {
    }

    /**
     * Configures the services exposed by the Postgres persistence layer for SMS Log plugin.
     */
    @Override
    protected void configure() {
        bind(SmsLogDAO.class).to(SmsLogPostgresDAO.class).in(Singleton.class);
    }
}
