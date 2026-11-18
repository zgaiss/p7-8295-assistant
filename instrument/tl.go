package main

// 红绿灯小件：横向沿用原厂 normalinfo 层「红绿灯灯行」的位置与宽度，纵向扩展。
//
// 几何（1920×720 绝对坐标，横向来自《ICM-仪表分区投放-投放内容区域示意图》
// 「红绿灯灯行（原厂灯行原位）」+《ICM-仪表显示几何》§五 normalinfo 层；
// 纵向是我们自己按两行卡片算的，用户明确允许扩展）：
//
//	卡片    x[875, 1045]  y[164, 278]   → 170 × 114，圆角 24（x 中心 960 = 屏宽中点）
//	灯盘    Ø44，圆心 x=907，圆心 y=196（左转）/ 246（直行）
//	秒数    右对齐到 x=1033，两位 38px、三位 28px
//
// 只画左转与直行两行 —— 数据源没有右转灯，原厂第三位空着也是浪费。
// 盘内只放方向箭头、秒数放盘外，两者不再共用 Ø48 圆内的面积。

import (
	"bytes"
	_ "embed"
	"image"
	_ "image/png"
	"math"
	"sync"
	"time"
)

//go:embed assets/tl/left_red.png
var tlPngLeftRed []byte

//go:embed assets/tl/left_yellow.png
var tlPngLeftYellow []byte

//go:embed assets/tl/left_green.png
var tlPngLeftGreen []byte

//go:embed assets/tl/straight_red.png
var tlPngStraightRed []byte

//go:embed assets/tl/straight_yellow.png
var tlPngStraightYellow []byte

//go:embed assets/tl/straight_green.png
var tlPngStraightGreen []byte

// 方向编码与 com.xiaopeng.light 日志一致：1=左转 2=直行（没有右转灯）
const (
	tlDirLeft     = 1
	tlDirStraight = 2
	tlDirRight    = 3 // 保留：原厂三盘有右位，但数据源不提供
)

// 相位编码：1=红 2=绿 3=黄 0=未知
const (
	tlRed    = 1
	tlGreen  = 2
	tlYellow = 3
)

type tlKey struct{ dir, cur int }

var (
	tlMu    sync.Mutex
	tlCache = map[tlKey]image.Image{}
)

// tlIcon 取某个方向 + 灯色的素材图；没有对应素材时返回 nil。
// 解码结果缓存，一张 200×200 的 RGBA 约 160KB，六张全解码不到 1MB。
func tlIcon(dir, cur int) image.Image {
	tlMu.Lock()
	defer tlMu.Unlock()
	k := tlKey{dir, cur}
	if im, ok := tlCache[k]; ok {
		return im
	}
	var b []byte
	switch dir {
	case tlDirLeft:
		switch cur {
		case tlRed:
			b = tlPngLeftRed
		case tlYellow:
			b = tlPngLeftYellow
		case tlGreen:
			b = tlPngLeftGreen
		}
	default: // 直行；右转没有素材，按直行兜底
		switch cur {
		case tlRed:
			b = tlPngStraightRed
		case tlYellow:
			b = tlPngStraightYellow
		case tlGreen:
			b = tlPngStraightGreen
		}
	}
	if b == nil {
		return nil
	}
	im, _, err := image.Decode(bytes.NewReader(b))
	if err != nil {
		return nil
	}
	tlCache[k] = im
	return im
}

// tlFromExt 把车机推来的相位换算成「此刻该画什么」。
//
// elapsed = 从车机观测到现在过了多久（调用方用 ext.json 的 mtime 算）。
// 倒计时在这里本地走秒，车机不需要每秒推包，只在相位变化时推一次即可。
//
// 相位已经走完但车机还没推新包时，用 nxt 顶上（remainMs 为负，nxtMs 补上），
// 这样跨相位的那一两秒不会先熄灭再亮起来。两者都没有就熄灭，绝不显示负数。
func tlFromExt(e *ExtTL, elapsed time.Duration) (tlInfo, bool) {
	var t tlInfo
	if e == nil || e.Ret != 0 || len(e.Phases) == 0 {
		return t, false
	}
	t.distM = e.DistM
	t.lim = e.Lim
	ms := float64(elapsed.Milliseconds())

	for _, ph := range e.Phases {
		var slot int
		switch ph.Dir {
		case tlDirLeft:
			slot = 0
		case tlDirStraight:
			slot = 1
		default:
			// 右转（3）和未知码都丢弃：卡片只有左转、直行两行
			continue
		}
		cur, left := ph.Cur, ph.RemainMs-ms
		if left <= 0 && ph.Nxt != 0 && ph.NxtMs > 0 {
			cur, left = ph.Nxt, ph.NxtMs+left
		}
		if cur == 0 || left <= 0 {
			continue
		}
		t.lights[slot] = tlLight{dir: ph.Dir, cur: cur, sec: math.Floor(left / 1000)}
	}
	return t, t.lit()
}
