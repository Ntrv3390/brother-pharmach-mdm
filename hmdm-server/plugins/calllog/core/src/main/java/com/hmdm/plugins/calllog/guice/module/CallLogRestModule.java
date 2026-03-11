package com.hmdm.plugins.calllog.guice.module;

import com.google.inject.servlet.ServletModule;
import com.hmdm.plugin.rest.PluginAccessFilter;
import com.hmdm.plugins.calllog.rest.resource.CallLogPublicResource;
import com.hmdm.plugins.calllog.rest.resource.CallLogResource;
import com.hmdm.rest.filter.AuthFilter;
import com.hmdm.rest.filter.PrivateIPFilter;
import com.hmdm.security.jwt.JWTFilter;

import java.util.Arrays;
import java.util.List;

/**
 * REST module for call log plugin
 */
public class CallLogRestModule extends ServletModule {

    // URLs requiring authentication (admin panel)
    private static final List<String> protectedResources = Arrays.asList(
        "/rest/plugins/calllog/private/*");

    public CallLogRestModule() {
    }

    @Override
    protected void configureServlets() {
        // Apply security filters to protected resources
        this.filter(protectedResources).through(JWTFilter.class);
        this.filter(protectedResources).through(AuthFilter.class);
        this.filter(protectedResources).through(PluginAccessFilter.class);
        this.filter(protectedResources).through(PrivateIPFilter.class);

        // Bind REST resources
        bind(CallLogResource.class);
        bind(CallLogPublicResource.class);
    }
}
