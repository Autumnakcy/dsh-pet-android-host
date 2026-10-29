#!/usr/bin/env bash
# 一键构建 pethost.apk（不需要 Gradle / Android Studio）
#
# 依赖（都在 PATH 里）：
#   javac / java   JDK 17
#   aapt zipalign apksigner   Android build-tools（28+ 均可，本项目在 34 上验过）
# 另外需要两个 jar：
#   android.jar   （API 34 编译用）       → 默认找 dl/android.jar
#   r8.jar        （D8/D8 用，官方可下）  → 默认找 dl/r8.jar
#
# 用法：
#   export ANDROID_JAR=/path/to/android.jar
#   export R8_JAR=/path/to/r8.jar
#   bash build.sh
#   → out/pethost.apk
#
# 补丁层（app/assets/host-*.js）就随包走，不需要额外文件。
set -euo pipefail
cd "$(dirname "$0")"

ANDROID_JAR="${ANDROID_JAR:-dl/android.jar}"
R8_JAR="${R8_JAR:-dl/r8.jar}"
OUT=out

for f in "$ANDROID_JAR" "$R8_JAR"; do
  [ -f "$f" ] || { echo "缺少 $f —— 见 README「构建」一节（android.jar / r8.jar）"; exit 1; }
done
for t in javac java aapt zipalign apksigner; do
  command -v "$t" >/dev/null 2>&1 || { echo "缺少命令：$t"; exit 1; }
done

rm -rf "$OUT" && mkdir -p "$OUT/classes" "$OUT/dex"

echo "[1/6] javac"
javac -nowarn -source 8 -target 8 -classpath "$ANDROID_JAR" \
      -d "$OUT/classes" $(find app/src -name '*.java')

echo "[2/6] d8"
java -cp "$R8_JAR" com.android.tools.r8.D8 --release --min-api 28 \
     --lib "$ANDROID_JAR" --output "$OUT/dex" $(find "$OUT/classes" -name '*.class')

echo "[3/6] aapt package"
aapt package -f -M app/AndroidManifest.xml -I "$ANDROID_JAR" -S app/res -A app/assets -F "$OUT/base.apk"

echo "[4/6] add classes.dex"
( cd "$OUT/dex" && aapt add ../base.apk classes.dex )

echo "[5/6] zipalign"
zipalign -f 4 "$OUT/base.apk" "$OUT/aligned.apk"

echo "[6/6] sign"
if [ ! -f keystore.jks ]; then
  echo "  keystore.jks 不存在 → 生成一把本地调试钥（口令 dshpet123，别拿去发布）"
  keytool -genkeypair -keystore keystore.jks -storepass dshpet123 -keypass dshpet123 \
    -alias pet -keyalg RSA -keysize 2048 -validity 3650 \
    -dname "CN=DSH Pet Host, O=local, C=CN" >/dev/null 2>&1
fi
apksigner sign --ks keystore.jks --ks-pass pass:dshpet123 --key-pass pass:dshpet123 \
    --out "$OUT/pethost.apk" "$OUT/aligned.apk"
apksigner verify --print-certs "$OUT/pethost.apk" | head -5
ls -l "$OUT/pethost.apk"
