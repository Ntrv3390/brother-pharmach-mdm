package com.hmdm.plugins.worktime.persistence.postgres.dao;

import javax.inject.Inject;

import com.hmdm.plugins.worktime.model.WorkTimeDevicePolicy;
import com.hmdm.plugins.worktime.model.WorkTimeDeviceOverride;
import com.hmdm.plugins.worktime.persistence.WorkTimeDAO;
import com.hmdm.plugins.worktime.persistence.postgres.dao.mapper.PostgresWorkTimeMapper;
import org.mybatis.guice.transactional.Transactional;
import java.util.List;

public class PostgresWorkTimeDAO implements WorkTimeDAO {

    private final PostgresWorkTimeMapper mapper;

    @Inject
    public PostgresWorkTimeDAO(PostgresWorkTimeMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public WorkTimeDevicePolicy getDevicePolicy(int customerId, int deviceId) {
        return mapper.getDevicePolicy(customerId, deviceId);
    }

    @Override
    public List<WorkTimeDevicePolicy> getDevicePolicies(int customerId) {
        return mapper.getDevicePolicies(customerId);
    }

    @Override
    @Transactional
    public void saveDevicePolicy(WorkTimeDevicePolicy policy) {
        WorkTimeDevicePolicy existing = mapper.getDevicePolicy(policy.getCustomerId(), policy.getDeviceId());

        if (existing == null) {
            mapper.insertDevicePolicy(policy);
        } else {
            mapper.updateDevicePolicy(policy);
        }
    }

    @Override
    public List<WorkTimeDeviceOverride> getDeviceOverrides(int customerId) {
        return mapper.getDeviceOverrides(customerId);
    }

    @Override
    public WorkTimeDeviceOverride getDeviceOverride(int customerId, int deviceId) {
        return mapper.getDeviceOverride(customerId, deviceId);
    }

    @Override
    @Transactional
    public void saveDeviceOverride(WorkTimeDeviceOverride policy) {
        if (!policy.isEnabled() && policy.getStartDateTime() != null && policy.getEndDateTime() != null) {
            policy.setStartBoundaryPushSent(Boolean.FALSE);
            policy.setEndBoundaryPushSent(Boolean.FALSE);
        } else {
            policy.setStartBoundaryPushSent(Boolean.TRUE);
            policy.setEndBoundaryPushSent(Boolean.TRUE);
        }

        WorkTimeDeviceOverride existing = mapper.getDeviceOverride(policy.getCustomerId(), policy.getDeviceId());
        if (existing == null) {
            mapper.insertDeviceOverride(policy);
        } else {
            mapper.updateDeviceOverride(policy);
        }
    }

    @Override
    @Transactional
    public void deleteDeviceOverride(int customerId, int deviceId) {
        mapper.deleteDeviceOverride(customerId, deviceId);
    }

    @Override
    @Transactional
    public void markExceptionStartPushSent(int customerId, int deviceId) {
        mapper.markExceptionStartPushSent(customerId, deviceId);
    }

    @Override
    @Transactional
    public void markExceptionEndPushSent(int customerId, int deviceId) {
        mapper.markExceptionEndPushSent(customerId, deviceId);
    }

    @Override
    public List<WorkTimeDeviceOverride> getAllDeviceOverrides() {
        return mapper.getAllDeviceOverrides();
    }
}
