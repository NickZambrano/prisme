#!/usr/bin/env bash
# Compile l'APK release de FluxFR avec les outils installés dans ~/.androidtv-build
set -e
T="$HOME/.androidtv-build"
mkdir -p "$T/tmp" "$T/out"
export JAVA_HOME="$T/jdk-17.0.21+9"
export PATH="$JAVA_HOME/bin:$PATH"
export TMP="$(cygpath -w "$T/tmp" 2>/dev/null || echo "$T/tmp")" TEMP="$TMP"
export JAVA_TOOL_OPTIONS="-Djdk.net.unixdomain.tmpdir=$(cygpath -m "$T/tmp") -Djava.io.tmpdir=$(cygpath -m "$T/tmp")"
export FLUXFR_BUILD_DIR="$(cygpath -m "$T/out")"
cd "$(dirname "$0")"
./gradlew assembleRelease "$@"
cp "$T/out/app/outputs/apk/release/app-release.apk" ./Prisme.apk
echo "APK : $(pwd)/Prisme.apk"
