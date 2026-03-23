#!/bin/bash
set -euo pipefail

# Usage:
#   ANDROID_SERIAL=192.168.0.98:38999 ./set_key.sh antfarm_key_here ant-farm-management
#   ./set_key.sh antfarm_key_here ant-farm-management
#
# Seeds the ClawWatch phone companion transport settings through the legacy
# prefs file. The app migrates them into encrypted prefs on launch.

KEY="${1:-}"
ROOMS="${2:-ant-farm-management}"
PKG="com.thinkoff.clawwatch"
PREFS_NAME="clawwatch_prefs"

if [ -z "$KEY" ]; then
  echo "Usage: ./set_key.sh antfarm_key [room_slug_or_csv]"
  exit 1
fi

if ! echo "$KEY" | grep -qE '^[A-Za-z0-9_-]+$'; then
  echo "Error: invalid key format"
  exit 1
fi

if ! echo "$ROOMS" | grep -qE '^[A-Za-z0-9,._-]+$'; then
  echo "Error: invalid room list format"
  exit 1
fi

TMP=$(mktemp /tmp/clawwatch_phone_prefs_XXXXXX.xml)
cat > "$TMP" <<XMLEOF
<?xml version="1.0" encoding="utf-8" standalone="yes" ?>
<map>
    <string name="antfarm_api_key">${KEY}</string>
    <string name="antfarm_rooms">${ROOMS}</string>
</map>
XMLEOF

echo "Pushing ClawWatch phone key to device..."
adb push "$TMP" /data/local/tmp/clawwatch_phone_tmp.xml >/dev/null
adb shell "run-as $PKG sh -c 'mkdir -p /data/data/$PKG/shared_prefs && cp /data/local/tmp/clawwatch_phone_tmp.xml /data/data/$PKG/shared_prefs/${PREFS_NAME}.xml'"
adb shell "rm /data/local/tmp/clawwatch_phone_tmp.xml"
rm "$TMP"

echo "Done. Restart ClawWatch on the device."
