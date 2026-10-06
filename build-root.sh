#!/system/bin/sh
# ============================================================
# Qusic 构建脚本（以 root 身份运行，解决 Termux JDK 的 linker 命名空间问题）
#   用法: shizuku_shell "sh /data/user/0/com.deepseek.harness/files/dshh/Qusic/build-root.sh"
# ============================================================
DSH=/data/user/0/com.deepseek.harness/files/dshh
PROJ=$DSH/Qusic
TOOL=$DSH/qtool
TPREFIX=/data/data/com.termux/files/usr
JDK=$TPREFIX/lib/jvm/java-21-openjdk
PY=$TPREFIX/bin/python3
OUT=$PROJ/build
SRC=$PROJ/app/src/main

export LD_LIBRARY_PATH=$TPREFIX/lib:$JDK/lib:$JDK/lib/server
export PATH=$JDK/bin:$TPREFIX/bin:$PATH
export HOME=/data/local/tmp
export TMPDIR=/data/local/tmp
mkdir -p "$HOME"

PKG=$(sed -n 's/.*package="\([^"]*\)".*/\1/p' "$SRC/AndroidManifest.xml" | head -1)
VCODE=$(sed -n 's/.*android:versionCode="\([0-9]*\)".*/\1/p' "$SRC/AndroidManifest.xml" | head -1)
VNAME=$(sed -n 's/.*android:versionName="\([^"]*\)".*/\1/p' "$SRC/AndroidManifest.xml" | head -1)
VCODE=${VCODE:-1}; VNAME=${VNAME:-1.0}
echo "项目: Qusic  包名: $PKG  版本: $VNAME ($VCODE)"

echo "== 0/7 清理 =="
rm -rf "$OUT"
mkdir -p "$OUT/res-c" "$OUT/gen" "$OUT/classes" "$OUT/dex"
umask 022

echo "== 1/7 aapt2 compile =="
"$TOOL/aapt2" compile --dir "$SRC/res" -o "$OUT/res-c/res.zip" || exit 1

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
  "$OUT/res-c/res.zip" || exit 1

echo "== 3/7 javac =="
find "$SRC/java" "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
NSRC=$(wc -l < "$OUT/sources.txt")
echo "   源文件数: $NSRC"
# javac 的退出码必须直接判断。
# 之前用「class 数 < 源文件数」当失败条件 —— 那是错的：
# javac 遇到部分文件报错时，其余文件照样会产出 class，
# 于是 202 个 class 对 33 个源文件也算「成功」，
# 结果就是用上一次的旧 class 打了包，改动根本没进去。
javac -encoding UTF-8 -source 8 -target 8 -nowarn \
  -bootclasspath "$TOOL/android.jar" \
  -classpath "$TOOL/android.jar" \
  -d "$OUT/classes" \
  @"$OUT/sources.txt" > "$OUT/javac.log" 2>&1
JAVAC_RC=$?
grep -E "error:|错误:" "$OUT/javac.log" | head -40
NCLASS=$(find "$OUT/classes" -name '*.class' 2>/dev/null | wc -l)
echo "   产出 class 数: $NCLASS / 源文件 $NSRC"
if [ "$JAVAC_RC" -ne 0 ]; then
  echo "!! javac 失败（退出码 $JAVAC_RC）—— 中止构建，绝不能用旧 class 继续打包"
  exit 1
fi

echo "== 4/7 R8（压缩 + 混淆 + 打包）=="
find "$OUT/classes" -name '*.class' > "$OUT/classes.txt"
# 用 R8 而不是 D8：D8 只是打包器，会把所有方法原样写进 dex；
# R8 会做可达性分析，裁掉没被引用到的代码，并缩短类名/方法名 —— 体积能小一大截。
java -cp "$TOOL/r8.jar" com.android.tools.r8.R8 \
  --release \
  --lib "$TOOL/android.jar" \
  --min-api 21 \
  --pg-conf "$PROJ/proguard-rules.pro" \
  --pg-map-output "$OUT/mapping.txt" \
  --output "$OUT/dex" \
  @"$OUT/classes.txt" > "$OUT/r8.log" 2>&1
R8_RC=$?
grep -iE "error|warning: " "$OUT/r8.log" | head -20
if [ "$R8_RC" -ne 0 ]; then
  echo "!! R8 失败 —— 中止构建"; tail -20 "$OUT/r8.log"; exit 1
fi
ls -la "$OUT/dex"/*.dex 2>/dev/null | awk '{printf "   %s  %.1f KB\n", $NF, $5/1024}' 
[ -f "$OUT/dex/classes.dex" ] || { echo "!! d8 失败"; exit 1; }

echo "== 5/7 打包 dex =="
cp "$OUT/base.apk" "$OUT/Qusic-unsigned.apk"
"$PY" "$DSH/adddex.py" "$OUT/Qusic-unsigned.apk" "$OUT/dex" || exit 1

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
