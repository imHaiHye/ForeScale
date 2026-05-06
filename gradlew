#!/bin/sh
# Gradle wrapper script for Unix
GRADLE_APP_NAME="Gradle"
APP_HOME=$( cd "${APP_HOME:-./}" && pwd -P ) || exit
APP_NAME="Gradle"
APP_BASE_NAME=${0##*/}

DEFAULT_JVM_OPTS='"-Xmx64m" "-Xms64m"'

MAX_FD=maximum

warn () {
    echo "$*"
}

die () {
    echo
    echo "$*"
    echo
    exit 1
}

if [ "$APP_HOME" = "" ]; then
    APP_HOME="$(dirname "$0")"
fi

CLASSPATH=$APP_HOME/gradle/wrapper/gradle-wrapper.jar

java_cmd="java"
if [ -n "$JAVA_HOME" ]; then
    java_cmd="$JAVA_HOME/bin/java"
fi

exec "$java_cmd" -classpath "$CLASSPATH" org.gradle.wrapper.GradleWrapperMain "$@"
