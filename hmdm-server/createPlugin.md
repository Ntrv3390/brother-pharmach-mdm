# Headwind MDM Server - Complete Plugin Development Guide

## Project Overview

Headwind MDM is an open-source Mobile Device Management (MDM) platform for Android devices, designed for corporate app developers and IT managers.

### Technology Stack

| Component            | Technology           |
| -------------------- | -------------------- |
| Backend Language     | Java 1.8             |
| Build System         | Maven (multi-module) |
| Web Framework        | Jersey 2.x (JAX-RS)  |
| Dependency Injection | Google Guice         |
| Database             | PostgreSQL           |
| ORM/Data Access      | MyBatis              |
| Database Migrations  | Liquibase            |
| Frontend             | AngularJS            |
| Web Server           | Apache Tomcat        |

---

## Where to Create Your Plugin

> **CRITICAL:** Your plugin MUST be created inside the `plugins/` directory at the project root.

**Exact Path:**

```
hmdm-server/plugins/<your-plugin-name>/
```

**Example:** For a plugin named `myplugin`:

```
hmdm-server/plugins/myplugin/
```

---

## Plugin Architecture

Each plugin follows a **two-module structure**:

| Module       | Location                            | Purpose                                                                       |
| ------------ | ----------------------------------- | ----------------------------------------------------------------------------- |
| **Core**     | `plugins/<plugin>/core/`            | Database-agnostic code: plugin config, REST endpoints, DAO interfaces, models |
| **Postgres** | `plugins/<plugin>/postgres/`        | PostgreSQL-specific: DAO implementations, MyBatis mappers, DB changelogs      |
| **Frontend** | `plugins/<plugin>/src/main/webapp/` | AngularJS module, HTML views, i18n translations                               |

---

## Complete Directory Structure (Required Files)

Create the following directory structure for your plugin (replace `myplugin` with your plugin name):

```
hmdm-server/
├── plugins/
│   ├── pom.xml                                          # ← MODIFY: Add your module here
│   └── myplugin/                                        # ← CREATE: Your plugin root
│       ├── pom.xml                                      # Plugin parent POM
│       │
│       ├── core/                                        # CORE MODULE (database-agnostic)
│       │   ├── pom.xml                                  # Core module POM
│       │   └── src/
│       │       └── main/
│       │           ├── java/
│       │           │   └── com/
│       │           │       └── hmdm/
│       │           │           └── plugins/
│       │           │               └── myplugin/
│       │           │                   ├── MyPluginPluginConfigurationImpl.java    # [REQUIRED] Entry point
│       │           │                   │
│       │           │                   ├── model/                                   # Domain objects
│       │           │                   │   ├── MyPluginSettings.java
│       │           │                   │   └── MyPluginRecord.java
│       │           │                   │
│       │           │                   ├── persistence/                             # DAO interfaces
│       │           │                   │   ├── MyPluginDAO.java
│       │           │                   │   └── MyPluginPersistenceConfiguration.java
│       │           │                   │
│       │           │                   ├── rest/
│       │           │                   │   └── resource/                            # REST endpoints
│       │           │                   │       └── MyPluginResource.java
│       │           │                   │
│       │           │                   └── guice/
│       │           │                       └── module/                              # Guice modules
│       │           │                           ├── MyPluginLiquibaseModule.java
│       │           │                           └── MyPluginRestModule.java
│       │           │
│       │           └── resources/
│       │               └── liquibase/
│       │                   └── myplugin.changelog.xml   # Core changelog (plugin registration)
│       │
│       ├── postgres/                                    # POSTGRES MODULE (database-specific)
│       │   ├── pom.xml                                  # Postgres module POM
│       │   └── src/
│       │       └── main/
│       │           ├── java/
│       │           │   └── com/
│       │           │       └── hmdm/
│       │           │           └── plugins/
│       │           │               └── myplugin/
│       │           │                   └── persistence/
│       │           │                       └── postgres/
│       │           │                           ├── MyPluginPostgresPersistenceConfiguration.java
│       │           │                           │
│       │           │                           ├── dao/                             # DAO implementations
│       │           │                           │   ├── PostgresMyPluginDAO.java
│       │           │                           │   │
│       │           │                           │   ├── domain/                      # DB domain objects
│       │           │                           │   │   └── PostgresMyPluginSettings.java
│       │           │                           │   │
│       │           │                           │   └── mapper/                      # MyBatis mappers
│       │           │                           │       ├── PostgresMyPluginMapper.java
│       │           │                           │       └── PostgresMyPluginMapper.xml
│       │           │                           │
│       │           │                           └── guice/
│       │           │                               └── module/                      # Postgres Guice modules
│       │           │                                   ├── MyPluginPostgresLiquibaseModule.java
│       │           │                                   ├── MyPluginPostgresPersistenceModule.java
│       │           │                                   └── MyPluginPostgresServiceModule.java
│       │           │
│       │           └── resources/
│       │               └── liquibase/
│       │                   └── myplugin.postgres.changelog.xml  # Table creation changelog
│       │
│       └── src/                                         # FRONTEND (AngularJS)
│           └── main/
│               └── webapp/
│                   ├── myplugin.module.js               # AngularJS module
│                   │
│                   ├── views/                           # HTML templates
│                   │   ├── main.html
│                   │   └── settings.html
│                   │
│                   └── i18n/                            # Translations
│                       ├── en_US.json
│                       └── ru_RU.json
│
├── server/
│   ├── pom.xml                                          # ← MODIFY: Add plugin dependencies
│   ├── build.properties.example                         # ← MODIFY: Add persistence config class
│   └── conf/
│       └── context.xml                                  # ← MODIFY: Add context parameter
│
└── install/
    └── context_template.xml                             # ← MODIFY: Add context parameter (production)
```

---

## Core Files to Modify (REQUIRED)

When creating a new plugin, you MUST modify these existing project files:

### 1. `hmdm-server/plugins/pom.xml`

| Property        | Value                                             |
| --------------- | ------------------------------------------------- |
| **Exact Path**  | `hmdm-server/plugins/pom.xml`                     |
| **Purpose**     | Parent POM for all plugins                        |
| **Change Type** | Registration                                      |
| **What to Add** | Add your plugin module to the `<modules>` section |

```xml
<modules>
    <!-- existing plugins -->
    <module>platform</module>
    <module>devicelog</module>
    <module>deviceinfo</module>
    <!-- ADD YOUR PLUGIN HERE -->
    <module>myplugin</module>
</modules>
```

---

### 2. `hmdm-server/server/pom.xml`

| Property        | Value                                                       |
| --------------- | ----------------------------------------------------------- |
| **Exact Path**  | `hmdm-server/server/pom.xml`                                |
| **Purpose**     | Main server WAR module that includes plugin JARs            |
| **Change Type** | Dependency registration                                     |
| **What to Add** | Add runtime dependencies for both core and postgres modules |

