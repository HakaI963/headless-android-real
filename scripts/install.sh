#!/usr/bin/env bash
# Reliable install: Android cannot parse APKs directly from shared Downloads
# storage on this phone, so copy to /data/local/tmp first.
set -e
APK="${1:-app/build/outputs/apk/debug/app-debug.apk}"
TMP=/data/local/tmp/headless-android-real.apk
cp "$APK" "$TMP"
chmod 0644 "$TMP"
cmd package install -r "$TMP" || /system/bin/cmd package install -r "$TMP"
