#!/usr/bin/env bash
# Builds the Android "QR code provisioning" payload for a signed kiosk APK.
#   usage: make-provisioning.sh <signed.apk> <public APK download URL> [start URL] [output dir]
# Writes <out>/provisioning.json and <out>/provisioning-qr.png (needs qrencode).
set -euo pipefail
# Surface any failure as a GitHub annotation (visible without opening the log).
trap 'echo "::error file=scripts/make-provisioning.sh,line=$LINENO::Failed: $BASH_COMMAND"' ERR

APK="$1"
APK_URL="$2"
START_URL="${3:-}"
OUT="${4:-dist}"
mkdir -p "$OUT"

# SHA-256 of the APK's signing certificate (Android checks the download against this).
# Output is captured whole before parsing, so an early-exiting grep can't SIGPIPE the producer under pipefail.
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/usr/local/lib/android/sdk}}"
APKSIGNER=""
for candidate in "$SDK"/build-tools/*/apksigner; do
  [ -x "$candidate" ] && APKSIGNER="$candidate"   # glob sorts ascending, so the newest wins
done

HEX=""
if [ -n "$APKSIGNER" ]; then
  echo "Reading certificate with $APKSIGNER"
  CERT_OUT="$("$APKSIGNER" verify --print-certs "$APK" 2>&1 || true)"
  echo "$CERT_OUT"
  # Formats vary by version ("Signer #1 certificate SHA-256 digest: …" or "V2 Signer: certificate SHA-256 digest: …"),
  # so take the 64-hex-digit value after the label rather than a fixed field.
  HEX="$(printf '%s\n' "$CERT_OUT" | sed -n 's/.*certificate SHA-256 digest: *\([0-9A-Fa-f]\{64\}\).*/\1/p' | sed -n '1p')"
fi

# Fallback: the signing keystore itself (same certificate as the APK).
if [ -z "$HEX" ] && [ -n "${KIOSK_KEYSTORE_PATH:-}" ] && [ -f "${KIOSK_KEYSTORE_PATH}" ]; then
  echo "Reading certificate from the keystore instead"
  KS_OUT="$(keytool -list -v -keystore "$KIOSK_KEYSTORE_PATH" -storepass "${KIOSK_KEYSTORE_PASSWORD:-}" -alias "${KIOSK_KEY_ALIAS:-kiosk}" 2>&1 || true)"
  HEX="$(printf '%s\n' "$KS_OUT" | awk '/SHA256:/ {sub(/.*SHA256: */, ""); print; exit}')"
fi

HEX="$(printf '%s' "$HEX" | tr -d ':[:space:]')"
if ! printf '%s' "$HEX" | grep -Eq '^[0-9A-Fa-f]{64}$'; then
  echo "::error::Could not read the APK signing certificate. apksigner=[$APKSIGNER] got=[$HEX] out=[$(printf '%s' "${CERT_OUT:-}${KS_OUT:-}" | head -c 300 | tr '\n' ' ')]"
  exit 1
fi
echo "::notice::Signing certificate SHA-256: $HEX"

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
elif python3 -c "import qrcode" 2>/dev/null; then
  python3 -c "import qrcode,sys; qrcode.make(open(sys.argv[1]).read(), box_size=10, border=4).save(sys.argv[2])" \
    "$OUT/provisioning.json" "$OUT/provisioning-qr.png"
else
  echo "No QR generator available (install qrencode or python qrcode)" >&2
  exit 1
fi
echo "QR code written to $OUT/provisioning-qr.png"
