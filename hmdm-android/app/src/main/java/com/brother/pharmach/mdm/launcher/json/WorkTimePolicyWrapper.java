package com.brother.pharmach.mdm.launcher.json;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class WorkTimePolicyWrapper {
    private String pluginId;
    private long timestamp;
    private EffectiveWorkTimePolicy policy;

    public String getPluginId() {
        return pluginId;
    }

    public void setPluginId(String pluginId) {
        this.pluginId = pluginId;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    public EffectiveWorkTimePolicy getPolicy() {
        return policy;
    }

    public void setPolicy(EffectiveWorkTimePolicy policy) {
        this.policy = policy;
    }
}
