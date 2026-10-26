#!/bin/sh
# Test parsera Yahoo bez Androida:  ./run.sh y-wig20-1m.json y-wig20-5m.json y-ten-5m.json y-gpp-5d.json
# Wymaga: kotlinc 1.9.x oraz android.jar (tylko do kompilacji; w czasie testu działają atrapy org.json i Log).
# Zmienne: KOTLINC (domyślnie kotlinc), ANDROID_JAR (ścieżka do android.jar)
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
KC=${KOTLINC:-kotlinc}
AJ=${ANDROID_JAR:?ustaw ANDROID_JAR=/sciezka/android.jar}
SRC="$HERE/../../app/src/main/java/pl/frutiger/tvhome"
W=${TMPDIR:-/tmp}/gpwtest
rm -rf "$W"; mkdir -p "$W/stub" "$W/app" "$W/shim" "$W/test"
printf 'package pl.frutiger.tvhome\nobject R { object anim { const val app_enter=1; const val home_exit=2 } }\n' > "$W/stub/R.kt"
$KC -jvm-target 1.8 -cp "$AJ" -d "$W/app" "$SRC"/*.kt "$W/stub/R.kt" 2>&1 | grep -i error || true
$KC -d "$W/shim" "$HERE/JsonShim.kt" "$HERE/LogShim.kt" 2>&1 | grep -i error || true
$KC -cp "$W/app" -d "$W/test" "$HERE/ParserTest.kt" 2>&1 | grep -i error || true
KH=$(dirname "$(dirname "$(command -v $KC)")")
java -cp "$W/shim:$W/test:$W/app:$KH/lib/kotlin-stdlib.jar" ParserTestKt "$@"