```xml
<dependencies>
    <!-- existing dependencies -->

    <!-- ADD YOUR PLUGIN DEPENDENCIES -->
    <dependency>
        <groupId>com.hmdm.plugin</groupId>
        <artifactId>myplugin-core</artifactId>
        <version>0.1.0</version>
        <scope>runtime</scope>
    </dependency>
    <dependency>
        <groupId>com.hmdm.plugin</groupId>
        <artifactId>myplugin-postgres</artifactId>
        <version>0.1.0</version>
        <scope>runtime</scope>
    </dependency>
</dependencies>
```

---

### 3. `hmdm-server/server/build.properties.example`

| Property        | Value                                         |
| --------------- | --------------------------------------------- |
| **Exact Path**  | `hmdm-server/server/build.properties.example` |
| **Purpose**     | Build-time configuration properties           |
| **Change Type** | Configuration                                 |
| **What to Add** | Add persistence configuration class property  |

```properties
# ADD THIS LINE
plugin.myplugin.persistence.config.class=com.hmdm.plugins.myplugin.persistence.postgres.MyPluginPostgresPersistenceConfiguration
```

> **NOTE:** Also add this to your local `build.properties` file (copied from `build.properties.example`).

---

### 4. `hmdm-server/server/conf/context.xml`

| Property        | Value                                        |
| --------------- | -------------------------------------------- |
| **Exact Path**  | `hmdm-server/server/conf/context.xml`        |
| **Purpose**     | Tomcat context configuration for development |
| **Change Type** | Runtime configuration                        |
| **What to Add** | Add context parameter for persistence class  |

```xml
<Context>
    <!-- existing parameters -->

    <!-- ADD THIS PARAMETER -->
    <Parameter name="plugin.myplugin.persistence.config.class"
               value="${plugin.myplugin.persistence.config.class}"/>
</Context>
```

> **CRITICAL:** Without this parameter, your plugin's persistence layer will NOT load at runtime!

---

### 5. `hmdm-server/install/context_template.xml`

| Property        | Value                                               |
| --------------- | --------------------------------------------------- |
| **Exact Path**  | `hmdm-server/install/context_template.xml`          |
| **Purpose**     | Tomcat context template for production installation |
| **Change Type** | Production configuration                            |
| **What to Add** | Add context parameter with hardcoded class name     |

```xml
<Context>
    <!-- existing parameters -->

    <!-- ADD THIS PARAMETER -->
    <Parameter name="plugin.myplugin.persistence.config.class"
               value="com.hmdm.plugins.myplugin.persistence.postgres.MyPluginPostgresPersistenceConfiguration"/>
</Context>
```

---

## Summary: Files to Modify Checklist

| #   | File Path                         | Change Type                                              | Required |
| --- | --------------------------------- | -------------------------------------------------------- | -------- |
| 1   | `plugins/pom.xml`                 | Add `<module>myplugin</module>`                          | ✅ YES   |
| 2   | `server/pom.xml`                  | Add `myplugin-core` and `myplugin-postgres` dependencies | ✅ YES   |
| 3   | `server/build.properties.example` | Add `plugin.myplugin.persistence.config.class`           | ✅ YES   |
| 4   | `server/build.properties` (local) | Add `plugin.myplugin.persistence.config.class`           | ✅ YES   |
| 5   | `server/conf/context.xml`         | Add `<Parameter>` for persistence class                  | ✅ YES   |
| 6   | `install/context_template.xml`    | Add `<Parameter>` for production deployment              | ✅ YES   |

---

## 1. Maven POM Files

### 1.1 Plugin Parent POM (`plugins/myplugin/pom.xml`)

```xml
<?xml version="1.0" encoding="UTF-8" ?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
         http://maven.apache.org/maven-v4_0_0.xsd">

    <modelVersion>4.0.0</modelVersion>

    <artifactId>myplugin</artifactId>
    <packaging>pom</packaging>
    <version>0.1.0</version>
    <name>My Plugin for MDM Server</name>

    <parent>
        <groupId>com.hmdm.plugin</groupId>
        <artifactId>base</artifactId>
        <version>0.1.0</version>
        <relativePath>../pom.xml</relativePath>
    </parent>

    <modules>
        <module>core</module>
        <module>postgres</module>
    </modules>

    <dependencies>
        <dependency>
            <groupId>com.hmdm.plugin</groupId>
            <artifactId>platform</artifactId>
            <version>0.1.0</version>
        </dependency>
    </dependencies>

</project>
```

### 1.2 Core Module POM (`plugins/myplugin/core/pom.xml`)

```xml
<?xml version="1.0" encoding="UTF-8" ?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
         http://maven.apache.org/maven-v4_0_0.xsd">

    <modelVersion>4.0.0</modelVersion>

    <artifactId>myplugin-core</artifactId>
    <packaging>jar</packaging>
    <version>0.1.0</version>
    <name>My Plugin for MDM Server - Core</name>

    <parent>
        <groupId>com.hmdm.plugin</groupId>
        <artifactId>myplugin</artifactId>
        <version>0.1.0</version>
        <relativePath>../pom.xml</relativePath>
    </parent>

    <dependencies>
        <!-- Add if you need notification support -->
        <dependency>
            <groupId>com.hmdm</groupId>
            <artifactId>notification</artifactId>
            <version>0.1.0</version>
        </dependency>
    </dependencies>

</project>
```

### 1.3 Postgres Module POM (`plugins/myplugin/postgres/pom.xml`)

```xml
<?xml version="1.0" encoding="UTF-8" ?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
         http://maven.apache.org/maven-v4_0_0.xsd">

    <modelVersion>4.0.0</modelVersion>

    <artifactId>myplugin-postgres</artifactId>
    <packaging>jar</packaging>
    <version>0.1.0</version>
    <name>My Plugin for MDM Server - Postgres</name>

    <parent>
        <groupId>com.hmdm.plugin</groupId>
        <artifactId>myplugin</artifactId>
        <version>0.1.0</version>
        <relativePath>../pom.xml</relativePath>
    </parent>

    <dependencies>
        <dependency>
            <groupId>com.hmdm.plugin</groupId>
            <artifactId>myplugin-core</artifactId>
            <version>0.1.0</version>
        </dependency>
    </dependencies>

</project>
```

### 1.4 Register Plugin in Parent POMs

**Add to `plugins/pom.xml`:**

```xml
<modules>
    <!-- existing plugins -->
    <module>myplugin</module>
</modules>
```

**Add to `server/pom.xml`:**

