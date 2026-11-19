#!/bin/sh
# 车机 App 构建脚本 · macOS 版（与 build.sh 等价，只换了工具路径和可执行文件后缀）
#
# 和 build.sh 的区别：
#   - JDK 用 tools/jdk/<jdk>/Contents/Home（macOS 的 JDK 多一层 Contents/Home）
#   - Android build-tools / platform 直接取 ~/Library/Android/sdk 下的（可用 BT= / AJAR= 覆盖）
#   - aapt2 / d8 / zipalign / apksigner 没有 .exe 后缀
#   - addzip.py 用 python3
#
# 用法：  sh build-mac.sh
set -e

HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/.." && pwd)
T=$ROOT/tools
SDK=${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}

JAVA_HOME=${JAVA_HOME:-$(echo "$T"/jdk/*/Contents/Home | awk '{print $1}')}
BT=${BT:-$(echo "$SDK"/build-tools/*/ | awk '{print $1}')}
AJAR=${AJAR:-$(echo "$SDK"/platforms/*/android.jar | awk '{print $NF}' | tr ' ' '\n' | sort -V | tail -n 1)}
PATH="$JAVA_HOME/bin:$PATH"
export PATH

echo "== JDK         $JAVA_HOME"
echo "== build-tools $BT"
echo "== android.jar $AJAR"

cd "$HERE"
rm -rf build
mkdir -p build/compiled build/gen build/classes build/dex build/apk

# 1) 资源
"$BT/aapt2" compile --dir res -o build/compiled/res.zip

# 2) 链接资源 + 生成 R.java
"$BT/aapt2" link \
    -o build/apk/base.apk \
    -I "$AJAR" \
    --manifest AndroidManifest.xml \
    --min-sdk-version 28 --target-sdk-version 28 \
    --version-code 103 --version-name 2.12.5 \
    --java build/gen \
    build/compiled/res.zip

# 3) 编译 Java
find src build/gen -name '*.java' > build/srcs.txt
if ! javac -nowarn -encoding UTF-8 -source 8 -target 8 \
    -bootclasspath "$AJAR" -classpath "$AJAR" \
    -d build/classes @build/srcs.txt > build/javac.log 2>&1; then
    echo "!! javac 失败："
    cat build/javac.log
    exit 1
fi
grep -v 'bootstrap class path' build/javac.log || true

# 4) dex
find build/classes -name '*.class' > build/classes.txt
"$BT/d8" --lib "$AJAR" --min-api 28 --output build/dex @build/classes.txt

# 5) 组装
cp build/apk/base.apk build/apk/unsigned.apk
python3 "$HERE/../tools/addzip.py" build/apk/unsigned.apk build/dex/classes.dex
python3 "$HERE/../tools/addzip.py" build/apk/unsigned.apk --prefix assets/ --dir assets

# 6) 对齐 + 签名（AOSP testkey，车机信任该密钥）
KEY=$HERE/../tools/testkey
mkdir -p ../dist
"$BT/zipalign" -f 4 build/apk/unsigned.apk build/apk/aligned.apk
"$BT/apksigner" sign \
    --key "$KEY/testkey.pk8" \
    --cert "$KEY/testkey.x509.pem" \
    --out "../dist/P7仪表助手-原生版.apk" build/apk/aligned.apk

echo "== OK  ->  $HERE/../dist/P7仪表助手-原生版.apk"
ls -la "../dist/P7仪表助手-原生版.apk"
