#!/bin/sh
# Build OgurisChips-<version>.apk with the manual aapt2/javac/d8 pipeline.
# Needs: JDK 17 (JAVA_HOME), Android SDK platform android-34 + build-tools 34.0.0.
# Set ANDROID_SDK to your SDK root if it is not ~/android-sdk.
set -e
cd "$(dirname "$0")"
SDK="${ANDROID_SDK:-$HOME/android-sdk}"
BT="$SDK/build-tools/34.0.0"
JAR="$SDK/platforms/android-34/android.jar"
KS="ogurichips.keystore"
if [ ! -f "$KS" ]; then
  keytool -genkeypair -keystore "$KS" -alias ogurichips -keyalg RSA -keysize 2048 \
    -validity 10000 -storepass ogurichips2026 -keypass ogurichips2026 \
    -dname "CN=Oguri's Chips"
fi
VER=$(grep -o 'versionName="[^"]*"' app/AndroidManifest.xml | cut -d'"' -f2)
OUT="OgurisChips-$VER.apk"
rm -rf build && mkdir -p build/classes build/dex
"$BT/aapt2" compile --dir app/res -o build/res.zip
"$BT/aapt2" link -o build/unsigned.apk build/res.zip -I "$JAR" \
  --manifest app/AndroidManifest.xml --min-sdk-version 24 --target-sdk-version 34
javac -encoding UTF-8 -source 8 -target 8 -cp "$JAR" -d build/classes $(find app/src -name '*.java')
"$BT/d8" --lib "$JAR" --min-api 24 --output build/dex $(find build/classes -name '*.class')
(cd build/dex && zip -q ../unsigned.apk classes.dex)
(cd build && "$BT/zipalign" -f 4 unsigned.apk aligned.apk)
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:ogurichips2026 --key-pass pass:ogurichips2026 \
  --out "$OUT" build/aligned.apk
"$BT/apksigner" verify "$OUT"
echo "Built $OUT"
