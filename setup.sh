#!/usr/bin/env bash
# Installs Hafen on one phone over adb.
#
#   ./setup.sh <adb-serial>          soft mode: works on any phone, accounts stay (recommended)
#   ./setup.sh <adb-serial> --owner  Device Owner: strongest, but the phone must have NO accounts
#
# List serials with: adb devices
set -euo pipefail

SERIAL="${1:-}"
MODE="${2:-}"
if [[ -z "$SERIAL" ]]; then
  echo "Usage: ./setup.sh <adb-serial> [--owner]"; adb devices -l; exit 1
fi
export ANDROID_SERIAL="$SERIAL"
PKG=dev.anchor
ADMIN="$PKG/.enforce.AdminReceiver"
APK=app/build/outputs/apk/debug/app-debug.apk
cd "$(dirname "$0")"

echo "== Device: $(adb shell getprop ro.product.model | tr -d '\r'), Android $(adb shell getprop ro.build.version.release | tr -d '\r')"

echo "== Building"
./gradlew -q :app:assembleDebug

echo "== Installing"
adb install -t -r "$APK" | tail -1

if [[ "$MODE" == "--owner" ]]; then
  if adb shell dpm list-owners | grep -q "$PKG"; then
    echo "== Hafen is already Device Owner"
  else
    ACCOUNTS=$(adb shell dumpsys account | grep -cE '^\s+Account \{' || true)
    USERS=$(adb shell pm list users | grep -c 'UserInfo{' || true)
    if [[ "$ACCOUNTS" -gt 0 || "$USERS" -gt 1 ]]; then
      echo "!! Android only allows a Device Owner when the phone has no accounts and one user."
      echo "   Accounts: $ACCOUNTS   Users: $USERS"
      echo "   Remove them (Settings > Passwords & accounts), run this again, then sign back in."
      echo "   Or run without --owner for soft mode."
      exit 2
    fi
    echo "== Making Hafen Device Owner"
    adb shell dpm set-device-owner "$ADMIN"
  fi
else
  echo "== Soft mode: device admin (Android then refuses to uninstall Hafen until it's deactivated)"
  adb shell dpm set-active-admin "$ADMIN" || true
fi

echo "== Permissions"
adb shell pm grant "$PKG" android.permission.WRITE_SECURE_SETTINGS        # grayscale, keeping the watcher on
adb shell cmd notification allow_listener "$PKG/$PKG.service.NotifyService" # notification filter
adb shell appops set "$PKG" GET_USAGE_STATS allow                           # statistics

echo "== Starting Hafen"
adb shell am start -n "$PKG/.ui.MainActivity" >/dev/null
sleep 4
echo "Watcher: $(adb shell settings get secure enabled_accessibility_services | tr -d '\r')"
echo "Owners:  $(adb shell dpm list-owners | tr -d '\r' | tail -1)"
echo
echo "Done. Open Hafen > Settings > Protection status: everything should be green."
echo "This is a test-only build: 'adb shell dpm remove-active-admin $ADMIN' then 'adb uninstall $PKG' removes it again."
