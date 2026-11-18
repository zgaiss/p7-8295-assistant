package main

// 布局：坐标全部取自原厂/原 App 的常量（1920x720 绝对坐标）
// 车模矩形 7 个参数：x0,y0,x1,y1, 左右间距, 字号, 分隔线长

const (
	frameW = 1920
	frameH = 720

	// 左上角歌词块的包围盒（用于拖动范围钳制与预览）。
	// 覆盖三种样式里最宽的 case3 渐变盒 x[0,640] y[0,186]，
	// 以及默认样式的竖条/文字。拖动时整块平移，靠这个盒子吸附屏幕边界。
	lyricBoxW = 640.0
	lyricBoxH = 186.0
)

type carRect struct {
	x0, y0, x1, y1 float64
	gap, fs, line  float64
}

var (
	carPark    = carRect{850, 130, 1070, 590, 26, 34, 88}
	carNormal  = carRect{887.5, 470, 1032.5, 600, 12, 26, 60}
	carCompact = carRect{909, 538, 1011, 630, 10, 22, 56}
)

func lerp(a, b, t float64) float64 { return a + (b-a)*t }

func lerpCar(a, b carRect, t float64) carRect {
	return carRect{
		lerp(a.x0, b.x0, t), lerp(a.y0, b.y0, t), lerp(a.x1, b.x1, t), lerp(a.y1, b.y1, t),
		lerp(a.gap, b.gap, t), lerp(a.fs, b.fs, t), lerp(a.line, b.line, t),
	}
}

type wheelPos struct {
	anchorX float64 // 左列=右边界（右对齐），右列=左边界（左对齐）
	baseY   float64 // 压力基线
	fs      float64 // 压力字号
	lineY   float64
	lineLen float64
	tempY   float64
	tempFS  float64
	left    bool
}

// layoutWheels 顺序 0=FL 1=FR 2=RL 3=RR
func layoutWheels(c carRect, park bool) [4]wheelPos {
	h := c.y1 - c.y0
	var yF, yR, inset float64
	if park {
		yF, yR, inset = c.y0+0.24*h, c.y0+0.78*h, 8
	} else {
		yF, yR, inset = c.y0+0.20*h, c.y0+0.64*h, 0
	}
	xL := c.x0 - c.gap + inset
	xR := c.x1 + c.gap - inset
	var out [4]wheelPos
	for i := 0; i < 4; i++ {
		front := i < 2
		left := i == 0 || i == 2
		p := wheelPos{left: left}
		if left {
			p.anchorX = xL
		} else {
			p.anchorX = xR
		}
		if front {
			p.baseY = yF
		} else {
			p.baseY = yR
		}
		ts := c.fs
		if front && !park {
			ts = 0.85 * c.fs
		}
		p.fs = ts
		p.lineY = p.baseY + 0.3*ts
		p.lineLen = c.line * ts / c.fs
		k := 1.18
		if park {
			k = 1.02
		}
		p.tempY = p.baseY + k*ts
		p.tempFS = 0.78 * ts
		out[i] = p
	}
	return out
}

// 底部区域（电瓶 / 车内 / 行程）与导航卡片，与原厂一致
const (
	voltX      = 430.0
	voltBottom = 706.0 // 整体下移 8px（原 698，用户反馈偏上）
	tripCenter = 960.0
	tripBottom = 708.0 // 与 voltBottom 同步下移 8px（原 700）
	navCardW   = 200.0
	navCardH   = 72.0
	// 位置按用户要求保持在原处（右侧，原厂导航面板之上），不要改。
	navCardY  = 553.0
	navCardX1 = 1311.0
	navCardX2 = 1614.0
)

// 红绿灯小件：横向区域沿用原厂胶囊的宽度和中心（x 875~1045，中心 960
// 恰为屏宽中点），纵向按内容扩展 —— 只画左转与直行。
//
// 版式：灯盘排在同一水平行，秒数落在各自盘的正下方（居中对齐）。
// 盘内只放方向箭头，箭头可以画满；秒数脱离圆盘后能放大到 30px。原来把秒数
// 塞进 Ø48 盘里、或者赶到盘右侧，都是和箭头抢横向空间，两个都委屈。
//
//	卡片    x[875, 1045]  y[164, 272]  → 170 × 108，圆角 24
//	灯盘    Ø50，圆心 y=201；圆心 x=923.5 / 996.5（左转 / 直行）
//	秒数    盘正下方，中心 y=249，两位 30px、三位降到 24px
//
// 高度取 108 而不是 12+50+8+30+12=112：数字的字体盒下缘比笔画低约 8px，
// 按盒子算底部会松出去，按笔画（大写高 ≈ 0.72em）算上下留白才都是 12。
const (
	tlCardX = 875.0 // 左边界：与原厂胶囊一致，横向区域不变
	tlCardY = 164.0 // 上边界：沿用原厂胶囊顶边，向下扩展
	tlCardW = 170.0
	tlCardH = 108.0
	tlCardR = 24.0

	tlPad    = 12.0                    // 卡片内边距
	tlDiscD  = 50.0                    // 灯盘直径
	tlNumGap = 8.0                     // 盘底到秒数之间
	tlNumFS  = 30.0                    // 秒数基准字号（两位）
	tlSlotW  = (tlCardW - 2*tlPad) / 2 // 每个方向占的列宽 = 73
)

// tlSlotDirs 渲染槽位 → 方向码。只有左转与直行；原厂第三位是右转，
// 数据源（com.xiaopeng.light 日志）不提供，所以不留空位，省下的宽度给秒数。
var tlSlotDirs = [2]int{tlDirLeft, tlDirStraight}
