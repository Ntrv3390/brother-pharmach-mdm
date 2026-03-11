# =============================================================================
# Stage 1: Build – compile all Maven modules and produce launcher.war
# =============================================================================
FROM maven:3.8.8-eclipse-temurin-8 AS builder

WORKDIR /build

# Copy POM files first for better layer caching
COPY pom.xml .
COPY common/pom.xml             common/
COPY jwt/pom.xml                jwt/
COPY notification/pom.xml       notification/
COPY plugins/pom.xml            plugins/

# Resolve plugin sub-module POMs (sub-modules are nested inside parent plugin dirs)
COPY plugins/devicelog/pom.xml          plugins/devicelog/
COPY plugins/devicelog/core/pom.xml     plugins/devicelog/core/
COPY plugins/devicelog/postgres/pom.xml plugins/devicelog/postgres/
COPY plugins/deviceinfo/pom.xml         plugins/deviceinfo/
COPY plugins/audit/pom.xml              plugins/audit/
COPY plugins/messaging/pom.xml          plugins/messaging/
COPY plugins/xtra/pom.xml               plugins/xtra/
COPY plugins/worktime/pom.xml           plugins/worktime/
COPY plugins/worktime/core/pom.xml      plugins/worktime/core/
COPY plugins/worktime/postgres/pom.xml  plugins/worktime/postgres/
COPY plugins/calllog/pom.xml            plugins/calllog/
COPY plugins/calllog/core/pom.xml       plugins/calllog/core/
COPY plugins/calllog/postgres/pom.xml   plugins/calllog/postgres/
COPY plugins/push/pom.xml               plugins/push/
COPY plugins/platform/pom.xml           plugins/platform/
COPY swagger/ui/pom.xml                 swagger/ui/
COPY server/pom.xml                     server/

# Pre-download dependencies (cached as a layer unless POMs change)
RUN mvn dependency:go-offline -B --fail-never || true

# Copy full source
COPY . .

# Generate a build.properties with placeholder values for Maven filtering.
# The real runtime values come from environment variables at startup (entrypoint.sh).
# These placeholder values are only used so the build succeeds.
RUN cat > server/build.properties << 'EOF'
jdbc.url=jdbc:postgresql://placeholder:5432/hmdm
jdbc.driver=org.postgresql.Driver
jdbc.username=hmdm
jdbc.password=placeholder
base.directory=/opt/hmdm
files.directory=/opt/hmdm/files
plugins.files.directory=/opt/hmdm/plugins
base.url=http://localhost:8080
usage.scenario=private
secure.enrollment=0
hash.secret=changeme-C3z9vi54
role.orgadmin.id=2
initialization.completion.signal.file=/opt/hmdm/hmdm_install_flag
aapt.command=aapt
log4j.config=file:///opt/hmdm/log4j-hmdm.xml
plugin.devicelog.persistence.config.class=com.hmdm.plugins.devicelog.persistence.postgres.DeviceLogPostgresPersistenceConfiguration
plugin.worktime.persistence.config.class=com.hmdm.plugins.worktime.persistence.postgres.WorkTimePostgresPersistenceConfiguration
plugin.calllog.persistence.config.class=com.hmdm.plugins.calllog.persistence.postgres.CallLogPostgresPersistenceConfiguration
mqtt.server.uri=localhost:31000
mqtt.external=0
mqtt.client.tag=
mqtt.auth=1
mqtt.message.delay=0
device.fast.search.chars=5
plugin.photo.enable.places=0
plugin.audit.display.forwarded.ip=0
smtp.host=
smtp.port=25
smtp.ssl=0
smtp.starttls=0
smtp.username=
smtp.password=
smtp.from=
sql.init.script.path=
jwt.secretkey=20c68f0d9185b1d18cf6add1e8b491fd89529a44
jwt.validity=86400
jwt.validityrememberme=2592000
EOF

# Build (skip tests to speed up)
RUN mvn install -DskipTests -B

# =============================================================================
# Stage 2: Runtime – Tomcat 9 + JRE 8
# =============================================================================
FROM tomcat:9.0-jre8

LABEL maintainer="Brother Pharmach DevOps" \
      description="Brother Pharmach MDM Server (Headwind MDM fork)" \
      version="0.1.0"

# Install aapt (required for APK analysis) and postgresql-client (pg_isready)
RUN apt-get update && apt-get install -y --no-install-recommends \
    curl \
        aapt \
        postgresql-client \
    && rm -rf /var/lib/apt/lists/*

# Remove default Tomcat webapps to keep image clean
RUN rm -rf /usr/local/tomcat/webapps/*

# Copy the built WAR
COPY --from=builder /build/server/target/launcher.war /usr/local/tomcat/webapps/ROOT.war

# Copy SQL init script for first-run database seeding
COPY --from=builder /build/install/sql/hmdm_init.en.sql /opt/hmdm-setup/hmdm_init.en.sql

# Copy Docker support scripts
COPY docker/entrypoint.sh       /docker-entrypoint.sh
COPY docker/log4j-hmdm.xml      /opt/hmdm-setup/log4j-hmdm.xml

# Create base directories and ensure correct permissions
RUN mkdir -p /opt/hmdm/files /opt/hmdm/plugins /opt/hmdm/logs \
             /usr/local/tomcat/conf/Catalina/localhost \
    && chmod +x /docker-entrypoint.sh

# Create a non-root system user for Tomcat
RUN groupadd --system hmdm && useradd --system --gid hmdm --no-create-home hmdm \
    && chown -R hmdm:hmdm /opt/hmdm \
    && chown -R hmdm:hmdm /usr/local/tomcat/logs \
    && chown -R hmdm:hmdm /usr/local/tomcat/work \
    && chown -R hmdm:hmdm /usr/local/tomcat/temp \
    && chown -R hmdm:hmdm /usr/local/tomcat/conf \
    && chown -R hmdm:hmdm /usr/local/tomcat/webapps

USER hmdm

# Ports:
#   8080  – Tomcat HTTP (MDM web panel + REST API)
#   31000 – Embedded MQTT broker (push notifications to Android devices)
EXPOSE 8080 31000

ENTRYPOINT ["/docker-entrypoint.sh"]
