#!/bin/sh
# 一键构建：仪表端 ARM 二进制 + 车机端 APK
# 依赖：Go 1.22+、JDK 17、Android build-tools（见 README 第 8 节）
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
export PATH="$HERE/tools/go/bin:$PATH"

echo "==> 仪表端"
cd "$HERE/instrument"
go test ./...
CGO_ENABLED=0 GOOS=linux GOARCH=arm GOARM=7 \
  go build -trimpath -ldflags "-s -w" -o "$HERE/dist/仪表端/p7assist" .
cp ctl/p7assistctl autostart/p7boot.sh autostart/p7watch.sh \
   autostart/install-autostart.sh autostart/uninstall-autostart.sh \
   "$HERE/dist/仪表端/"
chmod +x "$HERE/dist/仪表端/p7assistctl" "$HERE/dist/仪表端/"*.sh

echo "==> 车机端"
cp "$HERE/dist/仪表端/p7assist" "$HERE/carpad/assets/p7assist"
cp ctl/p7assistctl autostart/p7boot.sh autostart/p7watch.sh \
   autostart/install-autostart.sh autostart/uninstall-autostart.sh \
   "$HERE/carpad/assets/"
cd "$HERE/carpad"
sh build.sh

echo "==> 完成"
ls -la "$HERE/dist"