```xml
<dependency>
    <groupId>com.hmdm.plugin</groupId>
    <artifactId>myplugin-core</artifactId>
    <version>0.1.0</version>
    <scope>runtime</scope>
</dependency>
<dependency>
    <groupId>com.hmdm.plugin</groupId>
    <artifactId>myplugin-postgres</artifactId>
    <version>0.1.0</version>
    <scope>runtime</scope>
</dependency>
```

---

## 2. Core Module Java Classes

### 2.1 Plugin Configuration (REQUIRED)

**File:** `core/src/main/java/com/hmdm/plugins/myplugin/MyPluginPluginConfigurationImpl.java`

```java
package com.hmdm.plugins.myplugin;

import com.google.inject.Module;
import com.hmdm.plugin.PluginConfiguration;
import com.hmdm.plugin.PluginTaskModule;
import com.hmdm.plugins.myplugin.guice.module.MyPluginLiquibaseModule;
import com.hmdm.plugins.myplugin.guice.module.MyPluginRestModule;
import com.hmdm.plugins.myplugin.persistence.MyPluginPersistenceConfiguration;

import javax.servlet.ServletContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class MyPluginPluginConfigurationImpl implements PluginConfiguration {

    public static final String PLUGIN_ID = "myplugin";

    public MyPluginPluginConfigurationImpl() {
    }

    @Override
    public String getPluginId() {
        return PLUGIN_ID;
    }

    @Override
    public String getRootPackage() {
        return "com.hmdm.plugins.myplugin";
    }

    @Override
    public List<Module> getPluginModules(ServletContext context) {
        try {
            List<Module> modules = new ArrayList<>();

            // Add Liquibase module for core changelog
            modules.add(new MyPluginLiquibaseModule(context));

            // Load persistence configuration from context parameter
            final String configClass = context.getInitParameter("plugin.myplugin.persistence.config.class");
            if (configClass != null && !configClass.trim().isEmpty()) {
                MyPluginPersistenceConfiguration config =
                    (MyPluginPersistenceConfiguration) Class.forName(configClass).newInstance();
                modules.addAll(config.getPersistenceModules(context));
            }

            // Add REST module
            modules.add(new MyPluginRestModule());

            return modules;

        } catch (InstantiationException | IllegalAccessException | ClassNotFoundException e) {
            throw new IllegalArgumentException(
                "Could not initialize persistence layer for MyPlugin plugin", e);
        }
    }

    @Override
    public Optional<List<Class<? extends PluginTaskModule>>> getTaskModules(ServletContext context) {
        return Optional.empty(); // Add task modules if needed
    }
}
```

### 2.2 REST Module

**File:** `core/src/main/java/com/hmdm/plugins/myplugin/guice/module/MyPluginRestModule.java`

```java
package com.hmdm.plugins.myplugin.guice.module;

import com.google.inject.servlet.ServletModule;
import com.hmdm.plugin.rest.PluginAccessFilter;
import com.hmdm.plugins.myplugin.rest.resource.MyPluginResource;
import com.hmdm.rest.filter.AuthFilter;
import com.hmdm.rest.filter.PrivateIPFilter;
import com.hmdm.rest.filter.PublicIPFilter;
import com.hmdm.security.jwt.JWTFilter;

import java.util.Arrays;
import java.util.List;

public class MyPluginRestModule extends ServletModule {

    // URLs requiring authentication (admin panel)
    private static final List<String> protectedResources = Arrays.asList(
        "/rest/plugins/myplugin/private",
        "/rest/plugins/myplugin/private/*"
    );

    // URLs accessible from devices (optional)
    private static final List<String> publicResources = Arrays.asList(
        "/rest/plugins/myplugin/public/*"
    );

    public MyPluginRestModule() {
    }

    @Override
    protected void configureServlets() {
        // Apply security filters to protected resources
        this.filter(protectedResources).through(JWTFilter.class);
        this.filter(protectedResources).through(AuthFilter.class);
        this.filter(protectedResources).through(PluginAccessFilter.class);
        this.filter(protectedResources).through(PrivateIPFilter.class);

        // Apply IP filter to public resources (device access)
        this.filter(publicResources).through(PublicIPFilter.class);

        // Bind REST resource classes
        this.bind(MyPluginResource.class);
    }
}
```

### 2.3 Liquibase Module

**File:** `core/src/main/java/com/hmdm/plugins/myplugin/guice/module/MyPluginLiquibaseModule.java`

```java
package com.hmdm.plugins.myplugin.guice.module;

import com.hmdm.guice.module.AbstractLiquibaseModule;
import com.hmdm.plugin.guice.module.PluginLiquibaseResourceAccessor;
import liquibase.resource.ResourceAccessor;

import javax.servlet.ServletContext;

public class MyPluginLiquibaseModule extends AbstractLiquibaseModule {

    public MyPluginLiquibaseModule(ServletContext context) {
        super(context);
    }

    @Override
    protected String getChangeLogResourcePath() {
        String path = this.getClass().getResource("/liquibase/myplugin.changelog.xml").getPath();
        if (!path.startsWith("jar:")) {
            path = "jar:" + path;
        }
        return path;
    }

    @Override
    protected ResourceAccessor getResourceAccessor() {
        return new PluginLiquibaseResourceAccessor();
    }
}
```

### 2.4 Persistence Configuration Interface

**File:** `core/src/main/java/com/hmdm/plugins/myplugin/persistence/MyPluginPersistenceConfiguration.java`

```java
package com.hmdm.plugins.myplugin.persistence;

import com.google.inject.Module;
import com.hmdm.plugin.PluginTaskModule;

import javax.servlet.ServletContext;
import java.util.List;
import java.util.Optional;

public interface MyPluginPersistenceConfiguration {

    List<Module> getPersistenceModules(ServletContext context);

    default Optional<List<Class<? extends PluginTaskModule>>> getTaskModules(ServletContext context) {
        return Optional.empty();
    }
}
```

### 2.5 DAO Interface

**File:** `core/src/main/java/com/hmdm/plugins/myplugin/persistence/MyPluginDAO.java`

```java
package com.hmdm.plugins.myplugin.persistence;

import com.hmdm.plugins.myplugin.model.MyPluginSettings;

public interface MyPluginDAO {

    MyPluginSettings getPluginSettings();

    void insertPluginSettings(MyPluginSettings settings);

    void updatePluginSettings(MyPluginSettings settings);
}
```

### 2.6 REST Resource

**File:** `core/src/main/java/com/hmdm/plugins/myplugin/rest/resource/MyPluginResource.java`

