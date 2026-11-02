#!/usr/bin/env bash
# Virtual printer tests.
#
# Runs the kiosk's real printing code (Ipp, PrinterService, Prefs, KioskBridge,
# PwgRaster) on a normal JVM against a farm of simulated network printers.
# Android framework classes the code touches are replaced by small stand-ins
# (shims/); org.json is Android's own source. Nothing here needs a tablet.
#
#   bash test/printer-sim/run.sh            # everything (~4 minutes)
#   bash test/printer-sim/run.sh probe      # one group: parse local describe probe discover print moved bridge
#
# Needs: java 17+, python3, git, openssl. Binds 127.0.0.2-7 port 631, so uses sudo when not root.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
KIOSK="$ROOT/app/src/main/java/com/heracaresolutions/kiosk"
WORK="${WORK:-$(mktemp -d)}"
GROUP="${1:-all}"

IPP_SERVER_SHA=3e4a6d8aad4409c280c6df2d1718b3803e38e813   # github.com/h2g2bob/ipp-server (independent IPP printer)
ANDROID_JAR_SHA=262f6ae931160011572a5adfe4ec6302585e8f60  # github.com/Reginer/aosp-android-jar (android-34 stubs + org.json source)

fetch_at() {  # fetch_at <url> <dir> <sha> [sparse paths...]
  local url=$1 dir=$2 sha=$3; shift 3
  [ -d "$dir/.git" ] && return 0
  git init -q "$dir"
  git -C "$dir" remote add origin "$url"
  if [ $# -gt 0 ]; then
    git -C "$dir" sparse-checkout set --no-cone "$@"
    git -C "$dir" fetch -q --depth 1 --filter=blob:none origin "$sha"
  else
    git -C "$dir" fetch -q --depth 1 origin "$sha"
  fi
  git -C "$dir" checkout -q FETCH_HEAD
}

echo "work dir: $WORK"
fetch_at https://github.com/h2g2bob/ipp-server "$WORK/ipp-server" "$IPP_SERVER_SHA"
fetch_at https://github.com/Reginer/aosp-android-jar "$WORK/ajar" "$ANDROID_JAR_SHA" \
  /android-34/android.jar /android-34/src/org/json/
ANDROID_JAR="$WORK/ajar/android-34/android.jar"

# Android's org.json, minus its Android-only annotations.
mkdir -p "$WORK/jsonsrc/org/json" "$WORK/cls"/{json,shims,kiosk,fake,harness,pwg} "$WORK/bc/com/heracaresolutions/kiosk" "$WORK/jobs"
for f in "$WORK"/ajar/android-34/src/org/json/*.java; do
  sed -E -e '/^import (android\.annotation|android\.compat|libcore\.util|static android\.annotation)/d' \
         -e 's/@UnsupportedAppUsage(\([^)]*\))?//g' -e 's/@(NonNull|Nullable)\b//g' -e 's/@SystemApi(\([^)]*\))?//g' \
         "$f" > "$WORK/jsonsrc/org/json/$(basename "$f")"
done
cat > "$WORK/bc/com/heracaresolutions/kiosk/BuildConfig.java" <<'EOF'
package com.heracaresolutions.kiosk;
public final class BuildConfig {
  public static final String DEFAULT_START_URL = "https://hera.example.local/";
  public static final String DEFAULT_UPDATE_URL = "";
  public static final String VERSION_NAME = "1.0.test";
  public static final int VERSION_CODE = 1;
}
EOF

jc() { javac --release 17 -nowarn "$@" 2>&1 | grep -v -e '^Picked up' -e 'UnsupportedAppUsage' -e '^Note:' || true; }
jc -d "$WORK/cls/json" "$WORK"/jsonsrc/org/json/*.java
jc -d "$WORK/cls/shims" $(find "$HERE/shims" -name '*.java')
jc -cp "$ANDROID_JAR" -d "$WORK/cls/kiosk" "$WORK/bc/com/heracaresolutions/kiosk/BuildConfig.java" "$KIOSK"/*.java
jc -cp "$WORK/cls/shims:$WORK/cls/kiosk" -d "$WORK/cls/fake" "$HERE/fake/com/heracaresolutions/kiosk/KioskActivity.java"
jc -cp "$WORK/cls/fake:$WORK/cls/shims:$WORK/cls/json:$WORK/cls/kiosk" -d "$WORK/cls/harness" "$HERE/harness/com/heracaresolutions/kiosk/PrinterHarness.java"
jc -cp "$WORK/cls/kiosk" -d "$WORK/cls/pwg" "$HERE/pwg/PwgGen.java"
[ -f "$WORK/cls/harness/com/heracaresolutions/kiosk/PrinterHarness.class" ] || { echo "compile failed"; exit 1; }

# A self-signed certificate for the simulated ipps:// printers.
openssl req -x509 -newkey rsa:2048 -nodes -keyout "$WORK/key.pem" -out "$WORK/cert.pem" -days 2 \
  -subj "/CN=Simulated Printer" >/dev/null 2>&1

# A small two-page A4 landscape PDF to print.
python3 -I - "$WORK/test.pdf" <<'PY'
import sys
objs = ["<< /Type /Catalog /Pages 2 0 R >>", "<< /Type /Pages /Kids [3 0 R 5 0 R] /Count 2 >>"]
for pg, txt in [(3, "Medication round - page 1"), (5, "Medication round - page 2")]:
    objs.append("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 842 595] /Contents %d 0 R /Resources << /Font << /F1 7 0 R >> >> >>" % (pg + 1))
    s = "BT /F1 24 Tf 72 500 Td (%s) Tj ET" % txt
    objs.append("<< /Length %d >>\nstream\n%s\nendstream" % (len(s), s))
objs.append("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>")
out, offs = b"%PDF-1.4\n", []
for n, s in enumerate(objs, 1):
    offs.append(len(out)); out += ("%d 0 obj\n%s\nendobj\n" % (n, s)).encode()
x = len(out); out += ("xref\n0 %d\n0000000000 65535 f \n" % (len(objs) + 1)).encode()
for f in offs: out += ("%010d 00000 n \n" % f).encode()
out += ("trailer\n<< /Size %d /Root 1 0 R >>\nstartxref\n%d\n%%%%EOF\n" % (len(objs) + 1, x)).encode()
open(sys.argv[1], "wb").write(out)
PY

# Start the printer farm.
SUDO=""; [ "$(id -u)" = 0 ] || SUDO="sudo"
$SUDO python3 -I "$HERE/farm.py" "$WORK/ipp-server" "$WORK/jobs" "$WORK/cert.pem" "$WORK/key.pem" > "$WORK/farm.log" 2>&1 &
FARM=$!
trap '$SUDO kill $FARM 2>/dev/null || true; $SUDO pkill -f "$HERE/farm.py" 2>/dev/null || true' EXIT
for _ in $(seq 1 40); do grep -q "FARM READY" "$WORK/farm.log" 2>/dev/null && break; sleep 0.25; done
grep -q "FARM READY" "$WORK/farm.log" || { cat "$WORK/farm.log"; echo "printer farm didn't start"; exit 1; }

CP="$WORK/cls/harness:$WORK/cls/fake:$WORK/cls/shims:$WORK/cls/json:$WORK/cls/kiosk:$ANDROID_JAR"
STATUS=0
# Verification off: the android.jar stubs have no method bodies, and the Android-only
# code paths that would load them are never run here.
set +e
java -XX:+UnlockDiagnosticVMOptions -XX:-BytecodeVerificationRemote -XX:-BytecodeVerificationLocal \
  -cp "$CP" com.heracaresolutions.kiosk.PrinterHarness "$WORK/jobs" "$WORK/test.pdf" "$GROUP" > "$WORK/harness.log" 2>&1
RC=$?
set -e
grep -v -e '^Picked up' -e 'OpenJDK 64-Bit Server VM warning' "$WORK/harness.log" || true
[ "$RC" = 0 ] || STATUS=1

if [ "$GROUP" = all ] || [ "$GROUP" = pwg ]; then
  echo; echo "== PWG raster encoder, checked by an independent decoder"
  mkdir -p "$WORK/pwgout"
  java -cp "$WORK/cls/pwg:$WORK/cls/kiosk" com.heracaresolutions.kiosk.PwgGen "$WORK/pwgout" 2>&1 | grep -v '^Picked up' || true
  OUT="$(python3 -I "$HERE/pwgdecode.py" "$WORK/pwgout")"
  echo "$OUT"
  echo "$OUT" | grep -q "BAD\|DIFFERENT" && STATUS=1
fi
exit $STATUS
