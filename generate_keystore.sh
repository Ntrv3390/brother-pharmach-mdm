#!/bin/bash
set -e

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
KEYSTORE_PATH="${KEYSTORE_PATH:-$ROOT_DIR/hmdm-android/brother-pharmach-release.jks}"
KEY_ALIAS="${KEY_ALIAS:-brotherpharmach}"
KEY_VALIDITY_DAYS="${KEY_VALIDITY_DAYS:-10000}"

if [ ! -f "$KEYSTORE_PATH" ]; then
    echo "Keystore not found. Generating..."
        if [ -z "${STORE_PASSWORD:-}" ] || [ -z "${KEY_PASSWORD:-}" ] || [ -z "${KEY_DNAME:-}" ]; then
                echo "Error: set STORE_PASSWORD, KEY_PASSWORD, and KEY_DNAME before running this script."
                echo "Example KEY_DNAME: CN=BrotherPharmachMDM,OU=IT,O=BrotherPharmach,L=City,S=State,C=US"
                exit 1
        fi

    keytool -genkeypair -v -keystore "$KEYSTORE_PATH" \
            -keyalg RSA -keysize 2048 -validity "$KEY_VALIDITY_DAYS" \
            -alias "$KEY_ALIAS" -storepass "$STORE_PASSWORD" -keypass "$KEY_PASSWORD" \
            -dname "$KEY_DNAME"
    
    if [ -f "$KEYSTORE_PATH" ]; then
        echo "Keystore successfully created at $KEYSTORE_PATH"
    else
        echo "Failed to create keystore."
        exit 1
    fi
else
    echo "Keystore already exists at $KEYSTORE_PATH"
fi