```java
package com.hmdm.plugins.myplugin.rest.resource;

import com.hmdm.plugins.myplugin.persistence.MyPluginDAO;
import com.hmdm.plugins.myplugin.model.MyPluginSettings;
import com.hmdm.rest.json.Response;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;

@Singleton
@Path("/plugins/myplugin")
@Api(tags = {"My Plugin"})
public class MyPluginResource {

    private final MyPluginDAO dao;

    @Inject
    public MyPluginResource(MyPluginDAO dao) {
        this.dao = dao;
    }

    @GET
    @Path("/private/settings")
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation("Get plugin settings")
    public Response getSettings() {
        try {
            return Response.OK(dao.getPluginSettings());
        } catch (Exception e) {
            return Response.INTERNAL_ERROR();
        }
    }

    @PUT
    @Path("/private/settings")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation("Save plugin settings")
    public Response saveSettings(MyPluginSettings settings) {
        try {
            dao.updatePluginSettings(settings);
            return Response.OK();
        } catch (Exception e) {
            return Response.INTERNAL_ERROR();
        }
    }
}
```

---

## 3. Postgres Module Java Classes

### 3.1 Postgres Persistence Configuration

**File:** `postgres/src/main/java/com/hmdm/plugins/myplugin/persistence/postgres/MyPluginPostgresPersistenceConfiguration.java`

```java
package com.hmdm.plugins.myplugin.persistence.postgres;

import com.google.inject.Module;
import com.hmdm.plugins.myplugin.persistence.MyPluginPersistenceConfiguration;
import com.hmdm.plugins.myplugin.persistence.postgres.guice.module.*;

import javax.servlet.ServletContext;
import java.util.ArrayList;
import java.util.List;

public class MyPluginPostgresPersistenceConfiguration implements MyPluginPersistenceConfiguration {

    public MyPluginPostgresPersistenceConfiguration() {
    }

    @Override
    public List<Module> getPersistenceModules(ServletContext context) {
        List<Module> modules = new ArrayList<>();

        modules.add(new MyPluginPostgresLiquibaseModule(context));
        modules.add(new MyPluginPostgresServiceModule());
        modules.add(new MyPluginPostgresPersistenceModule(context));

        return modules;
    }
}
```

### 3.2 Service Module (Binds DAO Interface to Implementation)

**File:** `postgres/src/main/java/com/hmdm/plugins/myplugin/persistence/postgres/guice/module/MyPluginPostgresServiceModule.java`

```java
package com.hmdm.plugins.myplugin.persistence.postgres.guice.module;

import com.google.inject.AbstractModule;
import com.google.inject.Singleton;
import com.hmdm.plugins.myplugin.persistence.MyPluginDAO;
import com.hmdm.plugins.myplugin.persistence.postgres.dao.PostgresMyPluginDAO;

public class MyPluginPostgresServiceModule extends AbstractModule {

    public MyPluginPostgresServiceModule() {
    }

    @Override
    protected void configure() {
        bind(MyPluginDAO.class).to(PostgresMyPluginDAO.class).in(Singleton.class);
    }
}
```

### 3.3 Persistence Module (MyBatis Configuration)

**File:** `postgres/src/main/java/com/hmdm/plugins/myplugin/persistence/postgres/guice/module/MyPluginPostgresPersistenceModule.java`

```java
package com.hmdm.plugins.myplugin.persistence.postgres.guice.module;

import com.hmdm.guice.module.AbstractPersistenceModule;

import javax.servlet.ServletContext;

public class MyPluginPostgresPersistenceModule extends AbstractPersistenceModule {

    public MyPluginPostgresPersistenceModule(ServletContext context) {
        super(context);
    }

    @Override
    protected String getMapperPackageName() {
        return "com.hmdm.plugins.myplugin.persistence.postgres.dao.mapper";
    }

    @Override
    protected String getDomainObjectsPackageName() {
        return "com.hmdm.plugins.myplugin.persistence.postgres.dao.domain";
    }
}
```

### 3.4 Postgres Liquibase Module

**File:** `postgres/src/main/java/com/hmdm/plugins/myplugin/persistence/postgres/guice/module/MyPluginPostgresLiquibaseModule.java`

```java
package com.hmdm.plugins.myplugin.persistence.postgres.guice.module;

import com.hmdm.guice.module.AbstractLiquibaseModule;
import com.hmdm.plugin.guice.module.PluginLiquibaseResourceAccessor;
import liquibase.resource.ResourceAccessor;

import javax.servlet.ServletContext;

public class MyPluginPostgresLiquibaseModule extends AbstractLiquibaseModule {

    public MyPluginPostgresLiquibaseModule(ServletContext context) {
        super(context);
    }

    @Override
    protected String getChangeLogResourcePath() {
        String path = this.getClass().getResource("/liquibase/myplugin.postgres.changelog.xml").getPath();
        if (!path.startsWith("jar:")) {
            path = "jar:" + path;
        }
        return path;
    }

    @Override
    protected ResourceAccessor getResourceAccessor() {
        return new PluginLiquibaseResourceAccessor();
    }
}
```

### 3.5 MyBatis Mapper Interface

**File:** `postgres/src/main/java/com/hmdm/plugins/myplugin/persistence/postgres/dao/mapper/PostgresMyPluginMapper.java`

```java
package com.hmdm.plugins.myplugin.persistence.postgres.dao.mapper;

import com.hmdm.plugins.myplugin.persistence.postgres.dao.domain.PostgresMyPluginSettings;
import org.apache.ibatis.annotations.Param;

public interface PostgresMyPluginMapper {

    PostgresMyPluginSettings findPluginSettingsByCustomerId(@Param("customerId") int customerId);

    void insertPluginSettings(PostgresMyPluginSettings settings);

    void updatePluginSettings(PostgresMyPluginSettings settings);
}
```

### 3.6 MyBatis Mapper XML

**File:** `postgres/src/main/java/com/hmdm/plugins/myplugin/persistence/postgres/dao/mapper/PostgresMyPluginMapper.xml`

```xml
<?xml version="1.0" encoding="UTF-8" ?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
    "http://mybatis.org/dtd/mybatis-3-mapper.dtd">

<mapper namespace="com.hmdm.plugins.myplugin.persistence.postgres.dao.mapper.PostgresMyPluginMapper">

    <select id="findPluginSettingsByCustomerId"
            resultType="PostgresMyPluginSettings">
        SELECT * FROM plugin_myplugin_settings
        WHERE customerId = #{customerId}
    </select>

    <insert id="insertPluginSettings"
            parameterType="PostgresMyPluginSettings">
        INSERT INTO plugin_myplugin_settings (customerId, settingValue)
        VALUES (#{customerId}, #{settingValue})
    </insert>

    <update id="updatePluginSettings"
            parameterType="PostgresMyPluginSettings">
        UPDATE plugin_myplugin_settings
        SET settingValue = #{settingValue}
        WHERE id = #{id}
    </update>

</mapper>
```

---

## 4. Server Configuration

### 4.1 Add to `server/build.properties`

