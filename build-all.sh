#!/bin/sh
# 一键构建：仪表端 ARM 二进制 + 车机端 APK
# 依赖：Go 1.22+、JDK 17、Android build-tools（见 README 第二节）
#
# 按主机自动选择打包脚本：
#   macOS          → carpad/build-mac.sh（工具无 .exe 后缀，JDK 走 Contents/Home）
#   Windows(MSYS)  → carpad/build.sh    （aapt2.exe / d8.bat 等）
#
# 顺序刻意如此：先做完所有前置检查，再覆盖 carpad/assets——避免检查失败时
# 已经改掉仓库里被跟踪的 p7assist，留下看不出来的半成品状态。
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
    echo "   本脚本只分派 macOS 与 Windows(MSYS/MinGW)。Linux 请手动两步：" >&2
    echo "     cd instrument && CGO_ENABLED=0 GOOS=linux GOARCH=arm GOARM=7 \\" >&2
    echo "       go build -trimpath -ldflags '-s -w' -o ../carpad/assets/p7assist ." >&2
    echo "     cd carpad && ANDROID_SDK_ROOT=<sdk路径> sh build-mac.sh" >&2
    exit 1
    ;;
esac
[ -f "$PACK_PATH" ] || { echo "!! 找不到打包脚本：$PACK_PATH" >&2; exit 1; }
echo "==> 主机 $(uname -s)，打包脚本 $PACK"

# ---- 1) 仪表端 ----
echo "==> 仪表端"
mkdir -p "$HERE/dist/仪表端"
cd "$HERE/instrument"
# 纯逻辑测试跑在主机平台（充电页等 linux-only 代码已拆到 chargepage_linux.go）
go test ./...
# 交叉编译到仪表：i.MX6 / ARMv7
CGO_ENABLED=0 GOOS=linux GOARCH=arm GOARM=7 \
  go build -trimpath -ldflags "-s -w" -o "$HERE/dist/仪表端/p7assist" .
cp ctl/p7assistctl autostart/p7boot.sh autostart/p7watch.sh \
   autostart/install-autostart.sh autostart/uninstall-autostart.sh \
   "$HERE/dist/仪表端/"
chmod +x "$HERE/dist/仪表端/p7assistctl" "$HERE/dist/仪表端/"*.sh

# ---- 2) 车机端 ----
echo "==> 车机端"
cp "$HERE/dist/仪表端/p7assist" "$HERE/carpad/assets/p7assist"
cp ctl/p7assistctl autostart/p7boot.sh autostart/p7watch.sh \
   autostart/install-autostart.sh autostart/uninstall-autostart.sh \
   "$HERE/carpad/assets/"
cd "$HERE/carpad"
sh "$PACK"

echo "==> 完成"
ls -la "$HERE/dist"
