#!/usr/bin/env sh

##############################################################################
## Gradle start up script for UN*X
##############################################################################
set -e

DIRNAME="$(cd "$(dirname "$0")" && pwd)"
APP_HOME="$DIRNAME"
APP_BASE_NAME="$(basename "$0")"

# Find java executable
if [ -n "$JAVA_HOME" ]; then
    JAVACMD="$JAVA_HOME/bin/java"
else
    JAVACMD="java"
fi

if ! command -v "$JAVACMD" > /dev/null 2>&1; then
    echo "ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH." >&2
    exit 1
fi

# Set up classpath
CLASSPATH="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"

exec "$JAVACMD" \
    -classpath "$CLASSPATH" \
    org.gradle.wrapper.GradleWrapperMain \
    "$@"
