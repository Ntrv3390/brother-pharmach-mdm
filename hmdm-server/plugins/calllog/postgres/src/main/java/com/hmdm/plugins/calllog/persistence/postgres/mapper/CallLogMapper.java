package com.hmdm.plugins.calllog.persistence.postgres.mapper;

import com.hmdm.plugins.calllog.model.CallLogRecord;
import com.hmdm.plugins.calllog.model.CallLogSettings;
import org.apache.ibatis.annotations.*;

import java.util.List;
import java.util.Map;

/**
 * MyBatis mapper for call log operations
 */
public interface CallLogMapper {

    @Insert("INSERT INTO plugin_calllog_data " +
            "(deviceid, phonenumber, contactname, calltype, duration, calltimestamp, calldate, createtime, customerid) " +
            "VALUES " +
            "(#{deviceId}, #{phoneNumber}, #{contactName}, #{callType}, #{duration}, #{callTimestamp}, #{callDate}, #{createTime}, #{customerId})")
    @SelectKey(statement = "SELECT currval('plugin_calllog_data_id_seq')",
            keyProperty = "id", before = false, resultType = int.class)
    void insertCallLogRecord(CallLogRecord record);

    @Insert({
        "<script>",
        "INSERT INTO plugin_calllog_data ",
        "(deviceid, phonenumber, contactname, calltype, duration, calltimestamp, calldate, createtime, customerid) ",
        "VALUES ",
        "<foreach collection='list' item='item' separator=','>",
        "(#{item.deviceId}, #{item.phoneNumber}, #{item.contactName}, #{item.callType}, ",
        "#{item.duration}, #{item.callTimestamp}, #{item.callDate}, #{item.createTime}, #{item.customerId})",
        "</foreach>",
        "</script>"
    })
    void insertCallLogRecordsBatch(List<CallLogRecord> records);

    @Select("SELECT id, deviceid AS deviceId, phonenumber AS phoneNumber, contactname AS contactName, " +
            "calltype AS callType, duration, calltimestamp AS callTimestamp, calldate AS callDate, " +
            "createtime AS createTime, customerid AS customerId " +
            "FROM plugin_calllog_data " +
            "WHERE deviceid = #{deviceId} AND customerid = #{customerId} " +
            "ORDER BY calltimestamp DESC")
    List<CallLogRecord> getCallLogsByDevice(@Param("deviceId") int deviceId, @Param("customerId") int customerId);

    @Select("SELECT id, deviceid AS deviceId, phonenumber AS phoneNumber, contactname AS contactName, " +
            "calltype AS callType, duration, calltimestamp AS callTimestamp, calldate AS callDate, " +
            "createtime AS createTime, customerid AS customerId " +
            "FROM plugin_calllog_data " +
            "WHERE deviceid = #{deviceId} AND customerid = #{customerId} " +
            "ORDER BY calltimestamp DESC " +
            "LIMIT #{limit} OFFSET #{offset}")
    List<CallLogRecord> getCallLogsByDevicePaged(Map<String, Object> params);

    @Select("SELECT COUNT(*) FROM plugin_calllog_data " +
            "WHERE deviceid = #{deviceId} AND customerid = #{customerId}")
    int getCallLogsCountByDevice(@Param("deviceId") int deviceId, @Param("customerId") int customerId);

    @Delete("DELETE FROM plugin_calllog_data " +
            "WHERE customerid = #{customerId} " +
            "AND createtime < EXTRACT(EPOCH FROM (NOW() - make_interval(days => #{retentionDays}))) * 1000")
    int deleteOldCallLogs(@Param("customerId") int customerId, @Param("retentionDays") int retentionDays);

    @Delete("DELETE FROM plugin_calllog_data " +
            "WHERE deviceid = #{deviceId} AND customerid = #{customerId}")
    int deleteCallLogsByDevice(@Param("deviceId") int deviceId, @Param("customerId") int customerId);

    @Select("SELECT id, customerid AS customerId, enabled, retentiondays AS retentionDays " +
            "FROM plugin_calllog_settings WHERE customerid = #{customerId}")
    CallLogSettings getSettings(@Param("customerId") int customerId);

    @Insert("INSERT INTO plugin_calllog_settings (customerid, enabled, retentiondays) " +
            "VALUES (#{customerId}, #{enabled}, #{retentionDays})")
    void insertSettings(CallLogSettings settings);

    @Update("UPDATE plugin_calllog_settings " +
            "SET enabled = #{enabled}, retentiondays = #{retentionDays} " +
            "WHERE customerid = #{customerId}")
    void updateSettings(CallLogSettings settings);
}
