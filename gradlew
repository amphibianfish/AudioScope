#!/usr/bin/env sh
set -eu

APP_HOME="$(cd "$(dirname "$0")" && pwd)"
WRAPPER_JAR="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"

if [ ! -f "$WRAPPER_JAR" ]; then
    echo "[AudioScope] Bootstrapping official gradle-wrapper.jar..."
    mkdir -p "$APP_HOME/gradle/wrapper"
    if command -v curl >/dev/null 2>&1; then
        curl -fsSL "https://raw.githubusercontent.com/gradle/gradle/v9.6.0/gradle/wrapper/gradle-wrapper.jar" -o "$WRAPPER_JAR" \
          || curl -fsSL "https://raw.githubusercontent.com/android/nowinandroid/main/gradle/wrapper/gradle-wrapper.jar" -o "$WRAPPER_JAR"
    elif command -v wget >/dev/null 2>&1; then
        wget -qO "$WRAPPER_JAR" "https://raw.githubusercontent.com/gradle/gradle/v9.6.0/gradle/wrapper/gradle-wrapper.jar" \
          || wget -qO "$WRAPPER_JAR" "https://raw.githubusercontent.com/android/nowinandroid/main/gradle/wrapper/gradle-wrapper.jar"
    fi
fi

if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    JAVACMD="$JAVA_HOME/bin/java"
else
    JAVACMD="java"
fi

exec "$JAVACMD" -Xmx64m -Xms64m -Dorg.gradle.appname=gradlew -classpath "$WRAPPER_JAR" org.gradle.wrapper.GradleWrapperMain "$@"
