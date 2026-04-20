package com.hmdm.plugins.smslog.persistence.postgres.mapper;

import com.hmdm.plugins.smslog.model.SmsLogRecord;
import com.hmdm.plugins.smslog.model.SmsLogSettings;
import org.apache.ibatis.annotations.*;

import java.util.List;
import java.util.Map;

/**
 * MyBatis mapper for SMS log operations
 */
public interface SmsLogMapper {

    @Insert("INSERT INTO plugin_smslog_data " +
            "(deviceid, phonenumber, contactname, smsmessage, messagetype, messagesimslot, smstimestamp, smsdate, createtime, customerid) " +
            "VALUES " +
            "(#{deviceId}, #{phoneNumber}, #{contactName}, #{message}, #{messageType}, #{simSlot}, #{smsTimestamp}, #{smsDate}, #{createTime}, #{customerId})")
    @SelectKey(statement = "SELECT currval('plugin_smslog_data_id_seq')",
            keyProperty = "id", before = false, resultType = int.class)
    void insertSmsLogRecord(SmsLogRecord record);

    @Insert({
        "<script>",
        "INSERT INTO plugin_smslog_data ",
                "(deviceid, phonenumber, contactname, smsmessage, messagetype, messagesimslot, smstimestamp, smsdate, createtime, customerid) ",
        "VALUES ",
        "<foreach collection='list' item='item' separator=','>",
                "(#{item.deviceId}, #{item.phoneNumber}, #{item.contactName}, #{item.message}, #{item.messageType}, ",
                "#{item.simSlot}, #{item.smsTimestamp}, #{item.smsDate}, #{item.createTime}, #{item.customerId})",
        "</foreach>",
        "</script>"
    })
    void insertSmsLogRecordsBatch(List<SmsLogRecord> records);

    @Select("SELECT id, deviceid AS deviceId, phonenumber AS phoneNumber, contactname AS contactName, " +
            "smsmessage AS message, messagetype AS messageType, messagesimslot AS simSlot, " +
            "smstimestamp AS smsTimestamp, smsdate AS smsDate, " +
            "createtime AS createTime, customerid AS customerId " +
            "FROM plugin_smslog_data " +
            "WHERE deviceid = #{deviceId} AND customerid = #{customerId} " +
            "ORDER BY smstimestamp DESC")
    List<SmsLogRecord> getSmsLogsByDevice(@Param("deviceId") int deviceId, @Param("customerId") int customerId);

    @Select("SELECT id, deviceid AS deviceId, phonenumber AS phoneNumber, contactname AS contactName, " +
            "smsmessage AS message, messagetype AS messageType, messagesimslot AS simSlot, " +
            "smstimestamp AS smsTimestamp, smsdate AS smsDate, " +
            "createtime AS createTime, customerid AS customerId " +
            "FROM plugin_smslog_data " +
            "WHERE deviceid = #{deviceId} AND customerid = #{customerId} " +
            "ORDER BY smstimestamp DESC " +
            "LIMIT #{limit} OFFSET #{offset}")
    List<SmsLogRecord> getSmsLogsByDevicePaged(Map<String, Object> params);

    @Select("SELECT COUNT(*) FROM plugin_smslog_data " +
            "WHERE deviceid = #{deviceId} AND customerid = #{customerId}")
    int getSmsLogsCountByDevice(@Param("deviceId") int deviceId, @Param("customerId") int customerId);

    @Select({"<script>",
            "SELECT id, deviceid AS deviceId, phonenumber AS phoneNumber, contactname AS contactName, ",
            "smsmessage AS message, messagetype AS messageType, messagesimslot AS simSlot, ",
            "smstimestamp AS smsTimestamp, smsdate AS smsDate, ",
            "createtime AS createTime, customerid AS customerId ",
            "FROM plugin_smslog_data ",
            "WHERE deviceid = #{deviceId} AND customerid = #{customerId} ",
            "<if test='messageType != null'>AND messagetype = #{messageType} </if>",
            "<if test='simSlot != null'>AND messagesimslot = #{simSlot} </if>",
            "<if test='search != null and search != \"\"'>",
            "AND (LOWER(phonenumber) LIKE LOWER(CONCAT('%',#{search},'%')) ",
            "OR LOWER(contactname) LIKE LOWER(CONCAT('%',#{search},'%')) ",
            "OR LOWER(smsmessage) LIKE LOWER(CONCAT('%',#{search},'%'))) ",
            "</if>",
            "ORDER BY smstimestamp DESC ",
            "LIMIT #{limit} OFFSET #{offset}",
            "</script>"})
    List<SmsLogRecord> getSmsLogsByDevicePagedFiltered(Map<String, Object> params);

    @Select({"<script>",
            "SELECT COUNT(*) FROM plugin_smslog_data ",
            "WHERE deviceid = #{deviceId} AND customerid = #{customerId} ",
            "<if test='messageType != null'>AND messagetype = #{messageType} </if>",
            "<if test='simSlot != null'>AND messagesimslot = #{simSlot} </if>",
            "<if test='search != null and search != \"\"'>",
            "AND (LOWER(phonenumber) LIKE LOWER(CONCAT('%',#{search},'%')) ",
            "OR LOWER(contactname) LIKE LOWER(CONCAT('%',#{search},'%')) ",
            "OR LOWER(smsmessage) LIKE LOWER(CONCAT('%',#{search},'%'))) ",
            "</if>",
            "</script>"})
    int getSmsLogsCountByDeviceFiltered(Map<String, Object> params);

    @Delete("DELETE FROM plugin_smslog_data " +
            "WHERE customerid = #{customerId} " +
            "AND createtime < EXTRACT(EPOCH FROM (NOW() - make_interval(days => #{retentionDays}))) * 1000")
    int deleteOldSmsLogs(@Param("customerId") int customerId, @Param("retentionDays") int retentionDays);

    @Delete("DELETE FROM plugin_smslog_data " +
            "WHERE deviceid = #{deviceId} AND customerid = #{customerId}")
    int deleteSmsLogsByDevice(@Param("deviceId") int deviceId, @Param("customerId") int customerId);

    @Select("SELECT id, customerid AS customerId, enabled, retentiondays AS retentionDays " +
            "FROM plugin_smslog_settings WHERE customerid = #{customerId}")
    SmsLogSettings getSettings(@Param("customerId") int customerId);

    @Insert("INSERT INTO plugin_smslog_settings (customerid, enabled, retentiondays) " +
            "VALUES (#{customerId}, #{enabled}, #{retentionDays})")
    void insertSettings(SmsLogSettings settings);

    @Update("UPDATE plugin_smslog_settings " +
            "SET enabled = #{enabled}, retentiondays = #{retentionDays} " +
            "WHERE customerid = #{customerId}")
    void updateSettings(SmsLogSettings settings);
}
