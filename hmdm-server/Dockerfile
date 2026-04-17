# =============================================================================
# Stage 1: Android APK Build
# Builds the Brother Pharmamach MDM Android launcher APK using the Android SDK.
# =============================================================================
FROM eclipse-temurin:17-jdk AS android-builder

# Install Android SDK Command-line Tools
ENV ANDROID_HOME=/opt/android-sdk
ENV PATH=${PATH}:${ANDROID_HOME}/cmdline-tools/latest/bin:${ANDROID_HOME}/platform-tools:${ANDROID_HOME}/build-tools/35.0.0

RUN set -eux; \
        sed -i 's|http://archive.ubuntu.com/ubuntu|https://archive.ubuntu.com/ubuntu|g; s|http://security.ubuntu.com/ubuntu|https://security.ubuntu.com/ubuntu|g' /etc/apt/sources.list || true; \
        find /etc/apt/sources.list.d -type f \( -name '*.list' -o -name '*.sources' \) -exec \
            sed -i 's|http://archive.ubuntu.com/ubuntu|https://archive.ubuntu.com/ubuntu|g; s|http://security.ubuntu.com/ubuntu|https://security.ubuntu.com/ubuntu|g' {} + || true; \
        rm -rf /var/lib/apt/lists/*; \
        apt-get -o Acquire::ForceIPv4=true -o Acquire::Retries=5 -o Acquire::http::No-Cache=true -o Acquire::https::No-Cache=true update \
            || (rm -rf /var/lib/apt/lists/* && apt-get -o Acquire::ForceIPv4=true -o Acquire::Retries=5 update); \
        apt-get install -y --no-install-recommends wget unzip curl python3; \
        rm -rf /var/lib/apt/lists/*

# Download Android SDK command-line tools
RUN mkdir -p ${ANDROID_HOME}/cmdline-tools && \
    wget -q https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip -O /tmp/cmdtools.zip && \
    unzip -q /tmp/cmdtools.zip -d /tmp/cmdtools-extracted && \
    mv /tmp/cmdtools-extracted/cmdline-tools ${ANDROID_HOME}/cmdline-tools/latest && \
    rm -rf /tmp/cmdtools.zip /tmp/cmdtools-extracted

# Accept SDK licenses and install required components
RUN yes | sdkmanager --licenses > /dev/null 2>&1 || true && \
    sdkmanager "platform-tools" "platforms;android-35" "build-tools;35.0.0"

WORKDIR /android-build

# Copy Android project and keystore
COPY hmdm-android/ ./

# Keep the keystore bundled in hmdm-android/; do not overwrite it from repository root.
# We remove the org.gradle.java.home property because the Docker image uses a different Java path.
RUN sed -i '/org.gradle.java.home/d' gradle.properties || true

# Make gradlew executable and build the enterprise release APK
# Strip Windows CRLF line endings (gradlew checked out on Windows has \r in shebang,
# which causes Linux to report "./gradlew: not found" with exit code 127)
RUN sed -i 's/\r$//' gradlew && chmod +x gradlew && \
    ./gradlew assembleEnterpriseRelease --no-daemon --stacktrace -Dorg.gradle.jvmargs="-Xmx2g -Xms512m"

# Compute SHA-256 (URL-safe Base64) hash used by QR code and store to file
RUN APK_PATH="app/build/outputs/apk/enterprise/release/app-enterprise-release.apk" && \
    python3 -c "import hashlib,base64,sys; print(base64.urlsafe_b64encode(hashlib.sha256(open(sys.argv[1],'rb').read()).digest()).decode())" \
        "${APK_PATH}" > /android-build/apk_hash.txt && \
    echo "APK SHA-256 (URL-safe Base64): $(cat /android-build/apk_hash.txt)"

# =============================================================================
# Stage 2: Server Build — compile all Maven modules and produce launcher.war
# =============================================================================
FROM maven:3.8.8-eclipse-temurin-11 AS server-builder

RUN set -eux; \
        sed -i 's|http://archive.ubuntu.com/ubuntu|https://archive.ubuntu.com/ubuntu|g; s|http://security.ubuntu.com/ubuntu|https://security.ubuntu.com/ubuntu|g' /etc/apt/sources.list || true; \
        find /etc/apt/sources.list.d -type f \( -name '*.list' -o -name '*.sources' \) -exec \
            sed -i 's|http://archive.ubuntu.com/ubuntu|https://archive.ubuntu.com/ubuntu|g; s|http://security.ubuntu.com/ubuntu|https://security.ubuntu.com/ubuntu|g' {} + || true; \
        rm -rf /var/lib/apt/lists/*; \
        apt-get -o Acquire::ForceIPv4=true -o Acquire::Retries=5 -o Acquire::http::No-Cache=true -o Acquire::https::No-Cache=true update \
            || (rm -rf /var/lib/apt/lists/* && apt-get -o Acquire::ForceIPv4=true -o Acquire::Retries=5 update); \
        apt-get install -y --no-install-recommends git; \
        rm -rf /var/lib/apt/lists/*

WORKDIR /build

# Copy POM files first for better layer caching
COPY hmdm-server/pom.xml .
COPY hmdm-server/common/pom.xml             common/
COPY hmdm-server/jwt/pom.xml                jwt/
COPY hmdm-server/notification/pom.xml       notification/
COPY hmdm-server/plugins/pom.xml            plugins/

# Resolve plugin sub-module POMs
COPY hmdm-server/plugins/devicelog/pom.xml          plugins/devicelog/
COPY hmdm-server/plugins/devicelog/core/pom.xml     plugins/devicelog/core/
COPY hmdm-server/plugins/devicelog/postgres/pom.xml plugins/devicelog/postgres/
COPY hmdm-server/plugins/deviceinfo/pom.xml         plugins/deviceinfo/
COPY hmdm-server/plugins/audit/pom.xml              plugins/audit/
COPY hmdm-server/plugins/messaging/pom.xml          plugins/messaging/
COPY hmdm-server/plugins/xtra/pom.xml               plugins/xtra/
COPY hmdm-server/plugins/worktime/pom.xml           plugins/worktime/
COPY hmdm-server/plugins/worktime/core/pom.xml      plugins/worktime/core/
COPY hmdm-server/plugins/worktime/postgres/pom.xml  plugins/worktime/postgres/
COPY hmdm-server/plugins/calllog/pom.xml            plugins/calllog/
COPY hmdm-server/plugins/calllog/core/pom.xml       plugins/calllog/core/
COPY hmdm-server/plugins/calllog/postgres/pom.xml   plugins/calllog/postgres/
COPY hmdm-server/plugins/push/pom.xml               plugins/push/
COPY hmdm-server/plugins/platform/pom.xml           plugins/platform/
COPY hmdm-server/swagger/ui/pom.xml                 swagger/ui/
COPY hmdm-server/server/pom.xml                     server/

# Pre-download dependencies (cached as a layer unless POMs change)
RUN mvn dependency:go-offline -B --fail-never || true

# Copy full server source
COPY hmdm-server/ .

# Generate build.properties with placeholder values for Maven filtering.
# The real runtime values come from environment variables at startup (entrypoint.sh).
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
RUN rm -rf /root/.m2/repository/io/github/classgraph
RUN mvn install -DskipTests -U -B

# =============================================================================
# Stage 3: Runtime — Tomcat 9 + JRE 11
# =============================================================================
# FROM tomcat:9.0-jre11
FROM tomcat:9.0

LABEL maintainer="Brother Pharmamach DevOps" \
      description="Brother Pharmamach MDM Server (Headwind MDM fork)" \
      version="1.0.0"

# Install aapt (APK analysis) + postgresql-client (pg_isready) + python3 (hash)
RUN set -eux; \
        sed -i 's|http://archive.ubuntu.com/ubuntu|https://archive.ubuntu.com/ubuntu|g; s|http://security.ubuntu.com/ubuntu|https://security.ubuntu.com/ubuntu|g' /etc/apt/sources.list || true; \
        find /etc/apt/sources.list.d -type f \( -name '*.list' -o -name '*.sources' \) -exec \
            sed -i 's|http://archive.ubuntu.com/ubuntu|https://archive.ubuntu.com/ubuntu|g; s|http://security.ubuntu.com/ubuntu|https://security.ubuntu.com/ubuntu|g' {} + || true; \
        rm -rf /var/lib/apt/lists/*; \
        apt-get -o Acquire::ForceIPv4=true -o Acquire::Retries=5 -o Acquire::http::No-Cache=true -o Acquire::https::No-Cache=true update \
            || (rm -rf /var/lib/apt/lists/* && apt-get -o Acquire::ForceIPv4=true -o Acquire::Retries=5 update); \
        apt-get install -y --no-install-recommends curl aapt postgresql-client python3; \
        rm -rf /var/lib/apt/lists/*

# Remove default Tomcat webapps
RUN rm -rf /usr/local/tomcat/webapps/*

# Copy the built WAR (from server-builder stage)
COPY --from=server-builder /build/server/target/launcher.war /usr/local/tomcat/webapps/ROOT.war

# Copy SQL init script
COPY --from=server-builder /build/install/sql/hmdm_init.en.sql /opt/hmdm-setup/hmdm_init.en.sql

# Copy the freshly built Brother Pharmamach MDM APK (from android-builder stage)
COPY --from=android-builder \
    /android-build/app/build/outputs/apk/enterprise/release/app-enterprise-release.apk \
    /opt/hmdm-setup/brother-pharmach-mdm.apk

# Copy precomputed APK hash file (used by entrypoint to seed/update DB)
COPY --from=android-builder /android-build/apk_hash.txt /opt/hmdm-setup/apk_hash.txt

# Copy Docker support scripts
COPY hmdm-server/docker/entrypoint.sh  /docker-entrypoint.sh
COPY hmdm-server/docker/log4j-hmdm.xml /opt/hmdm-setup/log4j-hmdm.xml

# Create base directories and ensure correct permissions
# Strip Windows CRLF line endings from entrypoint script (checked out on Windows has \r in shebang,
# which causes Linux to report "no such file or directory" when trying to execute)
RUN mkdir -p /opt/hmdm/files /opt/hmdm/plugins /opt/hmdm/logs \
             /usr/local/tomcat/conf/Catalina/localhost \
    && sed -i 's/\r$//' /docker-entrypoint.sh \
    && chmod +x /docker-entrypoint.sh

# Create a non-root system user for Tomcat
RUN groupadd --system hmdm && useradd --system --gid hmdm --no-create-home hmdm \
    && chown -R hmdm:hmdm /opt/hmdm \
    && chown -R hmdm:hmdm /opt/hmdm-setup \
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
