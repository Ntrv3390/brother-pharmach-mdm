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
    public List<WorkTimeDeviceOverride> getDeviceOverridesForDevice(int customerId, int deviceId) {
        return mapper.getDeviceOverridesForDevice(customerId, deviceId);
    }

    @Override
    public WorkTimeDeviceOverride getDeviceOverride(int customerId, int deviceId) {
        return mapper.getDeviceOverride(customerId, deviceId);
    }

    @Override
    public WorkTimeDeviceOverride getDeviceOverrideById(int customerId, int id) {
        return mapper.getDeviceOverrideById(customerId, id);
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

        if (policy.getId() != null && policy.getId() > 0) {
            WorkTimeDeviceOverride existing = mapper.getDeviceOverrideById(policy.getCustomerId(), policy.getId());
            if (existing != null) {
                mapper.updateDeviceOverride(policy);
                return;
            }
        }

        if (policy.getId() == null || policy.getId() <= 0) {
            mapper.insertDeviceOverride(policy);
        }
    }

    @Override
    @Transactional
    public void deleteDeviceOverride(int customerId, int deviceId) {
        mapper.deleteDeviceOverride(customerId, deviceId);
    }

    @Override
    @Transactional
    public void deleteDeviceOverrideById(int customerId, int id) {
        mapper.deleteDeviceOverrideById(customerId, id);
    }

    @Override
    @Transactional
    public void deleteDeviceOverridesForDevice(int customerId, int deviceId) {
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
    @Transactional
    public void markExceptionStartPushSentById(int id) {
        mapper.markExceptionStartPushSentById(id);
    }

    @Override
    @Transactional
    public void markExceptionEndPushSentById(int id) {
        mapper.markExceptionEndPushSentById(id);
    }

    @Override
    public List<WorkTimeDeviceOverride> getAllDeviceOverrides() {
        return mapper.getAllDeviceOverrides();
    }
}
