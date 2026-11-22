#!/bin/sh
# 车机 App 构建脚本（无需 Gradle / Android Studio）
#
# 依赖（都放在项目根 tools/ 下）：
#   JDK 17        tools/jdk/<jdk>/bin/{java,javac,keytool}
#   Android SDK   tools/bt/<build-tools>/  (aapt2/d8/zipalign/apksigner)
#                 tools/plat/<platform>/android.jar
#
# 用法：  sh build.sh
set -e

HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/.." && pwd)
T=$ROOT/tools

JAVA_HOME=${JAVA_HOME:-$(echo "$T"/jdk/* | awk '{print $1}')}
BT=$(echo "$T"/bt/*/ | awk '{print $1}')
AJAR=$(echo "$T"/plat/*/android.jar | awk '{print $1}')
PATH="$T/go/bin:$JAVA_HOME/bin:$PATH"
export PATH

echo "== JDK         $JAVA_HOME"
echo "== build-tools $BT"
echo "== android.jar $AJAR"

cd "$HERE"
rm -rf build
mkdir -p build/compiled build/gen build/classes build/dex build/apk
python "$ROOT/tools/build_version.py" prepare --out build

# 1) 资源
"$BT/aapt2.exe" compile --dir res -o build/compiled/res.zip

# 2) 链接资源 + 生成 R.java
"$BT/aapt2.exe" link \
    -o build/apk/base.apk \
    -I "$AJAR" \
    --manifest build/AndroidManifest.xml \
    --min-sdk-version 28 --target-sdk-version 28 \
    --java build/gen \
    build/compiled/res.zip

# 3) 编译 Java（用相对路径，避免 Windows 版 javac 读不懂 /c/... 形式）
find src build/gen -name '*.java' | sed 's|^|./|' > build/srcs.txt
if ! javac -nowarn -encoding UTF-8 -source 8 -target 8 \
    -bootclasspath "$AJAR" -classpath "$AJAR" \
    -d build/classes @build/srcs.txt > build/javac.log 2>&1; then
    echo "!! javac 失败："
    cat build/javac.log
    exit 1
fi
grep -v 'bootstrap class path' build/javac.log || true

# 4) dex
find build/classes -name '*.class' | sed 's|^|./|' > build/classes.txt
"$BT/d8.bat" --lib "$AJAR" --min-api 28 --output build/dex @build/classes.txt

# 5) 组装：base.apk（资源）+ classes.dex + assets
cp build/apk/base.apk build/apk/unsigned.apk
python "$HERE/../tools/addzip.py" build/apk/unsigned.apk build/dex/classes.dex
python "$HERE/../tools/addzip.py" build/apk/unsigned.apk --prefix assets/ --dir assets
# Override tracked prebuilt with the mandatory freshly versioned binary.
python "$ROOT/tools/addzip.py" build/apk/unsigned.apk --prefix assets/ --dir build/instrument
python "$ROOT/tools/build_version.py" verify --out build --apk build/apk/unsigned.apk --aapt "$BT/aapt2.exe"

# 6) 对齐 + 签名
KS=$HERE/p7.keystore
if [ ! -f "$KS" ]; then
    keytool -genkeypair -keystore "$KS" -storepass p7p7p7 -keypass p7p7p7 \
        -alias p7 -keyalg RSA -keysize 2048 -validity 10000 \
        -dname "CN=P7 Native, OU=P7, O=P7, L=NA, S=NA, C=CN" >/dev/null 2>&1
    echo "== 已生成签名证书 p7.keystore（正式发布请换成自己的）"
fi

mkdir -p ../dist
"$BT/zipalign.exe" -f 4 build/apk/unsigned.apk build/apk/aligned.apk
# 用 AOSP testkey 签名（与原版 P7仪表助手一致，车机信任该测试密钥）
"$BT/apksigner.bat" sign \
    --key "$ROOT/tools/testkey/testkey.pk8" \
    --cert "$ROOT/tools/testkey/testkey.x509.pem" \
    --out build/apk/signed.apk build/apk/aligned.apk

python "$ROOT/tools/build_version.py" verify --out build --apk build/apk/signed.apk --aapt "$BT/aapt2.exe"
# 产物名带版本号（去掉"原生版"），版本号直接取构建快照，永远跟着 manifest 走。
APK_NAME="P7仪表助手-$(python -c 'import json;print(json.load(open("build/version.json"))["name"])').apk"
cp build/apk/signed.apk "../dist/$APK_NAME.tmp"
mv -f "../dist/$APK_NAME.tmp" "../dist/$APK_NAME"

echo "== OK  ->  $HERE/../dist/$APK_NAME"
ls -la "../dist/$APK_NAME"