```properties
# Add this line for your plugin
plugin.myplugin.persistence.config.class=com.hmdm.plugins.myplugin.persistence.postgres.MyPluginPostgresPersistenceConfiguration
```

### 4.2 Add to `server/conf/context.xml`

```xml
<Parameter name="plugin.myplugin.persistence.config.class"
           value="${plugin.myplugin.persistence.config.class}"/>
```

> **CRITICAL:** Without this configuration, your plugin's persistence layer will NOT be loaded!

---

## 5. Liquibase Changelogs

### 5.1 Core Changelog (Plugin Registration)

**File:** `core/src/main/resources/liquibase/myplugin.changelog.xml`

```xml
<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog
    xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
    xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
    http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-3.0.xsd"
    logicalFilePath="db.changelog.xml">

    <!-- Register plugin in plugins table -->
    <changeSet id="plugin-myplugin-2024.01.01-01" author="yourname" context="common">
        <comment>Register myplugin plugin</comment>
        <sql>
            INSERT INTO plugins (
                identifier, name, description,
                javascriptModuleFile, functionsViewTemplate, settingsViewTemplate,
                nameLocalizationKey,
                settingsPermission, functionsPermission, deviceFunctionsPermission,
                enabledForDevice
            ) VALUES (
                'myplugin',
                'My Plugin',
                'Description of my plugin',
                'app/components/plugins/myplugin/myplugin.module.js',
                'app/components/plugins/myplugin/views/main.html',
                'app/components/plugins/myplugin/views/settings.html',
                'plugin.myplugin.localization.key.name',
                'plugin_myplugin_access',
                'plugin_myplugin_access',
                'plugin_myplugin_access',
                FALSE
            );
        </sql>
        <rollback>
            DELETE FROM plugins WHERE identifier = 'myplugin';
        </rollback>
    </changeSet>

    <!-- Create permission -->
    <changeSet id="plugin-myplugin-2024.01.01-02" author="yourname" context="common">
        <comment>Create permission for myplugin access</comment>
        <sql>
            INSERT INTO permissions (name, description)
            VALUES ('plugin_myplugin_access', 'Has access to My Plugin');

            INSERT INTO userRolePermissions (roleId, permissionId)
            VALUES (1, (SELECT id FROM permissions WHERE name='plugin_myplugin_access'));

            INSERT INTO userRolePermissions (roleId, permissionId)
            VALUES (2, (SELECT id FROM permissions WHERE name='plugin_myplugin_access'));
        </sql>
        <rollback>
            DELETE FROM userRolePermissions
            WHERE permissionId = (SELECT id FROM permissions WHERE name='plugin_myplugin_access');
            DELETE FROM permissions WHERE name = 'plugin_myplugin_access';
        </rollback>
    </changeSet>

</databaseChangeLog>
```

### 5.2 Postgres Changelog (Table Creation)

**File:** `postgres/src/main/resources/liquibase/myplugin.postgres.changelog.xml`

```xml
<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog
    xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
    xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
    http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-3.0.xsd"
    logicalFilePath="db.changelog.xml">

    <changeSet id="plugin-myplugin-2024.01.01-tables-01" author="yourname" context="common">
        <comment>Create plugin_myplugin_settings table</comment>
        <sql>
            CREATE TABLE plugin_myplugin_settings (
                id SERIAL PRIMARY KEY,
                customerId INT NOT NULL REFERENCES customers(id) ON DELETE CASCADE,
                settingValue TEXT
            );
        </sql>
        <rollback>
            DROP TABLE plugin_myplugin_settings;
        </rollback>
    </changeSet>

    <changeSet id="plugin-myplugin-2024.01.01-tables-02" author="yourname" context="common">
        <comment>Initialize settings for existing customers</comment>
        <sql>
            INSERT INTO plugin_myplugin_settings (customerId)
            SELECT id FROM customers;
        </sql>
        <rollback>
            DELETE FROM plugin_myplugin_settings;
        </rollback>
    </changeSet>

</databaseChangeLog>
```

---

## 6. Frontend (AngularJS)

### 6.1 AngularJS Module

**File:** `src/main/webapp/myplugin.module.js`

```javascript
angular
  .module("plugin-myplugin", [
    "ngResource",
    "ui.bootstrap",
    "ui.router",
    "ncy-angular-breadcrumb",
  ])
  .config(function ($stateProvider) {
    // Main plugin view state
    try {
      $stateProvider.state("plugin-myplugin", {
        url: "/plugin-myplugin",
        templateUrl: "app/components/main/view/content.html",
        controller: "TabController",
        ncyBreadcrumb: {
          label: '{{"breadcrumb.plugin.myplugin.main" | localize}}',
        },
        resolve: {
          openTab: function () {
            return "plugin-myplugin";
          },
        },
      });
    } catch (e) {
      console.log("Error adding state plugin-myplugin", e);
    }

    // Settings view state
    try {
      $stateProvider.state("plugin-settings-myplugin", {
        url: "/plugin-settings-myplugin",
        templateUrl: "app/components/main/view/content.html",
        controller: "TabController",
        ncyBreadcrumb: {
          label: '{{"breadcrumb.plugin.myplugin.settings" | localize}}',
        },
        resolve: {
          openTab: function () {
            return "plugin-settings-myplugin";
          },
        },
      });
    } catch (e) {
      console.log("Error adding state plugin-settings-myplugin", e);
    }
  })
  .factory("pluginMyPluginService", function ($resource) {
    return $resource(
      "",
      {},
      {
        getSettings: {
          url: "rest/plugins/myplugin/private/settings",
          method: "GET",
        },
        saveSettings: {
          url: "rest/plugins/myplugin/private/settings",
          method: "PUT",
        },
      },
    );
  })
  .controller(
    "PluginMyPluginTabController",
    function ($scope, $rootScope, pluginMyPluginService, localization) {
      $rootScope.settingsTabActive = false;
      $rootScope.pluginsTabActive = true;

      $scope.errorMessage = undefined;
      $scope.successMessage = undefined;

      // Load data
      var loadData = function () {
        pluginMyPluginService.getSettings(
          function (response) {
            if (response.status === "OK") {
              $scope.data = response.data;
            } else {
              $scope.errorMessage =
                localization.localizeServerResponse(response);
            }
          },
          function () {
            $scope.errorMessage = localization.localize(
              "error.request.failure",
            );
          },
        );
      };

      loadData();
    },
  )
  .controller(
    "PluginMyPluginSettingsController",
    function ($scope, $rootScope, pluginMyPluginService, localization) {
      $rootScope.settingsTabActive = true;
      $rootScope.pluginsTabActive = false;

      $scope.settings = {};
      $scope.errorMessage = undefined;
      $scope.successMessage = undefined;

      pluginMyPluginService.getSettings(function (response) {
        if (response.status === "OK") {
          $scope.settings = response.data;
        }
      });

      $scope.save = function () {
        $scope.successMessage = undefined;
        $scope.errorMessage = undefined;

        pluginMyPluginService.saveSettings(
          $scope.settings,
          function (response) {
            if (response.status === "OK") {
              $scope.successMessage = localization.localize(
                "success.plugin.myplugin.settings.saved",
              );
            } else {
              $scope.errorMessage =
                localization.localizeServerResponse(response);
            }
          },
        );
      };
    },
  )
  .run(function ($rootScope, localization) {
    // Load plugin translations
    localization.loadPluginResourceBundles("myplugin");
  });
```

