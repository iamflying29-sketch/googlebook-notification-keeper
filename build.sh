#!/bin/bash
# Builds and signs NotificationKeeper.apk with the Android SDK in ~/tools/android-sdk.
# Set ANDROID_SDK to use an SDK somewhere else. Set KS_PASS to your own keystore password; a new
# keystore.jks is created with that password the first time if none exists.
set -euo pipefail

cd "$(dirname "$0")"
SDK="${ANDROID_SDK:-$HOME/tools/android-sdk}"
BT=$SDK/build-tools/35.0.0
JAR=$SDK/platforms/android-35/android.jar
KS=keystore.jks
KS_PASS="${KS_PASS:-change-me}"
VERSION_NAME=1.2
VERSION_CODE=$(( $(date +%s) / 60 ))   # epoch minutes: always increases, never a downgrade

# A fresh build directory each time (nothing is ever deleted).
B=build/$(date +%Y%m%d-%H%M%S)
mkdir -p "$B/classes" "$B/gen"

"$BT/aapt2" compile --dir res -o $B/res.zip
"$BT/aapt2" link -o $B/unsigned.apk -I "$JAR" --manifest AndroidManifest.xml \
    --min-sdk-version 33 --target-sdk-version 35 --version-code $VERSION_CODE --version-name $VERSION_NAME \
    --java $B/gen $B/res.zip

javac -Xlint:all -Xlint:-options -source 11 -target 11 -cp "$JAR" -d $B/classes \
    $B/gen/local/notifkeeper/R.java src/local/notifkeeper/*.java
"$BT/d8" --min-api 33 --lib "$JAR" --output $B $(find $B/classes -name '*.class')

python3 -I -c 'import sys, zipfile; zipfile.ZipFile(sys.argv[1], "a").write(sys.argv[2], "classes.dex")' \
    $B/unsigned.apk $B/classes.dex
"$BT/zipalign" -p -f 4 $B/unsigned.apk $B/aligned.apk

if [ ! -f "$KS" ]; then
    keytool -genkeypair -keystore "$KS" -storepass "$KS_PASS" -keypass "$KS_PASS" -alias notifkeeper \
        -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Notification Keeper (local build)"
fi
"$BT/apksigner" sign --ks "$KS" --ks-pass "pass:$KS_PASS" --out NotificationKeeper.apk $B/aligned.apk
"$BT/apksigner" verify NotificationKeeper.apk
echo "Built $(pwd)/NotificationKeeper.apk (version $VERSION_NAME, code $VERSION_CODE)"
