package com.hmdm.plugins.worktime.guice.module;

import com.google.inject.servlet.ServletModule;
import com.hmdm.plugin.rest.PluginAccessFilter;
import com.hmdm.plugins.worktime.rest.resource.WorkTimeResource;
import com.hmdm.plugins.worktime.rest.resource.WorkTimePublicResource;
import com.hmdm.rest.filter.AuthFilter;
import com.hmdm.rest.filter.PrivateIPFilter;
import com.hmdm.security.jwt.JWTFilter;

import java.util.Arrays;
import java.util.List;

public class WorkTimeRestModule extends ServletModule {

    // URLs requiring authentication (admin panel)
    private static final List<String> protectedResources = Arrays.asList(
        "/rest/plugins/worktime/private/*");

    public WorkTimeRestModule() {
    }

    @Override
    protected void configureServlets() {
        // Apply security filters to protected resources
        this.filter(protectedResources).through(JWTFilter.class);
        this.filter(protectedResources).through(AuthFilter.class);
        this.filter(protectedResources).through(PluginAccessFilter.class);
        this.filter(protectedResources).through(PrivateIPFilter.class);

        // Bind REST resource classes
        this.bind(WorkTimeResource.class);
        this.bind(WorkTimePublicResource.class);

        // Bind service
        this.bind(com.hmdm.plugins.worktime.service.WorkTimeService.class);
        // Bind sync hook so device sync responses can be filtered by worktime rules
        this.bind(com.hmdm.plugins.worktime.sync.WorkTimeSyncResponseHook.class);
    }
}
