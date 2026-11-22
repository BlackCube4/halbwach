#!/bin/bash
# Builds build/halbwach.dex (runs as the shell user on the tablet via app_process)
set -e
cd "$(dirname "$0")"
SDK=${ANDROID_HOME:-$HOME/Android/Sdk}
JAR=$SDK/platforms/android-36/android.jar
BT=$SDK/build-tools/36.0.0
JDK=${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk}/bin
rm -rf build && mkdir -p build/classes
"$JDK/javac" -Xlint:-options -source 17 -target 17 -classpath "$JAR" -d build/classes $(find src -name '*.java')
"$BT/d8" --release --min-api 34 --lib "$JAR" --output build $(find build/classes -name '*.class')
mv build/classes.dex build/halbwach.dex
ls -l build/halbwach.dex
