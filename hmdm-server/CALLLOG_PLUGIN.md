# Call Log Plugin - Complete Technical Documentation

## Table of Contents

1. [Overview](#overview)
2. [Purpose & Features](#purpose--features)
3. [Technology Stack](#technology-stack)
4. [Plugin Architecture](#plugin-architecture)
5. [Directory Structure](#directory-structure)
6. [Core Files & Classes](#core-files--classes)
7. [Database Schema](#database-schema)
8. [REST API Endpoints](#rest-api-endpoints)
9. [Android Integration](#android-integration)
10. [Frontend Integration](#frontend-integration)
11. [Configuration](#configuration)
12. [Security & Privacy](#security--privacy)

---

## Overview

The **Call Log Plugin** is a Mobile Device Management (MDM) feature module for Headwind MDM that provides call log collection and monitoring capabilities. It allows administrators to collect, view, and manage phone call history from managed Android devices.

### Plugin Identification

| Property           | Value                                  |
| ------------------ | -------------------------------------- |
| **Plugin ID**      | `calllog`                              |
| **Plugin Name**    | Call Log Collection Plugin for MDM     |
| **Version**        | 1.0.0                                  |
| **Package**        | `com.hmdm.plugins.calllog`             |
| **Root Path**      | `hmdm-server/plugins/calllog/`         |

---

## Purpose & Features

### What It Does

The Call Log Plugin enables MDM administrators to monitor phone call activity on managed Android devices. It:

- **Collects call data**: Automatically receives call logs from Android devices
- **Tracks call history**: Records phone numbers, contacts, types, duration, and timestamps
- **Provides visibility**: Displays call logs in a modern, user-friendly web interface
- **Supports filtering**: Allows pagination and searching through large call datasets
- **Manages retention**: Configurable data retention policies with automatic cleanup
- **Ensures privacy**: Multi-tenant isolation with customer-level security
- **Supports bulk operations**: Delete all logs per device or apply retention rules
- **Multi-language support**: UI translated into 12 languages

### Call Types Tracked

| Type | Value | Description | Android Constant |
|------|-------|-------------|------------------|
| **Incoming** | 1 | Received calls | `CallLog.Calls.INCOMING_TYPE` |
| **Outgoing** | 2 | Dialed calls | `CallLog.Calls.OUTGOING_TYPE` |
| **Missed** | 3 | Missed calls | `CallLog.Calls.MISSED_TYPE` |
| **Rejected** | 4 | Rejected calls | `CallLog.Calls.REJECTED_TYPE` |
| **Blocked** | 5 | Blocked calls | `CallLog.Calls.BLOCKED_TYPE` |

### Use Cases

1. **Corporate Compliance**: Track business phone usage on company-owned devices
2. **Fleet Management**: Monitor field worker communications and call patterns
3. **Security Auditing**: Review suspicious call activity and patterns
4. **Usage Analytics**: Analyze device communication patterns and trends
5. **Support & Troubleshooting**: Verify call-related issues reported by users
6. **Time Tracking**: Correlate call times with work hours for billing
7. **Legal Compliance**: Maintain call records for regulatory requirements

---

## Technology Stack

### Backend Technologies

| Component            | Technology / Framework                                                                  |
| -------------------- | --------------------------------------------------------------------------------------- |
| **Language**         | Java 1.8+                                                                               |
| **Build System**     | Maven (multi-module architecture)                                                       |
| **Dependency Injection** | Google Guice (for IoC and module configuration)                                     |
| **REST Framework**   | Jersey 2.x (JAX-RS implementation)                                                      |
| **Database**         | PostgreSQL                                                                              |
| **Persistence**      | MyBatis (SQL mapping with annotations)                                                  |
| **JSON Processing**  | Jackson (Jackson Databind & Jackson Annotations)                                        |
| **API Documentation** | Swagger/OpenAPI annotations                                                            |
| **Security**         | JWT authentication, SecurityContext, permission-based access control                    |

### Frontend Technologies

| Component      | Technology         |
| -------------- | ------------------ |
| **Framework**  | AngularJS 1.x      |
| **Templates**  | HTML5              |
| **Styling**    | Bootstrap + Custom CSS |
| **i18n**       | JSON-based localization |
| **UI Components** | Modern gradient design with custom styles |

### Core Dependencies

```xml
<!-- Main server components -->
<dependency>
    <groupId>com.hmdm</groupId>
    <artifactId>server</artifactId>
    <version>5.31.3</version>
</dependency>

<!-- PostgreSQL JDBC -->
<dependency>
    <groupId>org.postgresql</groupId>
    <artifactId>postgresql</artifactId>
    <version>42.2.2</version>
</dependency>

<!-- MyBatis for SQL mapping -->
<dependency>
    <groupId>org.mybatis</groupId>
    <artifactId>mybatis</artifactId>
    <version>3.4.6</version>
</dependency>

<!-- Google Guice -->
<dependency>
    <groupId>com.google.inject</groupId>
    <artifactId>guice</artifactId>
    <version>4.1.0</version>
</dependency>
```

---

## Plugin Architecture

The Call Log Plugin follows the standard three-module plugin architecture:

```
┌─────────────────────────────────────────────────────┐
│           CORE MODULE (Database-Agnostic)           │
├─────────────────────────────────────────────────────┤
│ • Model classes (CallLogRecord, CallLogSettings)    │
│ • DAO interfaces (data access contracts)            │
│ • REST resources (API endpoints)                    │
│ • Guice modules (dependency configuration)          │
└─────────────────────────────────────────────────────┘
                          ↓
┌─────────────────────────────────────────────────────┐
│      POSTGRES MODULE (Database-Specific)            │
├─────────────────────────────────────────────────────┤
│ • DAO implementations (PostgreSQL-specific)         │
│ • MyBatis mappers (SQL annotations)                 │
│ • Guice modules (persistence configuration)         │
│ • Database configuration classes                    │
└─────────────────────────────────────────────────────┘
                          ↓
┌─────────────────────────────────────────────────────┐
│       FRONTEND MODULE (AngularJS)                   │
├─────────────────────────────────────────────────────┤
│ • AngularJS module definition                       │
│ • Modal view (call log display)                     │
│ • i18n translation files (12 languages)             │
│ • Controllers, services, factories                  │
└─────────────────────────────────────────────────────┘
```

---

## Directory Structure

### Complete Call Log Plugin Tree

```
hmdm-server/
├── plugins/
│   ├── pom.xml                                      # Parent POM (includes calllog module)
│   └── calllog/                                     # CALL LOG PLUGIN ROOT
│       ├── pom.xml                                  # Plugin parent POM
│       │
│       ├── ANDROID_INTEGRATION_GUIDE.md             # Android integration documentation
│       ├── API_TESTING_GUIDE.md                     # API testing guide with curl commands
│       ├── IMPLEMENTATION_SUMMARY.md                # Implementation overview
│       └── STATUS_AND_NEXT_STEPS.md                 # Project status document
│       │
│       ├── core/                                    # CORE MODULE (Database-Agnostic)
│       │   ├── pom.xml                              # Core module POM
│       │   └── src/
│       │       └── main/
│       │           └── java/
│       │               └── com/hmdm/plugins/calllog/
│       │                   │
│       │                   ├── model/                                   # Domain Models
│       │                   │   ├── CallLogRecord.java                 # Call log entry model
│       │                   │   └── CallLogSettings.java               # Plugin settings model
│       │                   │
│       │                   ├── persistence/                             # Data Access Layer
│       │                   │   └── CallLogDAO.java                    # DAO Interface (contract)
│       │                   │
│       │                   ├── rest/
│       │                   │   └── resource/                            # REST Endpoints
│       │                   │       ├── CallLogResource.java            # Admin REST endpoints
│       │                   │       └── CallLogPublicResource.java      # Public device endpoints
│       │                   │
│       │                   └── guice/
│       │                       └── module/                              # Guice Dependency Injection
│       │                           └── CallLogRestModule.java          # REST API DI
│       │
│       ├── postgres/                                # POSTGRES MODULE (Database-Specific)
│       │   ├── pom.xml                              # Postgres module POM
│       │   └── src/
│       │       └── main/
│       │           └── java/
│       │               └── com/hmdm/plugins/calllog/
│       │                   └── persistence/postgres/
│       │                       ├── CallLogPostgresDAO.java             # PostgreSQL DAO implementation
│       │                       ├── CallLogPostgresPersistenceConfiguration.java  # DB config
│       │                       │
│       │                       ├── mapper/                             # MyBatis SQL Mappers
│       │                       │   └── CallLogMapper.java             # Annotated mapper interface
│       │                       │
│       │                       └── guice/module/                       # Postgres DI
│       │                           ├── CallLogPostgresServiceModule.java    # Service bindings
│       │                           └── CallLogPostgresPersistenceModule.java # MyBatis config
│       │
│       └── src/                                     # FRONTEND MODULE (AngularJS)
│           └── main/
│               └── webapp/
│                   ├── calllog.module.js            # AngularJS main module
│                   │
│                   ├── views/                       # HTML Templates
│                   │   └── modal.html               # Call logs modal view
│                   │
│                   └── i18n/                        # Internationalization
│                       ├── en_US.json               # English translations
│                       ├── ru_RU.json               # Russian translations
│                       ├── es_ES.json               # Spanish translations
│                       ├── fr_FR.json               # French translations
│                       ├── de_DE.json               # German translations
│                       ├── zh_CN.json               # Chinese Simplified
│                       ├── zh_TW.json               # Chinese Traditional
│                       ├── it_IT.json               # Italian translations
│                       ├── ja_JP.json               # Japanese translations
│                       ├── pt_PT.json               # Portuguese translations
│                       ├── tr_TR.json               # Turkish translations
│                       └── vi_VN.json               # Vietnamese translations
│
└── server/                                          # Main Server Module
    ├── src/main/webapp/app/components/main/
    │   ├── controller/
    │   │   └── devices.controller.js                # <- MODIFIED: Added viewCallLogs() function
    │   └── view/
    │       └── devices.html                         # <- MODIFIED: Added "View Call Logs" button
    └── conf/
        └── context.xml                              # <- Database connection configuration
```

### Key Directories Summary

| Directory | Purpose | Location |
|-----------|---------|----------|
| **Core Java** | Business logic, models, DAOs | `plugins/calllog/core/src/main/java/` |
| **Postgres Java** | PostgreSQL implementations | `plugins/calllog/postgres/src/main/java/` |
| **Frontend** | AngularJS module & views | `plugins/calllog/src/main/webapp/` |
| **i18n** | Translation files (12 languages) | `plugins/calllog/src/main/webapp/i18n/` |
| **Documentation** | Integration & API guides | `plugins/calllog/*.md` |

---

## Core Files & Classes

### 1. Model Classes

#### 1.1 CallLogRecord

**File:** `plugins/calllog/core/src/main/java/com/hmdm/plugins/calllog/model/CallLogRecord.java`

Represents a single call log entry:

```java
public class CallLogRecord {
    private Integer id;                   // Unique record identifier
    private Integer deviceId;             // Device ID (FK to devices table)
    private String phoneNumber;           // Phone number (dialed/received)
    private String contactName;           // Contact name (from device contacts)
    private Integer callType;             // Call type (1-5)
    private Long duration;                // Call duration in seconds
    private Long callTimestamp;           // Call timestamp (epoch milliseconds)
    private String callDate;              // Formatted date string (yyyy-MM-dd HH:mm:ss)
    private Long createTime;              // Server record creation time (epoch ms)
    private Integer customerId;           // Customer/organization ID
    
    // Standard getters and setters...
}
```

**Properties:**

| Property | Type | Description |
|----------|------|-------------|
| `id` | Integer | Unique record identifier (auto-generated) |
| `deviceId` | Integer | Device ID from which log was received |
| `phoneNumber` | String | Phone number involved in the call |
| `contactName` | String | Contact name (null if not in contacts) |
| `callType` | Integer | 1=Incoming, 2=Outgoing, 3=Missed, 4=Rejected, 5=Blocked |
| `duration` | Long | Call duration in seconds (0 for missed/rejected) |
| `callTimestamp` | Long | When the call occurred (epoch milliseconds) |
| `callDate` | String | Human-readable date (yyyy-MM-dd HH:mm:ss) |
| `createTime` | Long | When record was created on server (epoch ms) |
| `customerId` | Integer | Customer/organization identifier |

#### 1.2 CallLogSettings

**File:** `plugins/calllog/core/src/main/java/com/hmdm/plugins/calllog/model/CallLogSettings.java`

Plugin configuration settings per customer:

```java
public class CallLogSettings {
    private Integer id;                   // Settings record ID
    private Integer customerId;           // Customer/organization ID
    private Boolean enabled;              // Enable/disable plugin
    private Integer retentionDays;        // Days to keep logs (0 = forever)
    
    // Standard getters and setters...
}
```

**Properties:**

| Property | Type | Description |
|----------|------|-------------|
| `id` | Integer | Settings record identifier |
| `customerId` | Integer | Customer/organization ID (unique per customer) |
| `enabled` | Boolean | Enable (true) or disable (false) call log collection |
| `retentionDays` | Integer | Number of days to retain logs (0 = keep forever) |

---

### 2. DAO Interface

#### 2.1 CallLogDAO

**File:** `plugins/calllog/core/src/main/java/com/hmdm/plugins/calllog/persistence/CallLogDAO.java`

Data access contract for call log operations:

```java
public interface CallLogDAO {
    // Insert operations
    void insertCallLogRecord(CallLogRecord record);
    void insertCallLogRecordsBatch(List<CallLogRecord> records);
    
    // Query operations
    List<CallLogRecord> getCallLogsByDevice(int deviceId, int customerId);
    List<CallLogRecord> getCallLogsByDevicePaged(int deviceId, int customerId, int limit, int offset);
    int getCallLogsCountByDevice(int deviceId, int customerId);
    
    // Delete operations
    int deleteOldCallLogs(int customerId, int retentionDays);
    int deleteCallLogsByDevice(int deviceId, int customerId);
    
    // Settings operations
    CallLogSettings getSettings(int customerId);
    void saveSettings(CallLogSettings settings);
}
```

**Methods:**

| Method | Parameters | Returns | Purpose |
|--------|------------|---------|---------|
| `insertCallLogRecord` | CallLogRecord | void | Insert single call log record |
| `insertCallLogRecordsBatch` | List<CallLogRecord> | void | Batch insert multiple records |
| `getCallLogsByDevice` | deviceId, customerId | List<CallLogRecord> | Get all logs for a device |
| `getCallLogsByDevicePaged` | deviceId, customerId, limit, offset | List<CallLogRecord> | Get paginated logs |
| `getCallLogsCountByDevice` | deviceId, customerId | int | Count total logs for a device |
| `deleteOldCallLogs` | customerId, retentionDays | int | Delete logs older than retention period |
| `deleteCallLogsByDevice` | deviceId, customerId | int | Delete all logs for a device |
| `getSettings` | customerId | CallLogSettings | Get plugin settings |
| `saveSettings` | CallLogSettings | void | Save/update plugin settings |

---

### 3. DAO Implementation (PostgreSQL)

#### 3.1 CallLogPostgresDAO

**File:** `plugins/calllog/postgres/src/main/java/com/hmdm/plugins/calllog/persistence/postgres/CallLogPostgresDAO.java`

PostgreSQL implementation of CallLogDAO:

```java
@Singleton
public class CallLogPostgresDAO implements CallLogDAO {
    private final CallLogMapper mapper;
    
    @Inject
    public CallLogPostgresDAO(CallLogMapper mapper) {
        this.mapper = mapper;
    }
    
    // Implementation delegates to MyBatis mapper...
}
```

#### 3.2 CallLogMapper

**File:** `plugins/calllog/postgres/src/main/java/com/hmdm/plugins/calllog/persistence/postgres/mapper/CallLogMapper.java`

MyBatis mapper with SQL annotations:

```java
public interface CallLogMapper {
    
    @Insert("INSERT INTO plugin_calllog_data " +
            "(deviceid, phonenumber, contactname, calltype, duration, " +
            "calltimestamp, calldate, createtime, customerid) " +
            "VALUES (#{deviceId}, #{phoneNumber}, #{contactName}, #{callType}, " +
            "#{duration}, #{callTimestamp}, #{callDate}, #{createTime}, #{customerId})")
    @SelectKey(statement = "SELECT currval('plugin_calllog_data_id_seq')",
            keyProperty = "id", before = false, resultType = int.class)
    void insertCallLogRecord(CallLogRecord record);
    
    @Select("SELECT id, deviceid AS deviceId, phonenumber AS phoneNumber, " +
            "contactname AS contactName, calltype AS callType, duration, " +
            "calltimestamp AS callTimestamp, calldate AS callDate, " +
            "createtime AS createTime, customerid AS customerId " +
            "FROM plugin_calllog_data " +
            "WHERE deviceid = #{deviceId} AND customerid = #{customerId} " +
            "ORDER BY calltimestamp DESC " +
            "LIMIT #{limit} OFFSET #{offset}")
    List<CallLogRecord> getCallLogsByDevicePaged(Map<String, Object> params);
    
    // Additional methods...
}
```

---

### 4. REST Resources

#### 4.1 CallLogResource (Admin API)

**File:** `plugins/calllog/core/src/main/java/com/hmdm/plugins/calllog/rest/resource/CallLogResource.java`

Admin panel REST endpoints (authenticated):

```java
@Api(tags = {"Call Log Plugin"})
@Path("/plugins/calllog/private")
@Produces(MediaType.APPLICATION_JSON)
public class CallLogResource {
    
    @GET
    @Path("/device/{deviceId}")
    @ApiOperation(value = "Get call logs for a device")
    public Response getDeviceCallLogs(
            @PathParam("deviceId") int deviceId,
            @QueryParam("page") @DefaultValue("0") int page,
            @QueryParam("pageSize") @DefaultValue("50") int pageSize) {
        // Implementation...
    }
    
    @GET
    @Path("/settings")
    @ApiOperation(value = "Get call log plugin settings")
    public Response getSettings() {
        // Implementation...
    }
    
    @POST
    @Path("/settings")
    @ApiOperation(value = "Save call log plugin settings")
    public Response saveSettings(CallLogSettings settings) {
        // Implementation...
    }
    
    @DELETE
    @Path("/device/{deviceId}")
    @ApiOperation(value = "Delete all call logs for a device")
    public Response deleteDeviceCallLogs(@PathParam("deviceId") int deviceId) {
        // Implementation...
    }
}
```

#### 4.2 CallLogPublicResource (Android API)

**File:** `plugins/calllog/core/src/main/java/com/hmdm/plugins/calllog/rest/resource/CallLogPublicResource.java`

Public endpoints for Android devices (no authentication required):

```java
@Api(tags = {"Call Log Plugin - Public"})
@Path("/plugins/calllog/public")
@Produces(MediaType.APPLICATION_JSON)
public class CallLogPublicResource {
    
    @POST
    @Path("/submit/{deviceNumber}")
    @Consumes(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Submit call logs from device")
    public Response submitCallLogs(
            @PathParam("deviceNumber") String deviceNumber,
            List<CallLogRecord> logs) {
        // Validates device, checks if enabled, bulk inserts logs
    }
    
    @GET
    @Path("/enabled/{deviceNumber}")
    @ApiOperation(value = "Check if call log collection is enabled")
    public Response isEnabled(@PathParam("deviceNumber") String deviceNumber) {
        // Returns boolean enabled status
    }
}
```

---

### 5. Guice Modules

#### 5.1 CallLogRestModule

**File:** `plugins/calllog/core/src/main/java/com/hmdm/plugins/calllog/guice/module/CallLogRestModule.java`

REST API configuration and endpoint registration:

```java
public class CallLogRestModule extends AbstractModule {
    @Override
    protected void configure() {
        bind(CallLogResource.class);
        bind(CallLogPublicResource.class);
    }
}
```

#### 5.2 CallLogPostgresServiceModule

**File:** `plugins/calllog/postgres/src/main/java/com/hmdm/plugins/calllog/persistence/postgres/guice/module/CallLogPostgresServiceModule.java`

Service-level bindings:

```java
public class CallLogPostgresServiceModule extends AbstractModule {
    @Override
    protected void configure() {
        // Bindings for services
    }
}
```

#### 5.3 CallLogPostgresPersistenceModule

**File:** `plugins/calllog/postgres/src/main/java/com/hmdm/plugins/calllog/persistence/postgres/guice/module/CallLogPostgresPersistenceModule.java`

MyBatis mapper configuration:

```java
public class CallLogPostgresPersistenceModule extends AbstractModule {
    @Override
    protected void configure() {
        bind(CallLogDAO.class).to(CallLogPostgresDAO.class);
        bind(CallLogMapper.class).toProvider(
            new MybatisMapperProvider<>(CallLogMapper.class));
    }
}
```

---

## Database Schema

### Tables Created

#### 1. plugin_calllog_data

Main call log storage table:

| Column | Type | Nullable | Default | Purpose |
|--------|------|----------|---------|---------|
| `id` | SERIAL | NO | (PK) | Unique record identifier |
| `deviceid` | INTEGER | NO | | Device ID (FK to devices table) |
| `phonenumber` | VARCHAR(50) | YES | | Phone number involved in call |
| `contactname` | VARCHAR(255) | YES | | Contact name from device contacts |
| `calltype` | INTEGER | NO | | Call type (1=Incoming, 2=Outgoing, 3=Missed, 4=Rejected, 5=Blocked) |
| `duration` | BIGINT | NO | 0 | Call duration in seconds |
| `calltimestamp` | BIGINT | NO | | Call occurrence time (epoch milliseconds) |
| `calldate` | VARCHAR(50) | YES | | Human-readable date string |
| `createtime` | BIGINT | YES | | Server record creation time (epoch ms) |
| `customerid` | INTEGER | NO | | Customer/organization ID (FK to customers) |

**Indexes:**
- PRIMARY KEY: `id`
- INDEX: `idx_calllog_device` on `(deviceid, customerid)`
- INDEX: `idx_calllog_timestamp` on `calltimestamp`
- INDEX: `idx_calllog_customer` on `customerid`

**Constraints:**
- FOREIGN KEY: `deviceid` references `devices(id)` ON DELETE CASCADE
- FOREIGN KEY: `customerid` references `customers(id)` ON DELETE CASCADE

#### 2. plugin_calllog_settings

Plugin configuration per customer:

| Column | Type | Nullable | Default | Purpose |
|--------|------|----------|---------|---------|
| `id` | SERIAL | NO | (PK) | Settings record identifier |
| `customerid` | INTEGER | NO | | Customer/organization ID (FK to customers) |
| `enabled` | BOOLEAN | NO | true | Enable/disable call log collection |
| `retentiondays` | INTEGER | NO | 90 | Number of days to retain logs (0 = forever) |

**Indexes:**
- PRIMARY KEY: `id`
- UNIQUE: `customerid` (one settings record per customer)

**Constraints:**
- FOREIGN KEY: `customerid` references `customers(id)` ON DELETE CASCADE

### Database Permissions Required

```sql
-- Grant permissions to application user (hmdm)
GRANT ALL PRIVILEGES ON TABLE plugin_calllog_data TO hmdm;
GRANT ALL PRIVILEGES ON TABLE plugin_calllog_settings TO hmdm;
GRANT ALL PRIVILEGES ON SEQUENCE plugin_calllog_data_id_seq TO hmdm;
GRANT ALL PRIVILEGES ON SEQUENCE plugin_calllog_settings_id_seq TO hmdm;
```

---

## REST API Endpoints

### Base URL

```
http://<host>:<port>/rest/plugins/calllog/
```

### Authentication

**Private Endpoints** (`/rest/plugins/calllog/private/*`):
- **Authentication**: Required (JWT token)
- **Authorization**: User must have access to the customer's devices
- **Permission Check**: Validates user belongs to same customer as device

**Public Endpoints** (`/rest/plugins/calllog/public/*`):
- **Authentication**: Not required
- **Device Validation**: Requires valid device number
- **Security**: IP whitelist and device validation

---

### Admin Endpoints (Private)

#### 1. GET /rest/plugins/calllog/private/device/{deviceId}

**Description:** Retrieve call logs for a specific device with pagination

**Authentication:** Required (JWT)

**Request:**
```http
GET /rest/plugins/calllog/private/device/1?page=0&pageSize=50
Authorization: Bearer <jwt_token>
```

**Query Parameters:**

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `page` | Integer | No | 0 | Page number (0-based) |
| `pageSize` | Integer | No | 50 | Number of records per page |

**Response:**
```json
{
  "status": "OK",
  "data": {
    "items": [
      {
        "id": 123,
        "deviceId": 1,
        "phoneNumber": "+1234567890",
        "contactName": "John Doe",
        "callType": 1,
        "duration": 180,
        "callTimestamp": 1707476400000,
        "callDate": "2024-02-09 10:00:00",
        "createTime": 1707476500000,
        "customerId": 1
      },
      {
        "id": 124,
        "deviceId": 1,
        "phoneNumber": "+0987654321",
        "contactName": "Jane Smith",
        "callType": 2,
        "duration": 300,
        "callTimestamp": 1707480000000,
        "callDate": "2024-02-09 11:00:00",
        "createTime": 1707480100000,
        "customerId": 1
      }
    ],
    "total": 150,
    "page": 0,
    "pageSize": 50
  }
}
```

**Status Codes:**
- `200 OK` - Call logs retrieved successfully
- `401 Unauthorized` - Invalid or missing JWT token
- `403 Forbidden` - User lacks permission to access this device
- `404 Not Found` - Device not found

**Error Response:**
```json
{
  "status": "ERROR",
  "message": "error.device.not.found"
}
```

---

#### 2. GET /rest/plugins/calllog/private/settings

**Description:** Get call log plugin settings for current customer

**Authentication:** Required (JWT)

**Request:**
```http
GET /rest/plugins/calllog/private/settings
Authorization: Bearer <jwt_token>
```

**Response:**
```json
{
  "status": "OK",
  "data": {
    "id": 1,
    "customerId": 1,
    "enabled": true,
    "retentionDays": 90
  }
}
```

**Status Codes:**
- `200 OK` - Settings retrieved successfully (or defaults if not configured)
- `401 Unauthorized` - Invalid or missing JWT token

---

#### 3. POST /rest/plugins/calllog/private/settings

**Description:** Save call log plugin settings (admin only)

**Authentication:** Required (JWT + Admin)

**Content-Type:** `application/json`

**Request:**
```http
POST /rest/plugins/calllog/private/settings
Authorization: Bearer <jwt_token>
Content-Type: application/json

{
  "enabled": true,
  "retentionDays": 90
}
```

**Request Body:**

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `enabled` | Boolean | Yes | Enable/disable call log collection |
| `retentionDays` | Integer | Yes | Days to retain logs (0 = forever) |

**Response:**
```json
{
  "status": "OK",
  "data": {
    "id": 1,
    "customerId": 1,
    "enabled": true,
    "retentionDays": 90
  }
}
```

**Status Codes:**
- `200 OK` - Settings saved successfully
- `401 Unauthorized` - Invalid or missing JWT token
- `403 Forbidden` - User is not an admin

---

#### 4. DELETE /rest/plugins/calllog/private/device/{deviceId}

**Description:** Delete all call logs for a specific device

**Authentication:** Required (JWT)

**Request:**
```http
DELETE /rest/plugins/calllog/private/device/1
Authorization: Bearer <jwt_token>
```

**Response:**
```json
{
  "status": "OK",
  "data": {
    "deletedCount": 150
  }
}
```

**Status Codes:**
- `200 OK` - Logs deleted successfully
- `401 Unauthorized` - Invalid or missing JWT token
- `403 Forbidden` - User lacks permission to access this device

---

### Public Endpoints (Android)

#### 5. POST /rest/plugins/calllog/public/submit/{deviceNumber}

**Description:** Submit call logs from Android device

**Authentication:** Not required

**Content-Type:** `application/json`

**Request:**
```http
POST /rest/plugins/calllog/public/submit/DEVICE12345
Content-Type: application/json

[
  {
    "phoneNumber": "+1234567890",
    "contactName": "John Doe",
    "callType": 1,
    "duration": 180,
    "callTimestamp": 1707476400000,
    "callDate": "2024-02-09 10:00:00"
  },
  {
    "phoneNumber": "+0987654321",
    "contactName": null,
    "callType": 3,
    "duration": 0,
    "callTimestamp": 1707480000000,
    "callDate": "2024-02-09 11:00:00"
  }
]
```

**Request Body:** Array of CallLogRecord objects (without id, deviceId, createTime, customerId)

**Response:**
```json
{
  "status": "OK"
}
```

**Status Codes:**
- `200 OK` - Logs submitted successfully
- `400 Bad Request` - Invalid request format
- `404 Not Found` - Device not found
- `500 Internal Server Error` - Server error during processing

**Error Response:**
```json
{
  "status": "ERROR",
  "message": "error.device.not.found"
}
```

**Notes:**
- Server automatically sets: `deviceId`, `customerId`, `createTime`
- Checks if plugin is enabled for customer before accepting logs
- Supports batch insert for efficiency
- Logs rejected silently if plugin disabled

---

#### 6. GET /rest/plugins/calllog/public/enabled/{deviceNumber}

**Description:** Check if call log collection is enabled for device

**Authentication:** Not required

**Request:**
```http
GET /rest/plugins/calllog/public/enabled/DEVICE12345
```

**Response:**
```json
{
  "status": "OK",
  "data": true
}
```

**Status Codes:**
- `200 OK` - Status retrieved successfully
- `404 Not Found` - Device not found
- `500 Internal Server Error` - Server error

**Notes:**
- Returns `true` if plugin enabled or no settings configured (default enabled)
- Returns `false` if plugin explicitly disabled
- Android app can query before collecting/submitting logs

---

## Android Integration

### Required Permissions

Add to `AndroidManifest.xml`:

```xml
<uses-permission android:name="android.permission.READ_CALL_LOG" />
```

### Runtime Permission Request

```java
if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG)
        != PackageManager.PERMISSION_GRANTED) {
    ActivityCompat.requestPermissions(activity,
            new String[]{Manifest.permission.READ_CALL_LOG},
            REQUEST_CALL_LOG_PERMISSION);
}
```

### Reading Call Logs

```java
public static List<CallLogRecord> getCallLogs(Context context, long lastSyncTime) {
    List<CallLogRecord> callLogs = new ArrayList<>();
    ContentResolver cr = context.getContentResolver();
    
    String[] projection = new String[] {
        CallLog.Calls.NUMBER,
        CallLog.Calls.CACHED_NAME,
        CallLog.Calls.TYPE,
        CallLog.Calls.DURATION,
        CallLog.Calls.DATE
    };
    
    String selection = CallLog.Calls.DATE + " > ?";
    String[] selectionArgs = new String[] { String.valueOf(lastSyncTime) };
    String sortOrder = CallLog.Calls.DATE + " DESC";
    
    Cursor cursor = cr.query(
        CallLog.Calls.CONTENT_URI,
        projection,
        selection,
        selectionArgs,
        sortOrder
    );
    
    if (cursor != null && cursor.moveToFirst()) {
        do {
            CallLogRecord record = new CallLogRecord();
            record.setPhoneNumber(cursor.getString(0));
            record.setContactName(cursor.getString(1));
            record.setCallType(mapCallType(cursor.getInt(2)));
            record.setDuration(cursor.getLong(3));
            record.setCallTimestamp(cursor.getLong(4));
            // Format date...
            callLogs.add(record);
        } while (cursor.moveToNext());
        cursor.close();
    }
    
    return callLogs;
}

private static int mapCallType(int androidType) {
    switch (androidType) {
        case CallLog.Calls.INCOMING_TYPE: return 1;
        case CallLog.Calls.OUTGOING_TYPE: return 2;
        case CallLog.Calls.MISSED_TYPE: return 3;
        case CallLog.Calls.REJECTED_TYPE: return 4;
        case CallLog.Calls.BLOCKED_TYPE: return 5;
        default: return 1;
    }
}
```

### Submitting to Server

```java
public static boolean submitCallLogs(String serverUrl, String deviceNumber, 
                                     List<CallLogRecord> callLogs) {
    HttpURLConnection connection = null;
    try {
        String endpoint = serverUrl + "/rest/plugins/calllog/public/submit/" + deviceNumber;
        URL url = new URL(endpoint);
        
        connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setDoOutput(true);
        
        Gson gson = new Gson();
        String jsonData = gson.toJson(callLogs);
        
        OutputStream os = connection.getOutputStream();
        os.write(jsonData.getBytes("UTF-8"));
        os.flush();
        os.close();
        
        int responseCode = connection.getResponseCode();
        return responseCode == 200;
        
    } catch (Exception e) {
        Log.e("CallLogSync", "Error submitting call logs", e);
        return false;
    } finally {
        if (connection != null) {
            connection.disconnect();
        }
    }
}
```

### Sync Schedule Recommendation

```java
// Recommended sync intervals:
// - WiFi: Every 1-6 hours
// - Mobile data: Every 6-24 hours
// - Manual: On user request

AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
Intent intent = new Intent(context, CallLogSyncService.class);
PendingIntent pendingIntent = PendingIntent.getService(context, 0, intent, 0);

// Sync every 6 hours
alarmManager.setRepeating(
    AlarmManager.RTC_WAKEUP,
    System.currentTimeMillis(),
    AlarmManager.INTERVAL_HOUR * 6,
    pendingIntent
);
```

---

## Frontend Integration

### Integration with Devices View

**File:** `server/src/main/webapp/app/components/main/view/devices.html`

Added "View Call Logs" button to device actions:

```html
<a ng-click="viewCallLogs(device)" localized>button.view.calllogs</a>
```

### Controller Function

**File:** `server/src/main/webapp/app/components/main/controller/devices.controller.js`

```javascript
$scope.viewCallLogs = function (device) {
    var modalInstance = $modal.open({
        templateUrl: 'app/components/plugins/calllog/views/modal.html',
        controller: function ($scope, $modalInstance, device, $injector, localization) {
            // Load plugin localizations
            localization.loadPluginResourceBundles("calllog");
            
            // Get plugin service
            var pluginCallLogService = $injector.get('pluginCallLogService');
            
            $scope.device = device;
            $scope.loadCallLogs = function() {
                pluginCallLogService.getCallLogs({
                    deviceId: device.id,
                    page: $scope.pagination.page,
                    pageSize: $scope.pagination.pageSize
                }, function (response) {
                    if (response.status === 'OK') {
                        $scope.callLogs = response.data.items || [];
                        $scope.pagination.total = response.data.total || 0;
                    }
                });
            };
            
            $scope.loadCallLogs();
        },
        size: 'lg'
    });
};
```

### Modal View

**File:** `plugins/calllog/src/main/webapp/views/modal.html`

Modern gradient-styled modal with:
- Device information panel
- Call logs table with color-coded call types
- Pagination controls
- Delete all button
- Responsive design

### Supported Languages

| Language | Code | File |
|----------|------|------|
| English | en_US | `i18n/en_US.json` |
| Russian | ru_RU | `i18n/ru_RU.json` |
| Spanish | es_ES | `i18n/es_ES.json` |
| French | fr_FR | `i18n/fr_FR.json` |
| German | de_DE | `i18n/de_DE.json` |
| Chinese (Simplified) | zh_CN | `i18n/zh_CN.json` |
| Chinese (Traditional) | zh_TW | `i18n/zh_TW.json` |
| Italian | it_IT | `i18n/it_IT.json` |
| Japanese | ja_JP | `i18n/ja_JP.json` |
| Portuguese | pt_PT | `i18n/pt_PT.json` |
| Turkish | tr_TR | `i18n/tr_TR.json` |
| Vietnamese | vi_VN | `i18n/vi_VN.json` |

---

## Configuration

### Database Configuration

**File:** `server/build.properties`

```properties
# PostgreSQL connection settings
jdbc.url=jdbc:postgresql://localhost:5432/hmdm
jdbc.driver=org.postgresql.Driver
jdbc.username=hmdm
jdbc.password=hmdm
```

### Plugin Settings (Per Customer)

Configurable via Admin UI or API:

| Setting | Type | Default | Description |
|---------|------|---------|-------------|
| **Enabled** | Boolean | `true` | Enable/disable call log collection |
| **Retention Days** | Integer | `90` | Days to keep logs (0 = forever) |

### Granting Database Permissions

If encountering permission errors, run:

```bash
sudo -u postgres psql -d hmdm -c "GRANT ALL PRIVILEGES ON TABLE plugin_calllog_data TO hmdm;"
sudo -u postgres psql -d hmdm -c "GRANT ALL PRIVILEGES ON TABLE plugin_calllog_settings TO hmdm;"
sudo -u postgres psql -d hmdm -c "GRANT ALL PRIVILEGES ON SEQUENCE plugin_calllog_data_id_seq TO hmdm;"
sudo -u postgres psql -d hmdm -c "GRANT ALL PRIVILEGES ON SEQUENCE plugin_calllog_settings_id_seq TO hmdm;"
```

---

## Security & Privacy

### Multi-Tenant Isolation

**Customer-Level Separation:**
- All queries filtered by `customerId`
- Users can only access devices/data from their organization
- No cross-customer data leakage

**Example Query:**
```sql
SELECT * FROM plugin_calllog_data 
WHERE deviceid = ? AND customerid = ?
```

### Authentication & Authorization

**Private Endpoints:**
1. JWT token validation
2. User authentication check
3. Device ownership verification (device.customerId == user.customerId)
4. Permission-based access control

**Public Endpoints:**
1. Device number validation
2. Device existence check
3. Enabled status verification

### Data Retention

**Automatic Cleanup:**
- Configurable retention period per customer
- Background task deletes expired records
- Respects `retentionDays` setting (0 = keep forever)

**Manual Deletion:**
- Admins can delete all logs per device
- Cascade delete when device is removed
- Cascade delete when customer is removed

### Privacy Considerations

**What is Stored:**
- Phone numbers (plain text)
- Contact names (from device)
- Call metadata (type, duration, timestamp)
- Device and customer associations

**What is NOT Stored:**
- Call audio recordings
- Call content or transcripts
- SMS/message content
- Location data (unless separate plugin)

**Recommendations:**
1. Enable retention policies (don't keep forever)
2. Inform users about call log monitoring
3. Comply with local privacy regulations (GDPR, CCPA, etc.)
4. Implement access logging for audit trails
5. Regular security audits

### Compliance Notes

**Legal Requirements:**
- Obtain user consent before collecting call logs
- Provide privacy policy disclosure
- Allow users to request data deletion
- Comply with telecommunications regulations
- Maintain data access logs

**GDPR Considerations:**
- Call logs are personal data
- Must have legal basis for processing
- Support data subject rights (access, deletion, portability)
- Implement appropriate security measures

---

## Testing

### API Testing

**Test submission endpoint:**
```bash
curl -X POST "http://localhost:8080/rest/plugins/calllog/public/submit/YOUR_DEVICE_NUMBER" \
  -H "Content-Type: application/json" \
  -d '[
    {
      "phoneNumber": "+1234567890",
      "contactName": "John Doe",
      "callType": 1,
      "duration": 120,
      "callTimestamp": 1707476400000,
      "callDate": "2024-02-09 10:00:00"
    }
  ]'
```

**Test enabled status:**
```bash
curl "http://localhost:8080/rest/plugins/calllog/public/enabled/YOUR_DEVICE_NUMBER"
```

**Test retrieval (requires authentication):**
```bash
# Login first
TOKEN=$(curl -X POST "http://localhost:8080/rest/public/login" \
  -H "Content-Type: application/json" \
  -d '{"login":"admin","password":"admin"}' | jq -r '.data.token')

# Get call logs
curl "http://localhost:8080/rest/plugins/calllog/private/device/1?page=0&pageSize=50" \
  -H "Authorization: Bearer $TOKEN"
```

### Database Verification

```sql
-- Check if tables exist
\dt plugin_calllog*

-- Count records
SELECT COUNT(*) FROM plugin_calllog_data;

-- View recent logs
SELECT 
    d.number AS device,
    c.phonenumber,
    c.contactname,
    c.calltype,
    c.duration,
    to_timestamp(c.calltimestamp / 1000) AS call_time
FROM plugin_calllog_data c
JOIN devices d ON c.deviceid = d.id
ORDER BY c.calltimestamp DESC
LIMIT 20;

-- Check settings
SELECT * FROM plugin_calllog_settings;
```

---

## Build & Deployment

### Building the Plugin

```bash
cd hmdm-server
mvn clean install -DskipTests
```

### Deploying to Tomcat

```bash
# Shutdown Tomcat
sudo /opt/tomcat9/bin/shutdown.sh

# Remove old deployment
sudo rm -rf /opt/tomcat9/webapps/ROOT

# Deploy new WAR
sudo cp server/target/launcher.war /opt/tomcat9/webapps/ROOT.war

# Start Tomcat
sudo /opt/tomcat9/bin/startup.sh
```

### Verifying Plugin Installation

1. Login to admin panel: http://localhost:8080
2. Navigate to Plugins section
3. Verify "Call Log" plugin is listed
4. Check plugin status and configuration

---

## Troubleshooting

### Common Issues

#### 1. 500 Internal Server Error

**Symptom:** API returns 500 status code

**Cause:** Database permission denied

**Solution:**
```bash
sudo -u postgres psql -d hmdm -c "GRANT ALL PRIVILEGES ON TABLE plugin_calllog_data TO hmdm;"
sudo -u postgres psql -d hmdm -c "GRANT ALL PRIVILEGES ON TABLE plugin_calllog_settings TO hmdm;"
sudo -u postgres psql -d hmdm -c "GRANT ALL PRIVILEGES ON SEQUENCE plugin_calllog_data_id_seq TO hmdm;"
sudo -u postgres psql -d hmdm -c "GRANT ALL PRIVILEGES ON SEQUENCE plugin_calllog_settings_id_seq TO hmdm;"
```

#### 2. Empty Device Information

**Symptom:** Device number/IMEI/phone shows as empty in modal

**Cause:** Incorrect property names in template

**Solution:** Device object uses:
- `device.number` (not `device.deviceNumber`)
- `device.displayedIMEI` (not `device.imei`)
- `device.displayedPhone` (not `device.phone`)

#### 3. Translation Keys Not Working

**Symptom:** Buttons show "button.delete.all" instead of translated text

**Cause:** Incorrect localization directive usage

**Solution:** Use text content as key:
```html
<button localized>button.delete.all</button>
```

#### 4. Android Logs Not Appearing

**Checklist:**
- [ ] Plugin enabled in settings
- [ ] Device number correct in submission
- [ ] Device exists in database
- [ ] Android app has READ_CALL_LOG permission
- [ ] Network connectivity from device to server

---

## Future Enhancements

### Planned Features

1. **Advanced Filtering**
   - Filter by phone number
   - Filter by call type
   - Filter by date range
   - Search by contact name

2. **Analytics Dashboard**
   - Call volume charts
   - Peak usage times
   - Top contacts
   - Call duration statistics

3. **Export Functionality**
   - CSV export
   - PDF reports
   - Excel format
   - Scheduled exports

4. **Real-time Updates**
   - WebSocket notifications
   - Live call monitoring
   - Instant alerts

5. **Enhanced Privacy**
   - Phone number masking
   - Encrypted storage
   - Audit logging
   - Compliance reports

---

## References

### Documentation Files

- [ANDROID_INTEGRATION_GUIDE.md](plugins/calllog/ANDROID_INTEGRATION_GUIDE.md) - Complete Android integration
- [API_TESTING_GUIDE.md](plugins/calllog/API_TESTING_GUIDE.md) - API testing with examples
- [IMPLEMENTATION_SUMMARY.md](plugins/calllog/IMPLEMENTATION_SUMMARY.md) - Implementation overview
- [STATUS_AND_NEXT_STEPS.md](plugins/calllog/STATUS_AND_NEXT_STEPS.md) - Project status

### External Resources

- [MyBatis Documentation](https://mybatis.org/mybatis-3/)
- [Google Guice](https://github.com/google/guice)
- [Jersey JAX-RS](https://eclipse-ee4j.github.io/jersey/)
- [Android CallLog API](https://developer.android.com/reference/android/provider/CallLog)

---

## Support & Contact

For issues, questions, or contributions related to the Call Log Plugin:

1. Check existing documentation
2. Review troubleshooting section
3. Verify database permissions
4. Check Tomcat logs: `/opt/tomcat9/logs/catalina.out`

---

**Document Version:** 1.0.0  
**Last Updated:** February 10, 2026  
**Plugin Version:** 1.0.0
