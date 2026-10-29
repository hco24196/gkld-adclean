#!/bin/bash
# 不依赖 Gradle 的最小构建脚本：aapt2 + javac + d8 + apksigner
# 用法：ANDROID_SDK_ROOT=/path/to/android-sdk ./tools/build-apk.sh [输出路径]
set -e

SDK=${ANDROID_SDK_ROOT:-$ANDROID_HOME}
[ -z "$SDK" ] && { echo "请先设置 ANDROID_SDK_ROOT"; exit 1; }

BT=$SDK/build-tools/34.0.0
PLATFORM=$SDK/platforms/android-34/android.jar
ROOT=$(cd "$(dirname "$0")/.." && pwd)
SRC=$ROOT/app/src/main
OUT=$ROOT/build-manual
VERSION_NAME=${VERSION_NAME:-1.7.2}
VERSION_CODE=${VERSION_CODE:-11}
APK=${1:-$ROOT/../gkld-adclean-v${VERSION_NAME}.apk}

# 编译期依赖：Xposed API 只在编译时用，不打包
if [ ! -f "$ROOT/libs/api-82.jar" ]; then
  mkdir -p "$ROOT/libs"
  curl -sS -L -o "$ROOT/libs/api-82.jar" \
    "https://maven.aliyun.com/repository/public/de/robv/android/xposed/api/82/api-82.jar"
fi

rm -rf "$OUT" && mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex"

# aapt2 需要 package 属性（AGP 工程用的是 namespace）
python3 - "$SRC/AndroidManifest.xml" "$OUT/AndroidManifest.xml" <<'PY'
import sys
s = open(sys.argv[1], encoding="utf-8").read()
s = s.replace('<manifest xmlns:android="http://schemas.android.com/apk/res/android">',
              '<manifest xmlns:android="http://schemas.android.com/apk/res/android"\n    package="com.gkld.adclean">', 1)
open(sys.argv[2], "w", encoding="utf-8").write(s)
PY

"$BT/aapt2" compile --dir "$SRC/res" -o "$OUT/res.zip"
"$BT/aapt2" link -o "$OUT/base.apk" -I "$PLATFORM" \
  --manifest "$OUT/AndroidManifest.xml" -A "$SRC/assets" --java "$OUT/gen" \
  --min-sdk-version 26 --target-sdk-version 34 \
  --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" --auto-add-overlay "$OUT/res.zip"

find "$SRC/java" "$OUT/gen" -name "*.java" > "$OUT/sources.txt"
javac -encoding UTF-8 --release 17 -nowarn \
  -classpath "$PLATFORM:$ROOT/libs/api-82.jar" -d "$OUT/classes" @"$OUT/sources.txt"

find "$OUT/classes" -name "*.class" > "$OUT/classes.txt"
"$BT/d8" --release --min-api 26 --lib "$PLATFORM" --output "$OUT/dex" @"$OUT/classes.txt"

python3 - "$OUT/base.apk" "$OUT/dex/classes.dex" "$OUT/unsigned.apk" <<'PY'
import sys, shutil, zipfile
shutil.copy(sys.argv[1], sys.argv[3])
with zipfile.ZipFile(sys.argv[3], "a", zipfile.ZIP_DEFLATED) as z:
    z.write(sys.argv[2], "classes.dex")
PY

KS=$ROOT/libs/debug.keystore
if [ ! -f "$KS" ]; then
  keytool -genkeypair -keystore "$KS" -storepass android -keypass android \
    -alias gkld -keyalg RSA -keysize 2048 -validity 10950 \
    -dname "CN=GkldAdClean, OU=Personal, O=Personal, L=CN, ST=CN, C=CN" >/dev/null 2>&1
fi

"$BT/zipalign" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
  --ks-key-alias gkld --out "$APK" "$OUT/aligned.apk"
"$BT/apksigner" verify "$APK"
echo "OK -> $APK"
