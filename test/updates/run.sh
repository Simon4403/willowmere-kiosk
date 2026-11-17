#!/usr/bin/env bash
# Silent-update tests: the "on charge for 10 minutes" rules (ChargeWait) and
# download-then-verify (UpdateInstaller.download) against a local web server.
# Compiles the real kiosk code against Android's stub jar and runs it on a normal JVM.
#
#   bash test/updates/run.sh
#
# Needs: java 17+, git.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
KIOSK="$ROOT/app/src/main/java/com/heracaresolutions/kiosk"
WORK="${WORK:-$(mktemp -d)}"
ANDROID_JAR_SHA=262f6ae931160011572a5adfe4ec6302585e8f60  # github.com/Reginer/aosp-android-jar

if [ ! -f "$WORK/ajar/android-34/android.jar" ]; then
  git init -q "$WORK/ajar"
  git -C "$WORK/ajar" remote add origin https://github.com/Reginer/aosp-android-jar
  git -C "$WORK/ajar" sparse-checkout set --no-cone /android-34/android.jar
  git -C "$WORK/ajar" fetch -q --depth 1 --filter=blob:none origin "$ANDROID_JAR_SHA"
  git -C "$WORK/ajar" checkout -q FETCH_HEAD
fi
ANDROID_JAR="$WORK/ajar/android-34/android.jar"

mkdir -p "$WORK/bc/com/heracaresolutions/kiosk"
cat > "$WORK/bc/com/heracaresolutions/kiosk/BuildConfig.java" <<'J'
package com.heracaresolutions.kiosk;
public final class BuildConfig {
  public static final String DEFAULT_START_URL = "https://example.com/";
  public static final String DEFAULT_UPDATE_URL = "";
  public static final String VERSION_NAME = "1.0.test";
  public static final int VERSION_CODE = 1;
}
J
jc() { javac --release 17 -nowarn "$@" 2>&1 | grep -v -e '^Picked up' -e 'UnsupportedAppUsage' -e '^Note:' || true; }
jc -cp "$ANDROID_JAR" -d "$WORK/cls/kiosk" "$WORK/bc/com/heracaresolutions/kiosk/BuildConfig.java" "$KIOSK"/*.java
jc -d "$WORK/cls/shims" $(find "$ROOT/test/printer-sim/shims" -name '*.java')
jc -cp "$WORK/cls/shims:$WORK/cls/kiosk" -d "$WORK/cls/harness" "$HERE/harness/com/heracaresolutions/kiosk/UpdateHarness.java"
# Shims first so they stand in for the Android classes the code touches; verification off for the stub jar.
set +e +o pipefail
java -XX:+UnlockDiagnosticVMOptions -XX:-BytecodeVerificationRemote -XX:-BytecodeVerificationLocal \
  -cp "$WORK/cls/harness:$WORK/cls/shims:$WORK/cls/kiosk:$ANDROID_JAR" \
  com.heracaresolutions.kiosk.UpdateHarness > "$WORK/updates.log" 2>&1
STATUS=$?
grep -v '^Picked up' "$WORK/updates.log"
exit $STATUS