### 6.2 i18n Translation File

**File:** `src/main/webapp/i18n/en_US.json`

```json
{
  "breadcrumb.plugin.myplugin.main": "My Plugin",
  "breadcrumb.plugin.myplugin.settings": "My Plugin Settings",

  "plugin.myplugin.localization.key.name": "My Plugin",
  "plugin.myplugin.tab.title": "My Plugin",
  "plugin.myplugin.settings.title": "My Plugin Settings",

  "success.plugin.myplugin.settings.saved": "Settings saved successfully"
}
```

---

## 7. Naming Conventions

| Item            | Convention                                   | Example                                    |
| --------------- | -------------------------------------------- | ------------------------------------------ |
| Plugin ID       | lowercase                                    | `myplugin`                                 |
| Package         | `com.hmdm.plugins.<pluginid>`                | `com.hmdm.plugins.myplugin`                |
| Config class    | `<Name>PluginConfigurationImpl`              | `MyPluginPluginConfigurationImpl`          |
| DB table        | `plugin_<pluginid>_*`                        | `plugin_myplugin_settings`                 |
| Changelog ID    | `plugin-<pluginid>-*`                        | `plugin-myplugin-2024.01.01`               |
| Permission      | `plugin_<pluginid>_access`                   | `plugin_myplugin_access`                   |
| REST path       | `/rest/plugins/<pluginid>/*`                 | `/rest/plugins/myplugin/*`                 |
| Frontend module | `plugin-<pluginid>`                          | `plugin-myplugin`                          |
| Context param   | `plugin.<pluginid>.persistence.config.class` | `plugin.myplugin.persistence.config.class` |

---

## 8. Complete Checklist

| #   | Task                                      | File/Location                                                           |
| --- | ----------------------------------------- | ----------------------------------------------------------------------- |
| 1   | Create plugin directory structure         | `plugins/myplugin/`                                                     |
| 2   | Create parent POM                         | `plugins/myplugin/pom.xml`                                              |
| 3   | Create core POM                           | `plugins/myplugin/core/pom.xml`                                         |
| 4   | Create postgres POM                       | `plugins/myplugin/postgres/pom.xml`                                     |
| 5   | Create PluginConfigurationImpl            | `core/.../MyPluginPluginConfigurationImpl.java`                         |
| 6   | Create RestModule                         | `core/.../guice/module/MyPluginRestModule.java`                         |
| 7   | Create LiquibaseModule                    | `core/.../guice/module/MyPluginLiquibaseModule.java`                    |
| 8   | Create PersistenceConfiguration interface | `core/.../persistence/MyPluginPersistenceConfiguration.java`            |
| 9   | Create DAO interface                      | `core/.../persistence/MyPluginDAO.java`                                 |
| 10  | Create REST resource                      | `core/.../rest/resource/MyPluginResource.java`                          |
| 11  | Create core changelog                     | `core/src/main/resources/liquibase/myplugin.changelog.xml`              |
| 12  | Create PostgresPersistenceConfiguration   | `postgres/...`                                                          |
| 13  | Create ServiceModule (DAO binding)        | `postgres/.../guice/module/MyPluginPostgresServiceModule.java`          |
| 14  | Create PersistenceModule (MyBatis)        | `postgres/.../guice/module/MyPluginPostgresPersistenceModule.java`      |
| 15  | Create Postgres LiquibaseModule           | `postgres/.../guice/module/MyPluginPostgresLiquibaseModule.java`        |
| 16  | Create DAO implementation                 | `postgres/.../dao/PostgresMyPluginDAO.java`                             |
| 17  | Create MyBatis mapper interface           | `postgres/.../dao/mapper/PostgresMyPluginMapper.java`                   |
| 18  | Create MyBatis mapper XML                 | `postgres/.../dao/mapper/PostgresMyPluginMapper.xml`                    |
| 19  | Create postgres changelog                 | `postgres/src/main/resources/liquibase/myplugin.postgres.changelog.xml` |
| 20  | Add module to `plugins/pom.xml`           | `<module>myplugin</module>`                                             |
| 21  | Add dependencies to `server/pom.xml`      | myplugin-core, myplugin-postgres                                        |
| 22  | Add to `server/build.properties`          | `plugin.myplugin.persistence.config.class=...`                          |
| 23  | Add to `server/conf/context.xml`          | `<Parameter name="plugin.myplugin..."/>`                                |
| 24  | Create AngularJS module                   | `src/main/webapp/myplugin.module.js`                                    |
| 25  | Create HTML views                         | `src/main/webapp/views/*.html`                                          |
| 26  | Create i18n files                         | `src/main/webapp/i18n/en_US.json`                                       |

---

## 9. Advanced Plugin Patterns (Optional)

### 9.1 Service Layer (Business Logic)

For complex plugins requiring business logic beyond simple CRUD operations, create a service layer between REST resources and DAOs:

**File:** `core/src/main/java/com/hmdm/plugins/myplugin/service/MyPluginService.java`

```java
package com.hmdm.plugins.myplugin.service;

import com.hmdm.plugins.myplugin.model.MyPluginData;
import com.hmdm.plugins.myplugin.persistence.MyPluginDAO;
import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public class MyPluginService {
    private final MyPluginDAO dao;

    @Inject
    public MyPluginService(MyPluginDAO dao) {
        this.dao = dao;
    }

    public MyPluginData processData(int customerId, int userId) {
        // Complex business logic here
        MyPluginData global = dao.getGlobalData(customerId);
        MyPluginData user = dao.getUserData(userId);
        return mergeData(global, user);
    }
}
```

**Usage:** Inject service into REST resources instead of DAO directly.

---

### 9.2 Device Sync Hooks

Integrate with device sync to automatically deliver plugin data to devices:

**File:** `core/src/main/java/com/hmdm/plugins/myplugin/sync/MyPluginSyncResponseHook.java`

```java
package com.hmdm.plugins.myplugin.sync;

import com.hmdm.rest.json.SyncResponseInt;
import com.hmdm.rest.json.SyncResponseHook;
import javax.inject.Singleton;

@Singleton
public class MyPluginSyncResponseHook implements SyncResponseHook {
    @Override
    public SyncResponseInt handle(int deviceId, SyncResponseInt original) {
        // Add plugin data to sync response
        original.addCustomData("myplugin", pluginData);
        return original;
    }
}
```

