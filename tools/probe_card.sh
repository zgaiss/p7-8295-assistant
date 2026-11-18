#!/bin/sh
# 抓一帧仪表截图并打印歌词卡片区域的平均色（快速核对昼夜配色是否跟随底图）。
# 依赖：本机 python3 + Pillow，以及已经搭好的 30510 中继（见 README「九」）。
# 卡片矩形默认取 v2.12.2 的歌词位（x 1263~1821、y 459~599），可用 CARD_* 覆盖。
set -e
cd "$(dirname "$0")/.." || exit 1

CARD_X0=${CARD_X0:-1263}
CARD_X1=${CARD_X1:-1821}
CARD_Y0=${CARD_Y0:-459}
CARD_Y1=${CARD_Y1:-599}
OUT=${OUT:-dist/probe}
export CARD_X0 CARD_X1 CARD_Y0 CARD_Y1

R() { python3 tools/icrpc.py "$1" 2>/dev/null; }

mkdir -p "$OUT"
R 'rm -f /data/p7assist/shot.png; touch /data/p7assist/shot.req'
# 合成帧（fb0+fb1）比单平面慢，实测要 ≥8s，这里留 9s 余量
sleep 9
R 'ls -l /data/p7assist/shot.png' >/dev/null
SZ=$(R 'stat -c %s /data/p7assist/shot.png')
N=$(( (SZ + 44999) / 45000 ))
: > "$OUT/s.b64"
i=0
while [ $i -lt $N ]; do
  R "dd if=/data/p7assist/shot.png bs=45000 skip=$i count=1 2>/dev/null | base64 | tr -d '\n'" >> "$OUT/s.b64"
  i=$((i+1))
done
cd "$OUT" || exit 1
(base64 -d -i s.b64 -o s.png 2>/dev/null || base64 -D -i s.b64 -o s.png 2>/dev/null)
python3 - <<'PY'
import os
from PIL import Image

im = Image.open("s.png").convert("RGB")
p = im.load()
x0 = int(os.environ.get("CARD_X0", 1263))
x1 = int(os.environ.get("CARD_X1", 1821))
y0 = int(os.environ.get("CARD_Y0", 459))
y1 = int(os.environ.get("CARD_Y1", 599))

# 沿矩形上下两条边内缩取样：卡片里的歌词/封面集中在中间，边缘一圈基本是纯底色
ins = 6
pts = []
for fx in (0.08, 0.3, 0.5, 0.7, 0.92):
    x = int(x0 + ins + fx * (x1 - x0 - 2 * ins))
    pts.append((x, y0 + ins))
    pts.append((x, y1 - ins))

vals = [p[x, y] for x, y in pts]
avg = tuple(sum(v[i] for v in vals) // len(vals) for i in range(3))
lum = 0.2126 * avg[0] + 0.7152 * avg[1] + 0.0722 * avg[2]
print("卡片矩形 (%d,%d)-(%d,%d)  %d 点平均 #%02X%02X%02X (L=%.1f)"
      % (x0, y0, x1, y1, len(pts), avg[0], avg[1], avg[2], lum))
for (x, y), v in zip(pts, vals):
    print("   (%d,%d) #%02X%02X%02X" % (x, y, v[0], v[1], v[2]))
PY
