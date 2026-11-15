#!/usr/bin/env bash
# 手工出包：aapt2 -> javac -> d8 -> zipalign -> apksigner
# 不依赖 Gradle、不联网、不带任何 .so（所以是纯 Java，任何 ABI 都能跑，含 armeabi-v7a）
set -e

VERSION_CODE="${VERSION_CODE:-26}"
VERSION_NAME="${VERSION_NAME:-2.9.6}"

# ---------- 找 SDK ----------
find_sdk() {
  if [ -n "${ANDROID_HOME:-}" ]; then echo "$ANDROID_HOME"; return; fi
  if [ -n "${ANDROID_SDK_ROOT:-}" ]; then echo "$ANDROID_SDK_ROOT"; return; fi
  for d in "$HOME/AppData/Local/Android/Sdk" "$HOME/Android/Sdk" "/usr/local/lib/android/sdk"; do
    if [ -d "$d" ]; then echo "$d"; return; fi
  done
}

SDK="$(find_sdk || true)"
if [ -z "$SDK" ] || [ ! -d "$SDK" ]; then
  echo "Android SDK 未找到。请设置 ANDROID_HOME，或把 SDK 放到常见位置。"
  exit 1
fi

# ---------- 找 build-tools / platform ----------
BT_VER="${BUILD_TOOLS_VERSION:-$(ls -1 "$SDK/build-tools" 2>/dev/null | sort -V | tail -n 1 || true)}"
PLATFORM_VER="${PLATFORM_VERSION:-$(ls -1 "$SDK/platforms" 2>/dev/null | sort -V | tail -n 1 || true)}"
if [ -z "$BT_VER" ] || [ -z "$PLATFORM_VER" ]; then
  echo "缺少 build-tools 或 platform：$SDK"
  exit 1
fi

BT="$SDK/build-tools/$BT_VER"
ANDROID_JAR="$SDK/platforms/$PLATFORM_VER/android.jar"

# 同时兼容 Windows 的 .exe/.bat 和 Unix 可执行文件
find_tool() {
  for c in "$1.exe" "$1.bat" "$1"; do
    if [ -f "$c" ]; then echo "$c"; return; fi
  done
  command -v "$(basename "$1")" 2>/dev/null || true
}

AAPT2="$(find_tool "$BT/aapt2")"
ZIPALIGN="$(find_tool "$BT/zipalign")"
APKSIGNER="$(find_tool "$BT/apksigner")"
D8="$(find_tool "$BT/d8")"

[ -n "$AAPT2" ] || { echo "aapt2 not found in $BT"; exit 1; }
[ -n "$ZIPALIGN" ] || { echo "zipalign not found in $BT"; exit 1; }
[ -n "$APKSIGNER" ] || { echo "apksigner not found in $BT"; exit 1; }
[ -n "$D8" ] || { echo "d8 not found in $BT"; exit 1; }
[ -f "$ANDROID_JAR" ] || { echo "android.jar not found: $ANDROID_JAR"; exit 1; }

JAVAC="${JAVAC:-$(command -v javac 2>/dev/null || true)}"
JAR_TOOL="${JAR_TOOL:-$(command -v jar 2>/dev/null || true)}"
[ -n "$JAVAC" ] || { echo "javac 未找到，请安装 JDK 8+ 并加入 PATH。"; exit 1; }
[ -n "$JAR_TOOL" ] || { echo "jar 未找到，请安装 JDK 8+ 并加入 PATH。"; exit 1; }
PYTHON="${PYTHON:-$(command -v python3 2>/dev/null || command -v python 2>/dev/null || true)}"

OUT="${1:-app/build/out}"
rm -rf app/build
mkdir -p app/build/res app/build/gen app/build/classes app/build/dex "$(dirname "$OUT")"

echo "[1/6] aapt2 compile"
"$AAPT2" compile --dir app/src/main/res -o app/build/res.zip

echo "[2/6] aapt2 link"
# 源码里的 Manifest 不带 package（AGP 8 之后包名走 namespace），
# 手工出包这条路上 aapt2 仍然要它 —— 这里临时补一份给 aapt2 用。
awk 'NR==2{print; print "    package=\"com.feiyu.tape\""; next} {print}' \
  app/src/main/AndroidManifest.xml > app/build/AndroidManifest.tmp.xml
"$AAPT2" link \
  -o app/build/base.apk \
  -I "$ANDROID_JAR" \
  --manifest app/build/AndroidManifest.tmp.xml \
  --auto-add-overlay \
  -R app/build/res.zip \
  --java app/build/gen \
  --min-sdk-version 19 \
  --target-sdk-version 28 \
  --version-code "$VERSION_CODE" --version-name "$VERSION_NAME"

echo "[3/6] javac"
SRCS=$(find app/src/main/java app/build/gen -name '*.java')
if ! "$JAVAC" -encoding UTF-8 -nowarn -source 8 -target 8 \
      -J-Duser.language=en -J-Duser.country=US \
      -bootclasspath "$ANDROID_JAR" \
      -d app/build/classes $SRCS > app/build/javac.log 2>&1; then
  echo "--- javac failed ---"
  cat app/build/javac.log
  exit 1
fi
grep -i "error" app/build/javac.log || true

echo "[4/6] d8"
"$JAR_TOOL" cf app/build/classes.jar -C app/build/classes .
"$D8" --release --min-api 19 --lib "$ANDROID_JAR" \
  --output app/build/dex app/build/classes.jar

echo "[5/6] pack dex + zipalign"
cp app/build/base.apk app/build/unsigned.apk
if [ -n "$PYTHON" ] && "$PYTHON" -c "import sys" >/dev/null 2>&1; then
  "$PYTHON" - <<'PYEOF'
import zipfile, shutil
src = 'app/build/unsigned.apk'
tmp = 'app/build/unsigned2.apk'
shutil.copyfile(src, tmp)
with zipfile.ZipFile(tmp, 'a', zipfile.ZIP_DEFLATED) as z:
    z.write('app/build/dex/classes.dex', 'classes.dex')
shutil.move(tmp, src)
print('   dex packed')
PYEOF
else
  "$JAR_TOOL" uf app/build/unsigned.apk -C app/build/dex classes.dex
  echo "   dex packed (jar)"
fi

"$ZIPALIGN" -f -p 4 app/build/unsigned.apk app/build/aligned.apk

echo "[6/6] sign"
KS="${TAPE_KEYSTORE:-tape.jks}"
KEY_ALIAS="${TAPE_KEY_ALIAS:-tape}"
: "${TAPE_KS_PASS:?请先设置 TAPE_KS_PASS（keystore 密码）}"
: "${TAPE_KEY_PASS:?请先设置 TAPE_KEY_PASS（key 密码）}"
if [ ! -f "$KS" ]; then
  echo "签名密钥未找到：$KS"
  echo "请把 keystore 放在仓库外，并用 TAPE_KEYSTORE 指定路径。"
  exit 1
fi

cp app/build/aligned.apk "$OUT"
"$APKSIGNER" sign \
  --ks "$KS" \
  --ks-key-alias "$KEY_ALIAS" \
  --ks-pass "env:TAPE_KS_PASS" \
  --key-pass "env:TAPE_KEY_PASS" \
  --min-sdk-version 19 \
  --v1-signing-enabled true \
  --v2-signing-enabled true \
  "$OUT"
echo "OK -> $OUT"
ls -l "$OUT"