---

### 9.3 Background Tasks

Create periodic cleanup or maintenance tasks:

**File:** `core/src/main/java/com/hmdm/plugins/myplugin/task/MyPluginCleanupTask.java`

```java
package com.hmdm.plugins.myplugin.task;

import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public class MyPluginCleanupTask {
    public void cleanupExpiredData() {
        // Periodic cleanup logic
    }
}
```

---

### 9.4 Public REST Endpoints (Device Access)

Create public endpoints accessible without admin authentication:

**File:** `core/src/main/java/com/hmdm/plugins/myplugin/rest/resource/MyPluginPublicResource.java`

```java
@Path("/plugins/myplugin/public")
@Produces(MediaType.APPLICATION_JSON)
public class MyPluginPublicResource {
    @GET
    @Path("/data/{id}")
    public Response getData(@PathParam("id") int id,
                           @QueryParam("customerId") Integer customerId) {
        // Public endpoint logic
        return Response.OK(data);
    }
}
```

**Update RestModule:** Exclude public paths from auth filters:

```java
private static final List<String> protectedResources = 
    Arrays.asList("/rest/plugins/myplugin/private/*");
// Public paths (/public/*) are not in protectedResources
```

---

## 10. Build & Test

```bash
# From project root
mvn clean install

# Run with Tomcat
cd server
mvn tomcat7:run
```

## 11. Model Classes (Domain Objects)

### 10.1 Core Settings Model

**File:** `core/src/main/java/com/hmdm/plugins/myplugin/model/MyPluginSettings.java`

```java
package com.hmdm.plugins.myplugin.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;

import java.io.Serializable;

@ApiModel(description = "Plugin settings")
@JsonIgnoreProperties(ignoreUnknown = true)
public class MyPluginSettings implements Serializable {

    private static final long serialVersionUID = 1L;

    @ApiModelProperty("Settings ID")
    private Integer id;

    @ApiModelProperty("Customer ID")
    private int customerId;

    @ApiModelProperty("Setting value")
    private String settingValue;

    // Default constructor (required for serialization)
    public MyPluginSettings() {
    }

    // Getters and Setters
    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public int getCustomerId() {
        return customerId;
    }

    public void setCustomerId(int customerId) {
        this.customerId = customerId;
    }

    public String getSettingValue() {
        return settingValue;
    }

    public void setSettingValue(String settingValue) {
        this.settingValue = settingValue;
    }
}
```

### 10.2 Postgres Domain Object

**File:** `postgres/src/main/java/com/hmdm/plugins/myplugin/persistence/postgres/dao/domain/PostgresMyPluginSettings.java`

```java
package com.hmdm.plugins.myplugin.persistence.postgres.dao.domain;

import com.hmdm.plugins.myplugin.model.MyPluginSettings;

/**
 * Postgres-specific domain object extending core model.
 * MyBatis maps database results to this class.
 */
public class PostgresMyPluginSettings extends MyPluginSettings {

    private static final long serialVersionUID = 1L;

    public PostgresMyPluginSettings() {
        super();
    }
}
```

---

## 12. Complete DAO Implementation

### 12.1 Postgres DAO Implementation

**File:** `postgres/src/main/java/com/hmdm/plugins/myplugin/persistence/postgres/dao/PostgresMyPluginDAO.java`

```java
package com.hmdm.plugins.myplugin.persistence.postgres.dao;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.hmdm.persistence.AbstractDAO;
import com.hmdm.plugins.myplugin.model.MyPluginSettings;
import com.hmdm.plugins.myplugin.persistence.MyPluginDAO;
import com.hmdm.plugins.myplugin.persistence.postgres.dao.domain.PostgresMyPluginSettings;
import com.hmdm.plugins.myplugin.persistence.postgres.dao.mapper.PostgresMyPluginMapper;
import com.hmdm.security.SecurityException;
import org.mybatis.guice.transactional.Transactional;

/**
 * DAO for plugin settings backed by Postgres database.
 */
@Singleton
public class PostgresMyPluginDAO extends AbstractDAO<PostgresMyPluginSettings>
        implements MyPluginDAO {

    private final PostgresMyPluginMapper mapper;

    @Inject
    public PostgresMyPluginDAO(PostgresMyPluginMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public MyPluginSettings getPluginSettings() {
        return getSingleRecord(this.mapper::findPluginSettingsByCustomerId);
    }

    @Override
    @Transactional
    public void insertPluginSettings(MyPluginSettings settings) {
        PostgresMyPluginSettings postgresSettings = (PostgresMyPluginSettings) settings;
        insertRecord(postgresSettings, this.mapper::insertPluginSettings);
    }

    @Override
    @Transactional
    public void updatePluginSettings(MyPluginSettings settings) {
        PostgresMyPluginSettings postgresSettings = (PostgresMyPluginSettings) settings;
        updateRecord(
            postgresSettings,
            this.mapper::updatePluginSettings,
            s -> SecurityException.onCustomerDataAccessViolation(s.getId(), "pluginMyPluginSettings")
        );
    }
}
```

---

## 13. HTML View Templates

### 12.1 Main View

**File:** `src/main/webapp/views/main.html`

```html
<div ng-controller="PluginMyPluginTabController">
  <!-- Page Header -->
  <div class="row content-header">
    <div class="col-md-12">
      <h1>{{ 'plugin.myplugin.tab.title' | localize }}</h1>
    </div>
  </div>

  <!-- Error/Success Messages -->
  <div class="row" ng-if="errorMessage">
    <div class="col-md-12">
      <div class="alert alert-danger">{{ errorMessage }}</div>
    </div>
  </div>
  <div class="row" ng-if="successMessage">
    <div class="col-md-12">
      <div class="alert alert-success">{{ successMessage }}</div>
    </div>
  </div>

  <!-- Main Content -->
  <div class="row">
    <div class="col-md-12">
      <div class="panel panel-default">
        <div class="panel-heading">
          <h3 class="panel-title">
            {{ 'plugin.myplugin.tab.title' | localize }}
          </h3>
        </div>
        <div class="panel-body">
          <!-- Your plugin content here -->
          <p>Plugin data will be displayed here.</p>

          <div ng-if="data">
            <p><strong>Setting Value:</strong> {{ data.settingValue }}</p>
          </div>
        </div>
      </div>
    </div>
  </div>
</div>
```

### 12.2 Settings View

**File:** `src/main/webapp/views/settings.html`

