#!/usr/bin/env bash
# Builds the Android "QR code provisioning" payload for a signed kiosk APK.
#   usage: make-provisioning.sh <signed.apk> <public APK download URL> [start URL] [output dir]
# Writes <out>/provisioning.json and <out>/provisioning-qr.png (needs qrencode).
set -euo pipefail

APK="$1"
APK_URL="$2"
START_URL="${3:-}"
OUT="${4:-dist}"
mkdir -p "$OUT"

# SHA-256 of the APK's signing certificate (Android checks the download against this).
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/usr/local/lib/android/sdk}}"
APKSIGNER="$(ls -d "$SDK"/build-tools/*/apksigner 2>/dev/null | sort -V | tail -1 || true)"
if [ -n "$APKSIGNER" ]; then
  HEX="$("$APKSIGNER" verify --print-certs "$APK" | grep -m1 'certificate SHA-256 digest' | awk -F': ' '{print $2}')"
else
  HEX="$(keytool -printcert -jarfile "$APK" | grep -m1 'SHA256:' | sed 's/.*SHA256: *//')"
fi
HEX="$(echo "$HEX" | tr -d ':[:space:]')"
if [ -z "$HEX" ]; then
  echo "Could not read the APK signing certificate" >&2
  exit 1
fi

python3 - "$APK_URL" "$HEX" "$START_URL" "$OUT/provisioning.json" <<'PY'
import base64, json, sys
apk_url, cert_hex, start_url, out = sys.argv[1:5]
checksum = base64.urlsafe_b64encode(bytes.fromhex(cert_hex)).decode().rstrip("=")
extras = {"update_url": apk_url}
if start_url:
    extras["start_url"] = start_url
payload = {
    "android.app.extra.PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME":
        "com.heracaresolutions.kiosk/com.heracaresolutions.kiosk.KioskAdminReceiver",
    "android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION": apk_url,
    "android.app.extra.PROVISIONING_DEVICE_ADMIN_SIGNATURE_CHECKSUM": checksum,
    "android.app.extra.PROVISIONING_LEAVE_ALL_SYSTEM_APPS_ENABLED": True,
    "android.app.extra.PROVISIONING_ADMIN_EXTRAS_BUNDLE": extras,
}
with open(out, "w") as f:
    json.dump(payload, f, separators=(",", ":"))
print(json.dumps(payload, indent=2))
PY

if command -v qrencode >/dev/null 2>&1; then
  qrencode -o "$OUT/provisioning-qr.png" -s 10 -m 4 -l M < "$OUT/provisioning.json"
  echo "QR code written to $OUT/provisioning-qr.png"
else
  echo "qrencode not installed - skipped the PNG (the JSON is still valid)" >&2
fi
