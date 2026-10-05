#!/system/bin/sh
# ============================================================
# Qusic 手工构建脚本（无需 Gradle / Android Studio）
#   aapt2 / d8 / zipalign / android.jar  <- 本地 toolchain
#   java / javac / python3               <- Termux openjdk + python
# 用法: sh build.sh [静默]
# ============================================================
set -e
DSH=/data/user/0/com.deepseek.harness/files/dshh
PROJ=$DSH/Qusic
TOOL=/data/user/0/com.deepseek.harness/files/dshh/qtool
TPREFIX=/data/data/com.termux/files/usr
JDK=/data/user/0/com.deepseek.harness/files/dshh/qtool/jdk
PY=$TPREFIX/bin/python3
OUT=$PROJ/build
SRC=$PROJ/app/src/main

export LD_LIBRARY_PATH=$JDK/lib:$JDK/lib/server
export PATH=$JDK/bin:$PATH
export HOME=/data/local/tmp
export TMPDIR=/data/local/tmp
mkdir -p "$HOME"

PKG=$(sed -n 's/.*package="\([^"]*\)".*/\1/p' "$SRC/AndroidManifest.xml" | head -1)
VCODE=$(sed -n 's/.*android:versionCode="\([0-9]*\)".*/\1/p' "$SRC/AndroidManifest.xml" | head -1)
VNAME=$(sed -n 's/.*android:versionName="\([^"]*\)".*/\1/p' "$SRC/AndroidManifest.xml" | head -1)
VCODE=${VCODE:-1}; VNAME=${VNAME:-1.0}
echo "项目: Qusic  包名: $PKG  版本: $VNAME ($VCODE)"

echo "== 0/7 清理 =="
rm -rf "$OUT" 2>/dev/null || { chmod -R a+rwX "$OUT" 2>/dev/null; rm -rf "$OUT"; }
mkdir -p "$OUT/res-c" "$OUT/gen" "$OUT/classes" "$OUT/dex"
umask 022

echo "== 1/7 aapt2 compile =="
"$TOOL/aapt2" compile --dir "$SRC/res" -o "$OUT/res-c/res.zip"

echo "== 2/7 aapt2 link =="
"$TOOL/aapt2" link \
  -o "$OUT/base.apk" \
  -I "$TOOL/android.jar" \
  --manifest "$SRC/AndroidManifest.xml" \
  --java "$OUT/gen" \
  --min-sdk-version 21 \
  --target-sdk-version 34 \
  --version-code "$VCODE" \
  --version-name "$VNAME" \
  --auto-add-overlay \
  "$OUT/res-c/res.zip"

echo "== 3/7 javac =="
find "$SRC/java" "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
NSRC=$(wc -l < "$OUT/sources.txt")
echo "   源文件数: $NSRC"
javac -encoding UTF-8 -source 8 -target 8 -nowarn \
  -bootclasspath "$TOOL/android.jar" \
  -classpath "$TOOL/android.jar" \
  -d "$OUT/classes" \
  @"$OUT/sources.txt"
NCLASS=$(find "$OUT/classes" -name '*.class' | wc -l)
echo "   产出 class 数: $NCLASS"

echo "== 4/7 d8 =="
find "$OUT/classes" -name '*.class' > "$OUT/classes.txt"
java -cp "$TOOL/r8.jar" com.android.tools.r8.D8 \
  --lib "$TOOL/android.jar" \
  --min-api 21 \
  --output "$OUT/dex" \
  @"$OUT/classes.txt"

echo "== 5/7 打包 dex =="
cp "$OUT/base.apk" "$OUT/Qusic-unsigned.apk"
"$PY" "$DSH/adddex.py" "$OUT/Qusic-unsigned.apk" "$OUT/dex"

echo "== 6/7 签名（必须先签名）=="
KS=$TOOL/debug.keystore
if [ ! -f "$KS" ]; then
  keytool -genkeypair -keystore "$KS" -storepass android -keypass android \
    -alias md3lab -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Qusic, OU=Dev, O=Local, L=City, ST=State, C=CN" >/dev/null 2>&1
fi
cp "$OUT/Qusic-unsigned.apk" "$OUT/Qusic-signed.apk"
jarsigner -keystore "$KS" -storepass android -keypass android \
  -sigalg SHA256withRSA -digestalg SHA-256 "$OUT/Qusic-signed.apk" md3lab 2>&1 | head -5

echo "== 7/7 zipalign（必须放在签名之后）=="
# 关键：jarsigner 会重写 zip，把条目挪到未对齐的偏移上。
# Android 11+ (targetSdk 30+) 要求 resources.arsc 既「不压缩」又「4 字节对齐」，
# 所以对齐必须是最后一步。
# 用 v1(JAR) 签名时这样做是安全的：它只对条目内容做摘要，不覆盖文件布局。
"$TOOL/zipalign" -f -p 4 "$OUT/Qusic-signed.apk" "$OUT/Qusic.apk" || exit 1

chmod -R a+rwX "$OUT" 2>/dev/null
ls -lh "$OUT/Qusic.apk"
echo "== 构建完成 =="