```html
<div ng-controller="PluginMyPluginSettingsController">
  <!-- Page Header -->
  <div class="row content-header">
    <div class="col-md-12">
      <h1>{{ 'plugin.myplugin.settings.title' | localize }}</h1>
    </div>
  </div>

  <!-- Error/Success Messages -->
  <div class="row" ng-if="errorMessage">
    <div class="col-md-12">
      <div class="alert alert-danger">{{ errorMessage }}</div>
    </div>
  </div>
  <div class="row" ng-if="successMessage">
    <div class="col-md-12">
      <div class="alert alert-success">{{ successMessage }}</div>
    </div>
  </div>

  <!-- Settings Form -->
  <div class="row">
    <div class="col-md-6">
      <form name="settingsForm" ng-submit="save()">
        <div class="panel panel-default">
          <div class="panel-heading">
            <h3 class="panel-title">
              {{ 'plugin.myplugin.settings.title' | localize }}
            </h3>
          </div>
          <div class="panel-body">
            <div class="form-group">
              <label for="settingValue">Setting Value</label>
              <input
                type="text"
                class="form-control"
                id="settingValue"
                ng-model="settings.settingValue"
                placeholder="Enter setting value"
              />
            </div>
          </div>
          <div class="panel-footer">
            <button type="submit" class="btn btn-primary">
              <i class="fa fa-save"></i> Save
            </button>
          </div>
        </div>
      </form>
    </div>
  </div>
</div>
```

---

## 14. Frontend Deployment (How Files Are Served)

### How Frontend Files Get to the Browser

During the Maven build, plugin frontend files are **copied** from your plugin directory to the server's web resources:

| Source Location                                       | Deployed Location                                                     |
| ----------------------------------------------------- | --------------------------------------------------------------------- |
| `plugins/myplugin/src/main/webapp/myplugin.module.js` | `server/webtarget/app/components/plugins/myplugin/myplugin.module.js` |
| `plugins/myplugin/src/main/webapp/views/*.html`       | `server/webtarget/app/components/plugins/myplugin/views/*.html`       |
| `plugins/myplugin/src/main/webapp/i18n/*.json`        | `server/webtarget/app/components/plugins/myplugin/i18n/*.json`        |

### The Plugin Framework Loads Your Module

1. The server reads the `plugins` database table
2. For each enabled plugin, it loads the `javascriptModuleFile` path
3. Your AngularJS module is dynamically loaded
4. The `functionsViewTemplate` and `settingsViewTemplate` paths are used for views

### Plugin Registration in Database → Frontend Paths

```sql
INSERT INTO plugins (
    identifier,
    javascriptModuleFile,          -- 'app/components/plugins/myplugin/myplugin.module.js'
    functionsViewTemplate,          -- 'app/components/plugins/myplugin/views/main.html'
    settingsViewTemplate            -- 'app/components/plugins/myplugin/views/settings.html'
) VALUES (...);
```

> **IMPORTANT:** The paths in the database must match the deployed file locations exactly!

---

## 15. Common Errors & Troubleshooting

### Build Errors

| Error                                     | Cause                        | Solution                                              |
| ----------------------------------------- | ---------------------------- | ----------------------------------------------------- |
| `Package does not exist`                  | Missing import or dependency | Add dependency to core/postgres pom.xml               |
| `Cannot find symbol: PluginConfiguration` | Missing platform dependency  | Ensure `com.hmdm.plugin:platform:0.1.0` in parent pom |
| `Changelog parsing error`                 | Invalid Liquibase XML        | Check XML syntax, ensure valid changelog structure    |

### Runtime Errors

| Error                         | Cause                             | Solution                                                          |
| ----------------------------- | --------------------------------- | ----------------------------------------------------------------- |
| Plugin not appearing in menu  | Not registered in `plugins` table | Check core changelog ran, verify `SELECT * FROM plugins`          |
| "Permission denied" on plugin | Permission not granted to role    | Check `userRolePermissions` has your permission                   |
| Persistence layer not loading | Missing context.xml parameter     | Add `<Parameter name="plugin.myplugin.persistence.config.class">` |
| 500 error on REST call        | DAO not bound                     | Check ServiceModule binds interface to implementation             |
| "No qualifying bean"          | Guice injection failed            | Verify @Inject annotation and module binding                      |

### Database Errors

| Error                             | Cause                     | Solution                                        |
| --------------------------------- | ------------------------- | ----------------------------------------------- |
| `relation does not exist`         | Table not created         | Run postgres changelog, check for SQL errors    |
| `column does not exist`           | MyBatis mapping mismatch  | Match XML column names to actual DB columns     |
| `violates foreign key constraint` | Missing referenced record | Ensure customers record exists before inserting |

### Frontend Errors

| Error                    | Cause                  | Solution                                                              |
| ------------------------ | ---------------------- | --------------------------------------------------------------------- |
| Plugin view blank        | Controller not found   | Check controller name matches ng-controller                           |
| Translation keys showing | i18n not loaded        | Verify `localization.loadPluginResourceBundles("myplugin")` in .run() |
| 404 on view template     | Wrong path in database | Check `functionsViewTemplate` path matches deployed location          |
| `$injector:modulerr`     | AngularJS module error | Check module dependencies, syntax errors in JS                        |

### Debug Checklist

1. **Check database:**

   ```sql
   SELECT * FROM plugins WHERE identifier = 'myplugin';
   SELECT * FROM permissions WHERE name = 'plugin_myplugin_access';
   SELECT * FROM plugin_myplugin_settings;
   ```

2. **Check logs:**
   - Tomcat logs: `catalina.out` or console output
   - Look for Liquibase migration errors
   - Look for Guice injection errors

3. **Check browser:**
   - Open Developer Tools (F12)
   - Check Console for JavaScript errors
   - Check Network tab for 404/500 errors

---

## 16. Reference Plugins

| Plugin       | Description       | Use as Reference For                            |
| ------------ | ----------------- | ----------------------------------------------- |
| `devicelog`  | Device debug logs | Full-featured plugin with settings, rules, data |
| `deviceinfo` | Device info       | Simple data display plugin                      |
| `audit`      | Audit logging     | Simple logging plugin                           |
| `platform`   | Plugin framework  | **Do NOT modify** - core infrastructure         |

> **TIP:** Use the `devicelog` plugin as the primary reference implementation.

---

## 17. Quick Start Checklist (Copy & Rename)

For fastest development, copy an existing plugin and rename:

```bash
# From plugins directory
cp -r devicelog myplugin

# Then rename all files and update:
# 1. Package names: devicelog → myplugin
# 2. Class names: DeviceLog → MyPlugin
# 3. Database tables: plugin_devicelog_* → plugin_myplugin_*
# 4. REST paths: /plugins/devicelog/ → /plugins/myplugin/
# 5. Frontend: devicelog.module.js → myplugin.module.js
# 6. i18n keys: plugin.devicelog.* → plugin.myplugin.*
```
