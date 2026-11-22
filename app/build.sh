#!/bin/bash
# Builds build/halbwach.apk without Gradle: app + panel service + Shizuku API.
#
# Needs: Android SDK (platforms/android-36, build-tools/36.0.0), JDK 21, python3, curl.
# Override paths with ANDROID_HOME and JAVA_HOME, the keystore password with HALBWACH_KEYSTORE_PASS.
set -e
cd "$(dirname "$0")"
SDK=${ANDROID_HOME:-$HOME/Android/Sdk}
JAR=$SDK/platforms/android-36/android.jar
BT=$SDK/build-tools/36.0.0
JDK=${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk}/bin
PASS=${HALBWACH_KEYSTORE_PASS:-halbwach}

# Fetch the Shizuku API (Apache 2.0) from Maven Central once
SHIZUKU=13.1.5
mkdir -p libs
for a in api provider shared aidl; do
  if [ ! -f libs/shizuku-$a.jar ]; then
    tmp=$(mktemp -d)
    curl -sfL -o "$tmp/$a.aar" "https://repo1.maven.org/maven2/dev/rikka/shizuku/$a/$SHIZUKU/$a-$SHIZUKU.aar"
    python3 -I -c "import zipfile,sys; open(sys.argv[2],'wb').write(zipfile.ZipFile(sys.argv[1]).read('classes.jar'))" "$tmp/$a.aar" libs/shizuku-$a.jar
    rm -rf "$tmp"
  fi
done
LIBS=$(ls libs/*.jar | tr '\n' ':')

rm -rf build && mkdir -p build/classes build/dex

# Create a signing key once (local only, keep a backup, never commit it)
if [ ! -f keystore.jks ]; then
  "$JDK/keytool" -genkeypair -keystore keystore.jks -storepass "$PASS" -keypass "$PASS" \
    -alias halbwach -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Halbwach" >/dev/null 2>&1
fi

# Resources (colors, light/dark theme, icon, strings) and R.java
mkdir -p build/res build/gen
"$BT/aapt2" compile --dir res -o build/res/res.zip
"$BT/aapt2" link -o build/unsigned.apk -I "$JAR" --manifest AndroidManifest.xml \
  --min-sdk-version 34 --target-sdk-version 36 --java build/gen build/res/res.zip

"$JDK/javac" -Xlint:-options -nowarn -source 17 -target 17 -classpath "$JAR:$LIBS" -d build/classes \
  $(find src ../daemon/src build/gen -name '*.java')
"$BT/d8" --release --min-api 34 --lib "$JAR" --output build/dex $(find build/classes -name '*.class') libs/*.jar

python3 -I -c "import zipfile,sys; zipfile.ZipFile(sys.argv[1],'a',zipfile.ZIP_DEFLATED).write(sys.argv[2],'classes.dex')" build/unsigned.apk build/dex/classes.dex
"$BT/zipalign" -f -p 4 build/unsigned.apk build/aligned.apk
"$BT/apksigner" sign --ks keystore.jks --ks-pass "pass:$PASS" --key-pass "pass:$PASS" \
  --out build/halbwach.apk build/aligned.apk 2>/dev/null
rm -f build/unsigned.apk build/aligned.apk build/halbwach.apk.idsig
ls -l build/halbwach.apk
