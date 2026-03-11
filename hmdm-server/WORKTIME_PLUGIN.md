# Worktime Plugin - Complete Technical Documentation

## Table of Contents

1. [Overview](#overview)
2. [Purpose & Features](#purpose--features)
3. [Technology Stack](#technology-stack)
4. [Plugin Architecture](#plugin-architecture)
5. [Directory Structure](#directory-structure)
6. [Core Files & Classes](#core-files--classes)
7. [Database Schema](#database-schema)
8. [REST API Endpoints](#rest-api-endpoints)
9. [Configuration](#configuration)
10. [Integration Points](#integration-points)
11. [Development Guide](#development-guide)

---

## Overview

The **Worktime Plugin** is a Mobile Device Management (MDM) feature module for Headwind MDM that provides work time policy management capabilities. It allows administrators to define work-time restrictions and control app access on managed Android devices based on time-of-day and day-of-week rules.

### Plugin Identification

| Property           | Value                                  |
| ------------------ | -------------------------------------- |
| **Plugin ID**      | `worktime`                             |
| **Plugin Name**    | Work Time Policy Plugin for MDM Server |
| **Version**        | 0.1.0                                  |
| **Package**        | `com.hmdm.plugins.worktime`            |
| **Root Path**      | `hmdm-server/plugins/worktime/`        |

---

## Purpose & Features

### What It Does

The Worktime Plugin manages work-time policies for Android devices managed by Headwind MDM. It:

- **Defines work hours**: Allows setting start and end times for work periods
- **Restricts by day**: Specifies which days of the week the policy applies
- **Controls app access**: Defines which apps are allowed during work hours vs. outside work hours
- **Global policies**: Supports organization-wide work time policies
- **Per-customer configuration**: Each customer can have their own policies
- **Override capabilities**: Allows temporary overrides at global and per-user levels
- **Timezone support**: Handles different timezones for policy enforcement

### Use Cases

1. **Corporate Device Management**: Restrict personal apps during work hours
2. **BYOD (Bring Your Own Device)**: Control app access based on work time
3. **Compliance**: Enforce organizational policies for device usage
4. **Productivity**: Limit app usage outside work hours
5. **Security**: Control sensitive app access based on work schedule

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
| **Persistence**      | MyBatis (SQL mapping framework)                                                         |
| **Migrations**       | Liquibase (database versioning and schema management)                                   |
| **JSON Processing** | Jackson (Jackson Databind & Jackson Annotations)                                       |
| **API Documentation** | Swagger/Springfox annotations for OpenAPI documentation                                |
| **Scheduling**       | Background task runner for cleanup jobs                                                 |
| **Sync Hooks**       | Device sync response hooks for policy delivery                                          |

### Frontend Technologies

| Component      | Technology         |
| -------------- | ------------------ |
| **Framework**  | AngularJS 1.x      |
| **Templates**  | HTML5              |
| **Styling**    | Bootstrap / CSS    |
| **i18n**       | JSON-based (JSON)  |
| **Module Type** | AngularJS Module   |

### Core Dependencies

```xml
<!-- Platform base classes and utilities -->
<dependency>
    <groupId>com.hmdm.plugin</groupId>
    <artifactId>platform</artifactId>
    <version>0.1.0</version>
</dependency>

<!-- Main server components -->
<dependency>
    <groupId>com.hmdm</groupId>
    <artifactId>server</artifactId>
    <version>0.1.0</version>
</dependency>
```

---

## Plugin Architecture

The Worktime Plugin follows the standard two-module plugin architecture:

```
┌─────────────────────────────────────────────────────┐
│           CORE MODULE (Database-Agnostic)           │
├─────────────────────────────────────────────────────┤
│ • Model classes (domain objects)                    │
│ • DAO interfaces (data access contracts)            │
│ • REST resources (API endpoints)                    │
│ • Guice modules (dependency configuration)          │
│ • Core Liquibase changelog (plugin registration)    │
└─────────────────────────────────────────────────────┘
                          ↓
┌─────────────────────────────────────────────────────┐
│      POSTGRES MODULE (Database-Specific)            │
├─────────────────────────────────────────────────────┤
│ • DAO implementations (PostgreSQL-specific)         │
│ • MyBatis mappers (SQL mapping)                     │
│ • Guice modules (persistence configuration)         │
│ • Postgres Liquibase changelog (table creation)     │
└─────────────────────────────────────────────────────┘
                          ↓
┌─────────────────────────────────────────────────────┐
│       FRONTEND MODULE (AngularJS)                   │
├─────────────────────────────────────────────────────┤
│ • AngularJS module definition                       │
│ • HTML views/templates                              │
│ • i18n translation files                            │
│ • Controllers, directives, services                 │
└─────────────────────────────────────────────────────┘
```

---

## Directory Structure

### Complete Worktime Plugin Tree

```
hmdm-server/
├── plugins/
│   ├── pom.xml                                      # Parent POM (includes worktime module)
│   └── worktime/                                    # WORKTIME PLUGIN ROOT
│       ├── pom.xml                                  # Plugin parent POM
│       │
│       ├── core/                                    # CORE MODULE (Database-Agnostic)
│       │   ├── pom.xml                              # Core module POM
│       │   └── src/
│       │       └── main/
│       │           ├── java/
│       │           │   └── com/hmdm/plugins/worktime/
│       │           │       ├── WorkTimePluginConfigurationImpl.java     # [REQUIRED] Plugin entry point
│       │           │       │
│       │           │       ├── model/                                   # Domain Models
│       │           │       │   ├── WorkTimePolicy.java                # Work time policy definition
│       │           │       │   ├── WorkTimePolicyDeviceGroup.java     # Policy device group mapping
│       │           │       │   ├── GlobalWorkTimePolicy.java          # Global policy settings
│       │           │       │   └── WorkTimeUserOverride.java          # Per-user override model
│       │           │       │
│       │           │       ├── service/                                 # Business Logic Layer
│       │           │       │   ├── WorkTimeService.java               # Core business logic
│       │           │       │   └── EffectiveWorkTimePolicy.java       # Resolved policy model
│       │           │       │
│       │           │       ├── persistence/                             # Data Access Layer
│       │           │       │   ├── WorkTimeDAO.java                   # DAO Interface (contract)
│       │           │       │   └── WorkTimePersistenceConfiguration.java # Persistence config interface
│       │           │       │
│       │           │       ├── sync/                                    # Device Sync Integration
│       │           │       │   └── WorkTimeSyncResponseHook.java      # Sync response hook
│       │           │       │
│       │           │       ├── task/                                    # Background Tasks
│       │           │       │   └── ExpiredExceptionCleanupTask.java   # Cleanup expired exceptions
│       │           │       │
│       │           │       ├── rest/
│       │           │       │   └── resource/                            # REST Endpoints
│       │           │       │       ├── WorkTimeResource.java           # Admin REST endpoints
│       │           │       │       └── WorkTimePublicResource.java     # Public device endpoints
│       │           │       │
│       │           │       └── guice/
│       │           │           └── module/                              # Guice Dependency Injection
│       │           │               ├── WorkTimeLiquibaseModule.java    # Liquibase DI
│       │           │               └── WorkTimeRestModule.java         # REST API DI
│       │           │
│       │           └── resources/
│       │               └── liquibase/
│       │                   └── worktime.changelog.xml  # Core changelog (plugin registration)
│       │
│       ├── postgres/                                # POSTGRES MODULE (Database-Specific)
│       │   ├── pom.xml                              # Postgres module POM
│       │   └── src/
│       │       ├── main/
│       │       │   ├── java/
│       │       │   │   └── com/hmdm/plugins/worktime/
│       │       │   │       └── persistence/postgres/
│       │       │   │           ├── WorkTimePostgresPersistenceConfiguration.java
│       │       │   │           │
│       │       │   │           ├── dao/                                 # DAO Implementations
│       │       │   │           │   ├── PostgresWorkTimeDAO.java        # PostgreSQL DAO impl
│       │       │   │           │   │
│       │       │   │           │   ├── domain/                          # DB-specific domain mapping
│       │       │   │           │   │   (optional - uses model classes)
│       │       │   │           │   │
│       │       │   │           │   └── mapper/                          # MyBatis SQL Mappers
│       │       │   │           │       ├── PostgresWorkTimeMapper.java  # Mapper interface
│       │       │   │           │       └── PostgresWorkTimeMapper.xml   # SQL XML mappings
│       │       │   │           │
│       │       │   │           └── guice/module/                        # Postgres DI
│       │       │   │               ├── WorkTimePostgresLiquibaseModule.java  # Liquibase config
│       │       │   │               └── WorkTimePostgresPersistenceModule.java # MyBatis & DAO binding
│       │       │   │
│       │       │   └── resources/
│       │       │       └── liquibase/
│       │       │           └── worktime.postgres.changelog.xml  # Table creation changelog
│       │       │
│       │       └── test/  (optional)
│       │           └── java/  (unit tests)
│       │
│       ├── src/                                     # FRONTEND MODULE (AngularJS)
│       │   └── main/
│       │       └── webapp/
│       │           ├── worktime.module.js           # AngularJS main module
│       │           │
│       │           ├── views/                       # HTML Templates
│       │           │   ├── main.html                # Main view
│       │           │   ├── settings.html            # Settings/policy view
│       │           │   └── (other views...)
│       │           │
│       │           └── i18n/                        # Internationalization
│       │               ├── en_US.json               # English translations
│       │               ├── ru_RU.json               # Russian translations
│       │               └── (other languages...)
│       │
│       └── target/  (ignored - Maven build output)
│           ├── classes/
│           ├── generated-sources/
│           └── ...
│
├── server/                                          # Main Server Module
│   ├── pom.xml                                      # <- MODIFIED: Includes worktime dependencies
│   ├── build.properties.example                     # <- MODIFIED: Plugin config class property
│   ├── build.properties                             # <- MODIFIED: Local plugin config
│   └── conf/
│       └── context.xml                              # <- MODIFIED: Plugin context parameter
│
└── install/
    └── context_template.xml                         # <- MODIFIED: Production deployment template
```

### Key Directories Summary

| Directory | Purpose | Location |
|-----------|---------|----------|
| **Core Java** | Business logic, models, DAOs | `plugins/worktime/core/src/main/java/` |
| **Postgres Java** | PostgreSQL implementations | `plugins/worktime/postgres/src/main/java/` |
| **Core Resources** | Liquibase migrations | `plugins/worktime/core/src/main/resources/liquibase/` |
| **Postgres Resources** | Core DB schema setup | `plugins/worktime/postgres/src/main/resources/liquibase/` |
| **Frontend** | AngularJS module & views | `plugins/worktime/src/main/webapp/` |
| **i18n** | Translation files | `plugins/worktime/src/main/webapp/i18n/` |
| **Build Output** | Compiled classes, JARs | `plugins/worktime/core/target/` & `postgres/target/` |

---

## Core Files & Classes

### 1. Plugin Entry Point

**File:** [plugins/worktime/core/src/main/java/com/hmdm/plugins/worktime/WorkTimePluginConfigurationImpl.java](plugins/worktime/core/src/main/java/com/hmdm/plugins/worktime/WorkTimePluginConfigurationImpl.java)

```java
public class WorkTimePluginConfigurationImpl implements PluginConfiguration {
    
    public static final String PLUGIN_ID = "worktime";
    
    @Override
    public String getPluginId() {
        return PLUGIN_ID;  // Returns "worktime"
    }
    
    @Override
    public String getRootPackage() {
        return "com.hmdm.plugins.worktime";
    }
    
    @Override
    public List<Module> getPluginModules(ServletContext context) {
        // Loads Liquibase, Persistence, and REST modules
    }
}
```

**Responsibilities:**
- Serves as the main plugin entry point
- Initializes Guice modules for dependency injection
- Loads persistence configuration
- Registers REST endpoints

---

### 2. Model Classes

#### 2.1 WorkTimePolicy

**File:** [plugins/worktime/core/src/main/java/com/hmdm/plugins/worktime/model/WorkTimePolicy.java](plugins/worktime/core/src/main/java/com/hmdm/plugins/worktime/model/WorkTimePolicy.java)

Represents a work time policy definition:

```
Properties:
├── id: Integer                          # Unique policy identifier
├── name: String                         # Policy name
├── description: String                  # Policy description
├── startTime: String                    # Start time (HH:mm format)
├── endTime: String                      # End time (HH:mm format)
├── daysOfWeek: Integer                  # Bitmask of days (0-127)
├── allowedAppsDuringWork: String        # Apps allowed during work
├── allowedAppsOutsideWork: String       # Apps allowed outside work
├── priority: Integer                    # Policy priority
├── timezone: String                     # Timezone (e.g., Europe/Bucharest)
├── customerId: Integer                  # Customer/organization ID
├── createdAt: Date                      # Creation timestamp
├── updatedAt: Date                      # Last update timestamp
├── createdBy: Integer                   # User ID who created policy
└── deviceGroups: List<WorkTimePolicyDeviceGroup>  # Associated device groups
```

#### 2.2 GlobalWorkTimePolicy

**File:** [plugins/worktime/core/src/main/java/com/hmdm/plugins/worktime/model/GlobalWorkTimePolicy.java](plugins/worktime/core/src/main/java/com/hmdm/plugins/worktime/model/GlobalWorkTimePolicy.java)

Organization-wide global policy (simpler structure):

```
Properties:
├── id: Integer                          # Policy ID
├── startTime: String                    # Start time (HH:mm)
├── endTime: String                      # End time (HH:mm)
├── daysOfWeek: Integer                  # Days bitmask
├── allowedAppsDuringWork: String        # Allowed apps during work
├── allowedAppsOutsideWork: String       # Allowed apps outside work
├── enabled: Boolean                     # Enable/disable policy
└── customerId: Integer                  # Customer ID
```

#### 2.3 WorkTimePolicyDeviceGroup

**File:** [plugins/worktime/core/src/main/java/com/hmdm/plugins/worktime/model/WorkTimePolicyDeviceGroup.java](plugins/worktime/core/src/main/java/com/hmdm/plugins/worktime/model/WorkTimePolicyDeviceGroup.java)

Maps policies to device groups:

```
Properties:
├── id: Integer                          # Mapping ID
├── workTimePolicyId: Integer            # Policy ID
├── deviceGroupId: Integer               # Device group ID
└── customerId: Integer                  # Customer ID
```

#### 2.4 WorkTimeUserOverride

**File:** [plugins/worktime/core/src/main/java/com/hmdm/plugins/worktime/model/WorkTimeUserOverride.java](plugins/worktime/core/src/main/java/com/hmdm/plugins/worktime/model/WorkTimeUserOverride.java)

Per-user policy override (allows custom work time settings and temporary exceptions):

```
Properties:
├── id: Integer                          # Override ID
├── customerId: Integer                  # Customer ID
├── userId: Integer                      # User ID
├── userName: String                     # User name (for display)
├── enabled: Boolean                     # Override enabled flag
├── startTime: String                    # Custom start time (HH:mm)
├── endTime: String                      # Custom end time (HH:mm)
├── daysOfWeek: String                   # Days bitmask or CSV
├── allowedAppsDuringWork: String        # Custom apps during work
├── allowedAppsOutsideWork: String       # Custom apps outside work
├── startDateTime: Timestamp             # Exception start (temporary disable)
├── endDateTime: Timestamp               # Exception end (temporary disable)
├── priority: Integer                    # Override priority
├── createdAt: Timestamp                 # Creation timestamp
└── updatedAt: Timestamp                 # Last update timestamp
```

---

### 3. Service Layer

#### 3.1 WorkTimeService

**File:** [plugins/worktime/core/src/main/java/com/hmdm/plugins/worktime/service/WorkTimeService.java](plugins/worktime/core/src/main/java/com/hmdm/plugins/worktime/service/WorkTimeService.java)

Core business logic for resolving effective policies:

**Key Methods:**
```java
// Resolve effective policy for a user considering global policy and overrides
EffectiveWorkTimePolicy resolveEffectivePolicy(int customerId, int userId, LocalDateTime now)

// Check if a specific app is allowed for a user at the current time
boolean isAppAllowed(int customerId, int userId, String packageName, LocalDateTime now)

// Check if current time is within work hours
boolean isWorkTime(String startTime, String endTime, int daysOfWeek, LocalDateTime now)
```

**Responsibilities:**
- Merges global policy with user-specific overrides
- Handles temporary exceptions (startDateTime/endDateTime)
- Resolves effective app permissions
- Cleans up expired exceptions
- Applies priority-based policy resolution

#### 3.2 EffectiveWorkTimePolicy

**File:** [plugins/worktime/core/src/main/java/com/hmdm/plugins/worktime/service/EffectiveWorkTimePolicy.java](plugins/worktime/core/src/main/java/com/hmdm/plugins/worktime/service/EffectiveWorkTimePolicy.java)

Resolved policy after merging global and user-specific overrides:

```
Properties:
├── enabled: boolean                     # Enforcement enabled
├── startTime: String                    # Effective start time
├── endTime: String                      # Effective end time
├── daysOfWeek: int                      # Effective days bitmask
├── allowedAppsDuringWork: Set<String>   # Resolved apps during work
└── allowedAppsOutsideWork: Set<String>  # Resolved apps outside work
```

---

### 4. Data Access Layer

#### 4.1 WorkTimeDAO Interface

**File:** [plugins/worktime/core/src/main/java/com/hmdm/plugins/worktime/persistence/WorkTimeDAO.java](plugins/worktime/core/src/main/java/com/hmdm/plugins/worktime/persistence/WorkTimeDAO.java)

Defines data access contracts (database-agnostic):

```java
public interface WorkTimeDAO {
    // Global policy
    WorkTimePolicy getGlobalPolicy(int customerId);
    void saveGlobalPolicy(WorkTimePolicy policy);
    
    // User overrides
    List<WorkTimeUserOverride> getUserOverrides(int customerId);
    WorkTimeUserOverride getUserOverride(int customerId, int userId);
    void saveUserOverride(WorkTimeUserOverride override);
    void deleteUserOverride(int customerId, int userId);
    List<WorkTimeUserOverride> getAllUserOverrides(); // For cleanup task
}
```

#### 4.2 PostgresWorkTimeDAO

**File:** [plugins/worktime/postgres/src/main/java/com/hmdm/plugins/worktime/persistence/postgres/dao/PostgresWorkTimeDAO.java](plugins/worktime/postgres/src/main/java/com/hmdm/plugins/worktime/persistence/postgres/dao/PostgresWorkTimeDAO.java)

PostgreSQL-specific implementation of WorkTimeDAO using MyBatis.

#### 4.3 MyBatis Mapper

**Files:**
- Mapper Interface: [plugins/worktime/postgres/src/main/java/com/hmdm/plugins/worktime/persistence/postgres/dao/mapper/PostgresWorkTimeMapper.java](plugins/worktime/postgres/src/main/java/com/hmdm/plugins/worktime/persistence/postgres/dao/mapper/PostgresWorkTimeMapper.java)
- SQL Mappings: [plugins/worktime/postgres/src/main/java/com/hmdm/plugins/worktime/persistence/postgres/dao/mapper/PostgresWorkTimeMapper.xml](plugins/worktime/postgres/src/main/java/com/hmdm/plugins/worktime/persistence/postgres/dao/mapper/PostgresWorkTimeMapper.xml)

---

### 5. Device Sync Integration

#### 5.1 WorkTimeSyncResponseHook

**File:** [plugins/worktime/core/src/main/java/com/hmdm/plugins/worktime/sync/WorkTimeSyncResponseHook.java](plugins/worktime/core/src/main/java/com/hmdm/plugins/worktime/sync/WorkTimeSyncResponseHook.java)

Integrates with device sync process to deliver policies to devices:

```java
@Singleton
public class WorkTimeSyncResponseHook implements SyncResponseHook {
    @Override
    public SyncResponseInt handle(int deviceId, SyncResponseInt original) {
        // Resolves device to customer/user
        // Adds effective worktime policy to sync response
        // Devices receive policy updates automatically
    }
}
```

**Purpose:**
- Automatically includes worktime policy in device sync responses
- Ensures devices have up-to-date policy information
- No manual policy push required

---

### 6. Background Tasks

#### 6.1 ExpiredExceptionCleanupTask

**File:** [plugins/worktime/core/src/main/java/com/hmdm/plugins/worktime/task/ExpiredExceptionCleanupTask.java](plugins/worktime/core/src/main/java/com/hmdm/plugins/worktime/task/ExpiredExceptionCleanupTask.java)

Periodic cleanup task for expired temporary exceptions:

```java
@Singleton
public class ExpiredExceptionCleanupTask {
    public void cleanupExpiredExceptions() {
        // Scans all user overrides
        // Deletes overrides with expired exception windows
        // Prevents database bloat
    }
}
```

**Scheduling:**
- Runs periodically via BackgroundTaskRunnerService
- Checks all customers/users for expired exceptions
- Removes expired overrides automatically

---

### 7. REST Resources

#### 7.1 WorkTimeResource (Admin Endpoints)

**File:** [plugins/worktime/core/src/main/java/com/hmdm/plugins/worktime/rest/resource/WorkTimeResource.java](plugins/worktime/core/src/main/java/com/hmdm/plugins/worktime/rest/resource/WorkTimeResource.java)

Admin-only REST endpoints (see [REST API Endpoints](#rest-api-endpoints) section for details).

#### 7.2 WorkTimePublicResource (Device Endpoints)

**File:** [plugins/worktime/core/src/main/java/com/hmdm/plugins/worktime/rest/resource/WorkTimePublicResource.java](plugins/worktime/core/src/main/java/com/hmdm/plugins/worktime/rest/resource/WorkTimePublicResource.java)

Public endpoints for device queries (authentication optional):

**Key Endpoints:**
- `GET /plugins/worktime/public/policy/effective/{userId}` - Get resolved policy
- `GET /plugins/worktime/public/allowed?userId={id}&pkg={package}` - Check app permission

---

### 8. Guice Modules

#### 8.1 WorkTimeRestModule

**File:** [plugins/worktime/core/src/main/java/com/hmdm/plugins/worktime/guice/module/WorkTimeRestModule.java](plugins/worktime/core/src/main/java/com/hmdm/plugins/worktime/guice/module/WorkTimeRestModule.java)

Configures REST API security filters:

```java
public class WorkTimeRestModule extends ServletModule {
    // Protected resources requiring authentication
    private static final List<String> protectedResources = 
        Arrays.asList("/rest/plugins/worktime/private/*");
    
    // Applied filters:
    // - JWTFilter (JWT token validation)
    // - AuthFilter (authentication)
    // - PluginAccessFilter (plugin access control)
    // - PrivateIPFilter (IP whitelist validation)
}
```

#### 8.2 WorkTimeLiquibaseModule

**File:** [plugins/worktime/core/src/main/java/com/hmdm/plugins/worktime/guice/module/WorkTimeLiquibaseModule.java](plugins/worktime/core/src/main/java/com/hmdm/plugins/worktime/guice/module/WorkTimeLiquibaseModule.java)

Configures Liquibase for database migrations:

```java
public class WorkTimeLiquibaseModule extends AbstractLiquibaseModule {
    @Override
    protected String getChangeLogResourcePath() {
        return "jar:.../liquibase/worktime.changelog.xml";
    }
}
```

#### 8.3 Postgres-Specific Modules

Two modules in postgres package:

| Module | File | Purpose |
|--------|------|---------|
| **WorkTimePostgresLiquibaseModule** | `postgres/.../guice/module/WorkTimePostgresLiquibaseModule.java` | Loads postgres-specific Liquibase changelog |
| **WorkTimePostgresPersistenceModule** | `postgres/.../guice/module/WorkTimePostgresPersistenceModule.java` | Configures MyBatis mappers & binds DAO implementations |

---

## Database Schema

### Tables Created by Liquibase

All tables are created by [plugins/worktime/postgres/src/main/resources/liquibase/worktime.postgres.changelog.xml](plugins/worktime/postgres/src/main/resources/liquibase/worktime.postgres.changelog.xml)

#### 1. worktime_global_policy

Organization-wide work time policy:

| Column | Type | Nullable | Default | Purpose |
|--------|------|----------|---------|---------|
| `id` | SERIAL | NO | (PK) | Policy identifier |
| `start_time` | VARCHAR(5) | NO | | Start time (HH:mm) |
| `end_time` | VARCHAR(5) | NO | | End time (HH:mm) |
| `days_of_week` | INT | NO | | Bitmask (0-127): 1=Mon, 2=Tue, 4=Wed, 8=Thu, 16=Fri, 32=Sat, 64=Sun |
| `allowed_apps_during_work` | TEXT | YES | | Comma-separated app packages or `*` for all |
| `allowed_apps_outside_work` | TEXT | YES | | Comma-separated app packages or `*` for all |
| `enabled` | BOOLEAN | NO | true | Enable/disable policy |
| `customer_id` | INT | NO | | Organization/customer ID (FK to customers table) |

#### 2. worktime_global_override

Temporary organizational overrides:

| Column | Type | Nullable | Default | Purpose |
|--------|------|----------|---------|---------|
| `id` | SERIAL | NO | (PK) | Override identifier |
| `start_datetime` | TIMESTAMP | YES | | Override start date/time |
| `end_datetime` | TIMESTAMP | YES | | Override end date/time |
| `enabled` | BOOLEAN | NO | true | Enable/disable override |
| `customer_id` | INT | NO | | Organization ID |

#### 3. worktime_user_override

Per-user exceptions and custom policy settings:

| Column | Type | Nullable | Default | Purpose |
|--------|------|----------|---------|---------|
| `id` | SERIAL | NO | (PK) | Override identifier |
| `user_id` | INT | NO | | User ID (FK to users table) |
| `start_time` | VARCHAR(5) | YES | | Custom start time (HH:mm) overriding global |
| `end_time` | VARCHAR(5) | YES | | Custom end time (HH:mm) overriding global |
| `days_of_week` | VARCHAR(50) | YES | | Custom days bitmask or CSV overriding global |
| `allowed_apps_during_work` | TEXT | YES | | Custom apps allowed during work |
| `allowed_apps_outside_work` | TEXT | YES | | Custom apps allowed outside work |
| `start_datetime` | TIMESTAMP | YES | | Temporary exception start (policy disabled from this time) |
| `end_datetime` | TIMESTAMP | YES | | Temporary exception end (policy re-enabled after this time) |
| `enabled` | BOOLEAN | NO | true | Enable/disable override (false = temporary exception active) |
| `customer_id` | INT | NO | | Organization ID (FK to customers table) |
| `priority` | INT | YES | 0 | Override priority (higher = more precedence) |
| `created_at` | TIMESTAMP | YES | CURRENT_TIMESTAMP | Creation timestamp |
| `updated_at` | TIMESTAMP | YES | CURRENT_TIMESTAMP | Last update timestamp |

**Constraints:**
- PRIMARY KEY: `id`
- UNIQUE: `(customer_id, user_id)` - One override per user per customer
- INDEX: `idx_worktime_user_customer` on `customer_id`
- INDEX: `idx_worktime_user_userid` on `user_id`

**Usage Notes:**
- When `enabled = false` and `start_datetime`/`end_datetime` are set: temporary exception (policy disabled during that window)
- When `enabled = true`: permanent override with custom policy settings
- When override expires (`end_datetime` < now), cleanup task deletes the record
- Custom fields (`start_time`, `end_time`, etc.) override global policy when set; null values fall back to global

### Liquibase Changelogs

| File | Location | Purpose |
|------|----------|---------|
| **Core Changelog** | `core/src/main/resources/liquibase/worktime.changelog.xml` | Plugin registration (intentionally empty - delegates to postgres) |
| **Postgres Changelog** | `postgres/src/main/resources/liquibase/worktime.postgres.changelog.xml` | Creates all worktime tables |

---

## REST API Endpoints

### Base URL

```
http://<host>:<port>/rest/plugins/worktime/
```

### Authentication

**Private Endpoints** (`/rest/plugins/worktime/private/*`):
- **JWT Token**: Valid JWT authentication token
- **Admin Access**: Must have admin privileges for the customer
- **IP Whitelist**: Internal IP addresses only

**Public Endpoints** (`/rest/plugins/worktime/public/*`):
- **Authentication**: Optional (if authenticated, uses current user's customerId)
- **Device Access**: Intended for device/launcher queries
- **customerId**: Required query parameter if not authenticated

---

### Admin Endpoints (Private)

#### 1. GET /rest/plugins/worktime/private/policy

**Description:** Retrieve the global work time policy for the current customer

**Authentication:** Required (JWT + Auth Filter)

**Request:**
```
GET /rest/plugins/worktime/private/policy
Authorization: Bearer <jwt_token>
```

**Response:**
```json
{
  "status": "OK",
  "data": {
    "id": 1,
    "startTime": "09:00",
    "endTime": "17:00",
    "daysOfWeek": 62,  // Mon-Fri (2+4+8+16+32 = 62)
    "allowedAppsDuringWork": "com.company.mail,com.company.calendar",
    "allowedAppsOutsideWork": "*",
    "enabled": true,
    "customerId": 123
  }
}
```

**Status Codes:**
- `200 OK` - Policy retrieved successfully
- `401 Unauthorized` - Invalid or missing JWT token
- `403 Forbidden` - User lacks required permissions
- `404 Not Found` - No policy configured for customer

---

#### 2. POST /rest/plugins/worktime/private/policy

**Description:** Create or update the global work time policy

**Authentication:** Required (JWT + Auth + Admin)

**Content-Type:** `application/json`

**Request:**
```json
POST /rest/plugins/worktime/private/policy
Authorization: Bearer <jwt_token>
Content-Type: application/json

{
  "id": 1,
  "startTime": "09:00",
  "endTime": "17:00",
  "daysOfWeek": 62,
  "allowedAppsDuringWork": "com.company.mail,com.company.calendar",
  "allowedAppsOutsideWork": "*",
  "enabled": true,
  "customerId": 123
}
```

**Response:**
```json
{
  "status": "OK",
  "data": {
    "id": 1,
    "startTime": "09:00",
    "endTime": "17:00",
    "daysOfWeek": 62,
    "allowedAppsDuringWork": "com.company.mail,com.company.calendar",
    "allowedAppsOutsideWork": "*",
    "enabled": true,
    "customerId": 123
  }
}
```

**Status Codes:**
- `200 OK` - Policy saved successfully
- `400 Bad Request` - Invalid policy data
- `401 Unauthorized` - Invalid or missing JWT token
- `403 Forbidden` - User lacks required permissions

---

#### 3. GET /rest/plugins/worktime/private/users

**Description:** List all user overrides for the current customer (includes all users with their override status)

**Authentication:** Required (JWT + Auth + Admin)

**Request:**
```
GET /rest/plugins/worktime/private/users
Authorization: Bearer <jwt_token>
```

**Response:**
```json
{
  "status": "OK",
  "data": [
    {
      "id": 1,
      "customerId": 123,
      "userId": 456,
      "userName": "john.doe",
      "enabled": true,
      "startTime": "10:00",
      "endTime": "16:00",
      "daysOfWeek": "1,2,3,4,5",
      "allowedAppsDuringWork": "com.work.app1,com.work.app2",
      "allowedAppsOutsideWork": "*",
      "exceptions": [
        {
          "dateFrom": "2026-02-10",
          "dateTo": "2026-02-15",
          "timeFrom": "09:00",
          "timeTo": "18:00",
          "active": true
        }
      ],
      "priority": 10,
      "createdAt": "2026-02-01T10:00:00Z",
      "updatedAt": "2026-02-08T15:30:00Z"
    }
  ]
}
```

**Status Codes:**
- `200 OK` - Users retrieved successfully
- `401 Unauthorized` - Invalid or missing JWT token
- `403 Forbidden` - User lacks admin permissions

---

#### 4. GET /rest/plugins/worktime/private/users/{userId}

**Description:** Get override for a specific user

**Authentication:** Required (JWT + Auth + Admin)

**Request:**
```
GET /rest/plugins/worktime/private/users/456
Authorization: Bearer <jwt_token>
```

**Response:**
```json
{
  "status": "OK",
  "data": {
    "id": 1,
    "customerId": 123,
    "userId": 456,
    "userName": "john.doe",
    "enabled": true,
    "startTime": "10:00",
    "endTime": "16:00",
    "exceptions": []
  }
}
```

---

#### 5. POST /rest/plugins/worktime/private/users/{userId}

**Description:** Create or update override for a user

**Authentication:** Required (JWT + Auth + Admin)

**Content-Type:** `application/json`

**Request:**
```json
POST /rest/plugins/worktime/private/users/456
Authorization: Bearer <jwt_token>
Content-Type: application/json

{
  "userId": 456,
  "enabled": true,
  "startTime": "10:00",
  "endTime": "16:00",
  "daysOfWeek": "1,2,3,4,5",
  "allowedAppsDuringWork": "com.work.app",
  "allowedAppsOutsideWork": "*",
  "priority": 10
}
```

**Response:**
```json
{
  "status": "OK",
  "data": {
    "id": 1,
    "customerId": 123,
    "userId": 456,
    "enabled": true,
    "startTime": "10:00",
    "endTime": "16:00"
  }
}
```

---

#### 6. DELETE /rest/plugins/worktime/private/users/{userId}

**Description:** Remove override for a user

**Authentication:** Required (JWT + Auth + Admin)

**Request:**
```
DELETE /rest/plugins/worktime/private/users/456
Authorization: Bearer <jwt_token>
```

**Response:**
```json
{
  "status": "OK"
}
```

---

### Public Endpoints (Device Access)

#### 7. GET /rest/plugins/worktime/public/policy/effective/{userId}

**Description:** Get the resolved effective policy for a user (merges global policy with user overrides)

**Authentication:** Optional (uses authenticated user's customerId or requires customerId parameter)

**Request:**
```
GET /rest/plugins/worktime/public/policy/effective/456?customerId=123
```

**Response:**
```json
{
  "status": "OK",
  "data": {
    "enabled": true,
    "startTime": "10:00",
    "endTime": "16:00",
    "daysOfWeek": 31,
    "allowedAppsDuringWork": ["com.work.app1", "com.work.app2"],
    "allowedAppsOutsideWork": ["*"]
  }
}
```

**Status Codes:**
- `200 OK` - Policy retrieved successfully
- `400 Bad Request` - Missing required customerId parameter

---

#### 8. GET /rest/plugins/worktime/public/allowed

**Description:** Check if a specific app is allowed for a user at the current server time

**Authentication:** Optional

**Query Parameters:**
- `userId` (required) - User ID to check
- `pkg` (required) - App package name (e.g., `com.example.app`)
- `customerId` (optional) - Customer ID (required if not authenticated)

**Request:**
```
GET /rest/plugins/worktime/public/allowed?userId=456&pkg=com.example.app&customerId=123
```

**Response:**
```json
{
  "status": "OK",
  "data": true
}
```

**Status Codes:**
- `200 OK` - Check completed (data is boolean)
- `400 Bad Request` - Missing required parameters

---

### Days of Week Bitmask Reference

The `daysOfWeek` field uses a 7-bit bitmask:

| Day | Bit Value | Hex |
|-----|-----------|-----|
| Monday | 1 | 0x01 |
| Tuesday | 2 | 0x02 |
| Wednesday | 4 | 0x04 |
| Thursday | 8 | 0x08 |
| Friday | 16 | 0x10 |
| Saturday | 32 | 0x20 |
| Sunday | 64 | 0x40 |

**Common Values:**
- Monday-Friday: `1+2+4+8+16 = 31` or `0x1F`
- Monday-Friday (Revised): `2+4+8+16+32 = 62` or `0x3E`
- All days: `127` or `0x7F`
- Weekday + Saturday: `1+2+4+8+16+32 = 63` or `0x3F`

---

## Android Client Integration

### Overview

The WorkTime plugin provides multiple integration methods for Android MDM clients to enforce worktime policies:

1. **Automatic Delivery via Sync** - Policies automatically included in device configuration sync (recommended)
2. **Device-Specific REST APIs** - Direct API calls using device number
3. **User-Based REST APIs** - For admin panels or when user context is available

### Architecture

```
┌─────────────────────────────────────────────────────┐
│           Android MDM Client (Device)                │
├─────────────────────────────────────────────────────┤
│                                                       │
│  1. Device syncs configuration with server           │
│     GET /public/sync/configuration/{deviceNumber}    │
│                                                       │
│  2. Server automatically injects worktime policy     │
│     via WorkTimeSyncResponseHook                     │
│                                                       │
│  3. Policy delivered in sync response (custom1)      │
│     { "custom1": "{\"pluginId\":\"worktime\"...}" }  │
│                                                       │
│  4. Android parses policy and enforces locally       │
│     - Checks current time against policy             │
│     - Blocks/allows apps based on time               │
│                                                       │
│  5. Optionally: Real-time checks via REST API        │
│     GET /plugins/worktime/public/device/{number}/... │
│                                                       │
└─────────────────────────────────────────────────────┘
```

---

### Method 1: Automatic Sync Integration (Recommended)

**How It Works:**

When an Android device calls the standard sync endpoint to get its configuration:

```
GET /public/sync/configuration/{deviceNumber}
```

The `WorkTimeSyncResponseHook` automatically:
1. Resolves the device's customer ID
2. Fetches the effective worktime policy for that device
3. Serializes the policy to JSON
4. Injects it into the `custom1` field of the sync response

**Android Implementation:**

```java
// 1. Perform standard device sync
Response<SyncResponse> syncResponse = syncApi.getConfiguration(deviceNumber);

// 2. Extract worktime policy from custom1 field
String worktimePolicyJson = syncResponse.body().getCustom1();

// 3. Parse the policy wrapper
WorkTimePolicyWrapper wrapper = gson.fromJson(worktimePolicyJson, WorkTimePolicyWrapper.class);

// 4. Check if worktime plugin is present
if ("worktime".equals(wrapper.getPluginId())) {
    EffectiveWorkTimePolicy policy = wrapper.getPolicy();
    
    // 5. Store policy locally for enforcement
    worktimeManager.updatePolicy(policy);
    
    // 6. Apply enforcement rules
    if (policy.isEnabled()) {
        enforceWorktimeRestrictions(policy);
    }
}
```

**Policy Wrapper Structure:**

```json
{
  "pluginId": "worktime",
  "timestamp": 1707523200000,
  "policy": {
    "enabled": true,
    "startTime": "09:00",
    "endTime": "18:00",
    "daysOfWeek": 31,
    "allowedAppsDuringWork": ["com.work.app1", "com.work.app2"],
    "allowedAppsOutsideWork": ["*"]
  }
}
```

**Benefits:**
- ✅ No additional API calls required
- ✅ Policy updates delivered automatically during sync
- ✅ Works with existing sync infrastructure
- ✅ Minimal network overhead

---

### Method 2: Device-Specific REST APIs

For real-time policy checks or when sync is not available, use device-specific endpoints:

#### 9. GET /rest/plugins/worktime/public/device/{deviceNumber}/policy

**Description:** Get the effective worktime policy for a specific device

**Authentication:** None required (device number identifies the device)

**Request:**
```
GET /rest/plugins/worktime/public/device/DEVICE-001/policy
```

**Response:**
```json
{
  "status": "OK",
  "data": {
    "enabled": true,
    "startTime": "09:00",
    "endTime": "18:00",
    "daysOfWeek": 31,
    "allowedAppsDuringWork": ["com.work.app1", "com.work.app2"],
    "allowedAppsOutsideWork": ["*"]
  }
}
```

**Android Implementation:**
```java
public EffectiveWorkTimePolicy fetchPolicy(String deviceNumber) {
    Call<Response<EffectiveWorkTimePolicy>> call = 
        worktimeApi.getDevicePolicy(deviceNumber);
    
    Response<Response<EffectiveWorkTimePolicy>> response = call.execute();
    if (response.isSuccessful() && "OK".equals(response.body().getStatus())) {
        return response.body().getData();
    }
    return null;
}
```

---

#### 10. GET /rest/plugins/worktime/public/device/{deviceNumber}/allowed

**Description:** Check if a specific app is allowed for the device at current time

**Request:**
```
GET /rest/plugins/worktime/public/device/DEVICE-001/allowed?pkg=com.example.app
```

**Response:**
```json
{
  "status": "OK",
  "data": true
}
```

**Android Implementation:**
```java
public boolean isAppAllowed(String deviceNumber, String packageName) {
    Call<Response<Boolean>> call = 
        worktimeApi.isAppAllowedForDevice(deviceNumber, packageName);
    
    Response<Response<Boolean>> response = call.execute();
    if (response.isSuccessful() && "OK".equals(response.body().getStatus())) {
        return response.body().getData();
    }
    return true; // Default: allow if check fails
}
```

---

#### 11. GET /rest/plugins/worktime/public/device/{deviceNumber}/status

**Description:** Get quick status check (enabled + currently in work time)

**Request:**
```
GET /rest/plugins/worktime/public/device/DEVICE-001/status
```

**Response:**
```json
{
  "status": "OK",
  "data": {
    "enabled": true,
    "currentlyInWorkTime": true,
    "startTime": "09:00",
    "endTime": "18:00",
    "daysOfWeek": 31
  }
}
```

**Use Case:** Lightweight status check without fetching full policy

---

### Android Enforcement Logic

**Sample Implementation:**

```java
public class WorkTimeEnforcer {
    private EffectiveWorkTimePolicy policy;
    private Handler handler = new Handler();
    
    public void updatePolicy(EffectiveWorkTimePolicy newPolicy) {
        this.policy = newPolicy;
        scheduleNextCheck();
    }
    
    public boolean isAppAllowedNow(String packageName) {
        if (policy == null || !policy.isEnabled()) {
            return true; // No policy or disabled: allow all
        }
        
        boolean isWorkTime = isCurrentlyWorkTime();
        Set<String> allowedApps = isWorkTime ? 
            policy.getAllowedAppsDuringWork() : 
            policy.getAllowedAppsOutsideWork();
        
        // Check if wildcard or explicit permission
        return allowedApps.contains("*") || allowedApps.contains(packageName);
    }
    
    private boolean isCurrentlyWorkTime() {
        LocalDateTime now = LocalDateTime.now();
        DayOfWeek today = now.getDayOfWeek();
        LocalTime currentTime = now.toLocalTime();
        
        // Check if today is a workday
        int dayBit = 1 << (today.getValue() - 1);
        if ((policy.getDaysOfWeek() & dayBit) == 0) {
            return false; // Not a workday
        }
        
        // Parse work hours
        LocalTime start = LocalTime.parse(policy.getStartTime());
        LocalTime end = LocalTime.parse(policy.getEndTime());
        
        // Check if current time is within work hours
        return !currentTime.isBefore(start) && !currentTime.isAfter(end);
    }
    
    private void scheduleNextCheck() {
        // Re-check policy at start/end of work hours
        // (Implementation depends on app architecture)
    }
}
```

---

### Retrofit API Interface

```java
public interface WorkTimeApi {
    // Device-based endpoints
    @GET("/rest/plugins/worktime/public/device/{deviceNumber}/policy")
    Call<Response<EffectiveWorkTimePolicy>> getDevicePolicy(
        @Path("deviceNumber") String deviceNumber
    );
    
    @GET("/rest/plugins/worktime/public/device/{deviceNumber}/allowed")
    Call<Response<Boolean>> isAppAllowedForDevice(
        @Path("deviceNumber") String deviceNumber,
        @Query("pkg") String packageName
    );
    
    @GET("/rest/plugins/worktime/public/device/{deviceNumber}/status")
    Call<Response<WorkTimeStatus>> getDeviceStatus(
        @Path("deviceNumber") String deviceNumber
    );
}
```

---

### Caching Strategy

**Recommended approach:**

1. **Sync-based updates** (primary):
   - Cache policy from sync response
   - Automatic updates every sync interval (configurable, typically 15-60 min)

2. **Real-time API fallback** (optional):
   - Use API endpoints if policy not in cache
   - For immediate updates after policy changes

3. **Local enforcement** (always):
   - Don't make API call for every app launch
   - Use cached policy for local time-based decisions
   - Only query API for edge cases or verification

---

### Testing Android Integration

#### Manual Testing Steps:

1. **Setup**:
   - Configure worktime policy on MDM server
   - Enroll test device with known device number

2. **Test Sync Integration**:
   ```bash
   # Trigger device sync
   curl -X GET "http://your-server/public/sync/configuration/DEVICE-001"
   # Verify custom1 field contains worktime policy
   ```

3. **Test Device APIs**:
   ```bash
   # Get policy
   curl "http://your-server/rest/plugins/worktime/public/device/DEVICE-001/policy"
   
   # Check app permission
   curl "http://your-server/rest/plugins/worktime/public/device/DEVICE-001/allowed?pkg=com.example.app"
   
   # Get status
   curl "http://your-server/rest/plugins/worktime/public/device/DEVICE-001/status"
   ```

4. **Test Time-Based Enforcement**:
   - Set policy with specific work hours (e.g., 9 AM - 5 PM)
   - Test app access before, during, and after work hours
   - Verify correct appsare allowed/blocked

5. **Test Per-Device Overrides**:
   - Create user override for specific device (using deviceId as userId)
   - Verify override takes precedence over global policy

---

### Error Handling

**Android should handle these scenarios:**

| Scenario | Recommended Behavior |
|----------|---------------------|
| Policy not available in sync response | Default to "allow all" or use cached policy |
| Device not found (404) | Re-enroll or contact admin |
| Network error during API call | Use cached policy, retry later |
| Malformed policy JSON | Log error, default to "allow all" |
| Policy disabled (`enabled: false`) | Allow all apps |
| No policy configured on server | Allow all apps (default permissive) |

---

### Security Considerations

1. **Device Authentication:**
   - Device-specific endpoints use deviceNumber for identification
   - Consider adding device signature validation ifneeded
   - Current implementation: deviceNumber is trusted (device enrolled in MDM)

2. **Local Enforcement:**
   - Policy enforcement must happen on device
   - Server provides policy, device enforces it
   - Malicious users with root can bypass (inherent limitation)

3. **Policy Integrity:**
   - Policies delivered via HTTPS
   - Consider adding checksum/signature in production
   - Current implementation: trusts HTTPS transport security

---

## Configuration

### 1. Project-Level Configuration

#### 1.1 `plugins/pom.xml`

Add worktime module to parent POM:

**Location:** [plugins/pom.xml](plugins/pom.xml)

```xml
<modules>
    <module>platform</module>
    <module>devicelog</module>
    <module>deviceinfo</module>
    <module>audit</module>
    <module>messaging</module>
    <module>push</module>
    <module>worktime</module>  <!-- ADD THIS -->
    <module>xtra</module>
</modules>
```

---

#### 1.2 `server/pom.xml`

Add runtime dependencies for worktime core and postgres modules:

**Location:** [server/pom.xml](server/pom.xml)

```xml
<dependencies>
    <!-- Other dependencies... -->
    
    <!-- ADD THESE DEPENDENCIES -->
    <dependency>
        <groupId>com.hmdm.plugin</groupId>
        <artifactId>worktime-core</artifactId>
        <version>0.1.0</version>
        <scope>runtime</scope>
    </dependency>
    <dependency>
        <groupId>com.hmdm.plugin</groupId>
        <artifactId>worktime-postgres</artifactId>
        <version>0.1.0</version>
        <scope>runtime</scope>
    </dependency>
</dependencies>
```

---

### 2. Build-Time Configuration

#### 2.1 `server/build.properties.example`

Define the persistence configuration class:

**Location:** [server/build.properties.example](server/build.properties.example)

```properties
# Configure worktime plugin persistence
plugin.worktime.persistence.config.class=com.hmdm.plugins.worktime.persistence.postgres.WorkTimePostgresPersistenceConfiguration
```

#### 2.2 `server/build.properties` (Local)

Copy the property to your local `build.properties`:

```properties
# Same as above
plugin.worktime.persistence.config.class=com.hmdm.plugins.worktime.persistence.postgres.WorkTimePostgresPersistenceConfiguration
```

---

### 3. Runtime Configuration

#### 3.1 Development: `server/conf/context.xml`

Add Tomcat context parameter:

**Location:** [server/conf/context.xml](server/conf/context.xml)

```xml
<Context>
    <!-- Other parameters... -->
    
    <!-- ADD THIS PARAMETER -->
    <Parameter name="plugin.worktime.persistence.config.class"
               value="${plugin.worktime.persistence.config.class}"/>
</Context>
```

**Purpose:** Passes persistence configuration class to servlet context for runtime initialization

---

#### 3.2 Production: `install/context_template.xml`

Add for production deployments:

**Location:** [install/context_template.xml](install/context_template.xml)

```xml
<Context>
    <!-- Other parameters... -->
    
    <!-- ADD THIS PARAMETER (hardcoded for production) -->
    <Parameter name="plugin.worktime.persistence.config.class"
               value="com.hmdm.plugins.worktime.persistence.postgres.WorkTimePostgresPersistenceConfiguration"/>
</Context>
```

**Purpose:** Production deployment template uses hardcoded class name (no Maven filtering)

---

## Integration Points

### 1. Plugin Discovery & Loading

The plugin is discovered and loaded by the main server via:

1. **Service Provider Interface (SPI):** Java's `ServiceLoader` mechanism
2. **File:** `META-INF/services/com.hmdm.plugin.PluginConfiguration`
3. **Entry:** Points to `com.hmdm.plugins.worktime.WorkTimePluginConfigurationImpl`

### 2. Dependency Injection

The plugin integrates with Headwind MDM's Guice-based DI container:

```
Server Boot
  ↓
Load PluginConfiguration implementations (SPI)
  ↓
Call WorkTimePluginConfigurationImpl.getPluginModules()
  ↓
Install Guice modules:
  - WorkTimeLiquibaseModule (DB migrations)
  - WorkTimePostgresLiquibaseModule (Postgres specific)
  - WorkTimePostgresServiceModule (DAO binding)
  - WorkTimePostgresPersistenceModule (MyBatis)
  - WorkTimeRestModule (REST API binding)
  ↓
Bind REST resources:
  - WorkTimeResource (@Path /rest/plugins/worktime/*)
```

### 3. Database Integration

Liquibase automatically runs migrations:

```
Server Startup
  ↓
Liquibase initialization
  ↓
Load changelog from classpath
  ↓
Execute changesets:
  - worktime.changelog.xml (core - empty)
  - worktime.postgres.changelog.xml (creates tables)
  ↓
Database ready with worktime tables
```

### 4. REST API Integration

Jersey/JAX-RS discovers REST resources:

```
HTTP Request: GET /rest/plugins/worktime/private/policy
  ↓
Jersey routing (path matching)
  ↓
Filter chain execution:
  1. JWTFilter (validate JWT)
  2. AuthFilter (check authentication)
  3. PluginAccessFilter (plugin permissions)
  4. PrivateIPFilter (IP validation)
  ↓
WorkTimeResource.getDemoPolicy()
  ↓
WorkTimeDAO implementation call
  ↓
MyBatis mapper execution
  ↓
PostgreSQL query
  ↓
JSON response serialization
```

---

## Development Guide

### Build the Plugin

Navigate to plugin root and build with Maven:

```bash
cd hmdm-server/plugins/worktime
mvn clean install
```

This builds:
- `worktime-core-0.1.0.jar`
- `worktime-postgres-0.1.0.jar`

### Build Entire Project

```bash
cd hmdm-server
mvn clean install
```

### Run Tests

```bash
# Run plugin tests only
mvn test -DskipTests=false

# Run full project tests
cd hmdm-server
mvn test
```

### Debug Information

#### Enable Debug Logging

Add to Logback configuration (`conf/logback.xml` or similar):

```xml
<logger name="com.hmdm.plugins.worktime" level="DEBUG"/>
```

#### Check Loaded Plugins

The server logs all discovered plugins on startup:

```
INFO  Discovered PluginConfiguration implementations:
INFO  - com.hmdm.plugins.platform.PlatformPluginConfigurationImpl
INFO  - com.hmdm.plugins.worktime.WorkTimePluginConfigurationImpl
INFO  - ...
```

#### Verify REST Endpoints Bound

Jersey logs bound resources:

```
INFO  Binding REST resource: com.hmdm.plugins.worktime.rest.resource.WorkTimeResource
INFO  Path: /rest/plugins/worktime/private
```

### Common Development Tasks

#### Add a New REST Endpoint

1. Add method to `WorkTimeResource`:
   ```java
   @GET
   @Path("/newendpoint")
   public Response getNewEndpoint() {
       // Implementation
   }
   ```

2. Method is auto-discovered by Jersey (no additional configuration needed)

#### Add a New Database Table

1. Create new changeset in `postgres/src/main/resources/liquibase/worktime.postgres.changelog.xml`:
   ```xml
   <changeSet id="worktime-create-newtable" author="worktime-plugin">
       <createTable tableName="worktime_newtable">
           <!-- columns -->
       </createTable>
   </changeSet>
   ```

2. Liquibase automatically executes on next server startup

#### Update a Data Model

1. Modify class in `core/src/main/java/com/hmdm/plugins/worktime/model/`
2. Update corresponding MyBatis mapper XML if needed
3. Add Liquibase migration for schema changes
4. Rebuild and redeploy

---

## Summary: Key Paths & Files

| Element | Path |
|---------|------|
| **Plugin Root** | `hmdm-server/plugins/worktime/` |
| **Core Module** | `plugins/worktime/core/` |
| **Postgres Module** | `plugins/worktime/postgres/` |
| **Frontend** | `plugins/worktime/src/main/webapp/` |
| **Entry Point** | `core/src/main/java/.../WorkTimePluginConfigurationImpl.java` |
| **Models** | `core/src/main/java/.../model/` (WorkTimePolicy, GlobalWorkTimePolicy, WorkTimeUserOverride, WorkTimePolicyDeviceGroup) |
| **Service Layer** | `core/src/main/java/.../service/` (WorkTimeService, EffectiveWorkTimePolicy) |
| **Sync Hook** | `core/src/main/java/.../sync/WorkTimeSyncResponseHook.java` |
| **Background Task** | `core/src/main/java/.../task/ExpiredExceptionCleanupTask.java` |
| **DAO Interface** | `core/src/main/java/.../persistence/WorkTimeDAO.java` |
| **DAO Implementation** | `postgres/src/main/java/.../dao/PostgresWorkTimeDAO.java` |
| **REST Resources** | `core/src/main/java/.../rest/resource/` (WorkTimeResource, WorkTimePublicResource) |
| **Core Changelog** | `core/src/main/resources/liquibase/worktime.changelog.xml` |
| **Postgres Changelog** | `postgres/src/main/resources/liquibase/worktime.postgres.changelog.xml` |
| **Models** | `core/src/main/java/.../model/` |
| **Guice Modules** | `core/src/main/java/.../guice/module/` |
| **MyBatis Mappers** | `postgres/src/main/java/.../dao/mapper/` |
| **i18n Translations** | `src/main/webapp/i18n/` |
| **Server Integration** | `server/pom.xml`, `server/build.properties`, `server/conf/context.xml` |
| **Installation Template** | `install/context_template.xml` |

---

## Appendix: Plugin ID & Constants

```java
// Plugin Identifier
PLUGIN_ID = "worktime"

// Package Name
ROOT_PACKAGE = "com.hmdm.plugins.worktime"

// REST Base Paths
PROTECTED_PATH = "/rest/plugins/worktime/private"
PUBLIC_PATH = "/rest/plugins/worktime/public"  (optional)

// Database Tables
TABLE_GLOBAL_POLICY = "worktime_global_policy"
TABLE_GLOBAL_OVERRIDE = "worktime_global_override"
TABLE_USER_OVERRIDE = "worktime_user_override"

// Configuration Parameter
CONFIG_PARAM = "plugin.worktime.persistence.config.class"
CONFIG_CLASS = "com.hmdm.plugins.worktime.persistence.postgres.WorkTimePostgresPersistenceConfiguration"
```

---

## Related Resources

- **Main Plugin Development Guide:** [createPlugin.md](createPlugin.md)
- **Server Code:** [server/](server/)
- **Platform Plugin:** [plugins/platform/](plugins/platform/)
- **Build Configuration:** [pom.xml](pom.xml)
- **Installation Scripts:** [hmdm_install.sh](hmdm_install.sh)

---

**Document Version:** 1.0  
**Last Updated:** February 2026  
**Worktime Plugin Version:** 0.1.0
