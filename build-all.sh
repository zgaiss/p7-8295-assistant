#!/bin/sh
# 一键构建：仪表端 ARM 二进制 + 车机端 APK
# 依赖：Go 1.23.4、JDK 17、Android build-tools（见 README 第二节）
#
# 按主机自动选择打包脚本：
#   macOS          → carpad/build-mac.sh（工具无 .exe 后缀，JDK 走 Contents/Home）
#   Windows(MSYS)  → carpad/build.sh    （aapt2.exe / d8.bat 等）
#
# 前置检查后由独立打包入口统一构建；新资产只放build目录，不覆盖tracked assets。
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
export PATH="$HERE/tools/go/bin:$PATH"

# ---- 0) 选打包脚本 + 前置检查 ----
case "$(uname -s)" in
  Darwin)
    PACK=build-mac.sh
    PACK_PATH="$HERE/carpad/build-mac.sh"
    JDK=$(echo "$HERE"/tools/jdk/*/Contents/Home | awk '{print $1}')
    SDK=${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}
    [ -d "$JDK" ] || { echo "!! 缺少 JDK：$JDK" >&2; echo "   见 README 第二节的目录约定" >&2; exit 1; }
    [ -d "$SDK/build-tools" ] || { echo "!! 缺少 Android build-tools：$SDK/build-tools" >&2; echo "   可用 ANDROID_SDK_ROOT=<sdk路径> 覆盖" >&2; exit 1; }
    [ -d "$SDK/platforms" ] || { echo "!! 缺少 Android platform（android.jar）：$SDK/platforms" >&2; exit 1; }
    ;;
  MINGW*|MSYS*|CYGWIN*)
    PACK=build.sh
    PACK_PATH="$HERE/carpad/build.sh"
    [ -d "$HERE/tools/bt" ] || { echo "!! 缺少 Android build-tools：$HERE/tools/bt" >&2; exit 1; }
    [ -d "$HERE/tools/plat" ] || { echo "!! 缺少 Android platform：$HERE/tools/plat" >&2; exit 1; }
    ;;
  *)
    echo "!! 不支持的系统：$(uname -s)" >&2
    echo "   Linux 仪表构建（从仓库根目录）：" >&2
    echo "     python3 tools/build_version.py prepare --out /tmp/p7-instrument-build" >&2
    echo "   APK 需配置可用的 JAVA_HOME / BT / AJAR 后运行 carpad/build-mac.sh。" >&2
    exit 1
    ;;
esac
[ -f "$PACK_PATH" ] || { echo "!! 找不到打包脚本：$PACK_PATH" >&2; exit 1; }
echo "==> 主机 $(uname -s)，打包脚本 $PACK"

# ---- build/test; standalone packager owns the only instrument build ----
cd "$HERE/instrument"
GOTOOLCHAIN=local "${GO:-go}" test ./...
cd "$HERE/carpad"
sh "$PACK"
mkdir -p "$HERE/dist/仪表端"
cp build/instrument/* "$HERE/dist/仪表端/"
chmod +x "$HERE/dist/仪表端/p7assistctl" "$HERE/dist/仪表端/"*.sh
echo "==> 完成"
ls -la "$HERE/dist"
