#!/bin/bash
set -euo pipefail

cd /workspace

./gradlew clean bundleEnterpriseRelease assembleEnterpriseRelease \
  -Dorg.gradle.java.home=/opt/java/openjdk \
  -PRELEASE_STORE_FILE=../brother-pharmach-release.jks \
  -PRELEASE_STORE_PASSWORD='BrotherPharmachMDM2026@2026' \
  -PRELEASE_KEY_ALIAS=brotherpharmach \
  -PRELEASE_KEY_PASSWORD='BrotherPharmachMDM2026@2026'

APK="app/build/outputs/apk/enterprise/release/app-enterprise-release.apk"
AAB="app/build/outputs/bundle/enterpriseRelease/app-enterprise-release.aab"
APKSIGNER="${ANDROID_HOME}/build-tools/35.0.0/apksigner"

test -f "${APK}"
test -f "${AAB}"

"${APKSIGNER}" verify --print-certs "${APK}" | tee /tmp/apk_cert.txt

curl -sSL -o /tmp/bundletool.jar \
  https://github.com/google/bundletool/releases/download/1.17.2/bundletool-all-1.17.2.jar

java -jar /tmp/bundletool.jar build-apks \
  --bundle="${AAB}" \
  --output=/tmp/release.apks \
  --mode=universal \
  --ks=brother-pharmach-release.jks \
  --ks-key-alias=brotherpharmach \
  --ks-pass=pass:BrotherPharmachMDM2026@2026 \
  --key-pass=pass:BrotherPharmachMDM2026@2026

unzip -p /tmp/release.apks universal.apk > /tmp/universal.apk
"${APKSIGNER}" verify --print-certs /tmp/universal.apk | tee /tmp/universal_cert.txt

APK_SHA="$(grep -m1 'SHA-256 digest' /tmp/apk_cert.txt | awk -F': ' '{print $2}')"
UNI_SHA="$(grep -m1 'SHA-256 digest' /tmp/universal_cert.txt | awk -F': ' '{print $2}')"

echo "APK_SHA256=${APK_SHA}" | tee /out/signing-verification.txt
echo "AAB_UNIVERSAL_APK_SHA256=${UNI_SHA}" | tee -a /out/signing-verification.txt

if [ "${APK_SHA}" = "${UNI_SHA}" ]; then
  echo "MATCH=true" | tee -a /out/signing-verification.txt
else
  echo "MATCH=false" | tee -a /out/signing-verification.txt
  exit 2
fi

cp "${APK}" /out/app-enterprise-release.apk
cp "${AAB}" /out/app-enterprise-release.aab
cp brother-pharmach-release.jks /out/brother-pharmach-release.jks
cp /tmp/apk_cert.txt /out/apk-cert.txt
cp /tmp/universal_cert.txt /out/universal-apk-from-aab-cert.txt

echo "Artifacts and verification files exported to /out"