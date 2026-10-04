#!/usr/bin/env bash
#
# Fetch a pinned JDK 17 + Gradle 8.11.1 into a persistent cache (only the pieces
# that are missing) and run this project's tests. The Gradle wrapper JAR is not
# committed, so Gradle is invoked directly, exactly like CI.
#
#   scripts/chirp-test.sh                 # runs :core:test
#   scripts/chirp-test.sh assembleDebug    # any Gradle args are forwarded
#
set -euo pipefail

CACHE="${CHIRP_TOOLCHAIN:-$HOME/.cache/chirp-toolchain}"
JDK="$CACHE/jdk17"
GRADLE="$CACHE/gradle-8.11.1"
export GRADLE_USER_HOME="$CACHE/gradle-home"

JDK_URL="https://api.adoptium.net/v3/binary/latest/17/ga/linux/x64/jdk/hotspot/normal/eclipse"
GRADLE_URL="https://services.gradle.org/distributions/gradle-8.11.1-bin.zip"
REQUIRED_KB=$((1600 * 1024))

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(dirname "$SCRIPT_DIR")"

need_download=false
[ -x "$JDK/bin/java" ] || need_download=true
[ -x "$GRADLE/bin/gradle" ] || need_download=true

if [ "$need_download" = true ]; then
    mkdir -p "$CACHE"
    available=$(df -Pk "$CACHE" | awk 'NR==2 {print $4}')
    if [ "$available" -lt "$REQUIRED_KB" ]; then
        echo "error: not enough space in $CACHE" >&2
        echo "need ~$((REQUIRED_KB / 1024)) MB free, have ~$((available / 1024)) MB" >&2
        df -h "$CACHE" >&2
        exit 1
    fi
    stage="$CACHE/.stage"
    rm -rf "$stage"
    mkdir -p "$stage"
    trap 'rm -rf "$stage" "$CACHE/.gradle.zip" 2>/dev/null || true' EXIT

    if [ ! -x "$JDK/bin/java" ]; then
        echo "fetching JDK 17 into $JDK ..." >&2
        curl -fL --retry 3 "$JDK_URL" | tar -xzf - -C "$stage"
        inner=$(find "$stage" -mindepth 1 -maxdepth 1 -type d | head -1)
        mv "$inner" "$JDK"
    fi

    if [ ! -x "$GRADLE/bin/gradle" ]; then
        echo "fetching Gradle 8.11.1 into $GRADLE ..." >&2
        curl -fL --retry 3 "$GRADLE_URL" -o "$CACHE/.gradle.zip"
        python3 -m zipfile -e "$CACHE/.gradle.zip" "$stage"
        mv "$stage/gradle-8.11.1" "$GRADLE"
        chmod +x "$GRADLE/bin/gradle"
    fi

    rm -rf "$stage"
    rm -f "$CACHE/.gradle.zip"
    trap - EXIT
fi

export JAVA_HOME="$JDK"
export PATH="$JAVA_HOME/bin:$GRADLE/bin:$PATH"
cd "$REPO_ROOT"

[ "$#" -eq 0 ] && set -- :core:test
exec gradle --no-daemon --stacktrace "$@"
