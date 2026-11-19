package main

// 绘制：一次画完整帧，画到哪就把 dirty 累加到哪；守护进程只 blit dirty 区域。
// 配色 / 字号 / 位置与原厂一致（日间 #1C2830 系，夜间 #E8F0F4 系）。

import (
	"fmt"
	"image/color"
	"math"
	"strings"

	"golang.org/x/image/font"
)

var (
	cRed    = color.RGBA{0xFF, 0x5A, 0x5A, 0xFF}
	cOrange = color.RGBA{0xE4, 0xAB, 0x6F, 0xFF}

	dayValue  = color.RGBA{0x1C, 0x28, 0x30, 0xFF}
	dayLabel  = color.RGBA{0x5E, 0x6B, 0x78, 0xFF}
	dayAccent = color.RGBA{0x39, 0x43, 0x4C, 0xFF}
	dayNaN    = color.RGBA{0xA9, 0xB6, 0xBF, 0xFF}
	dayLine   = color.RGBA{0x6E, 0x77, 0x80, 0xFF}

	nightValue  = color.RGBA{0xE8, 0xF0, 0xF4, 0xFF}
	nightLabel  = color.RGBA{0x8A, 0x97, 0xA0, 0xFF}
	nightAccent = color.RGBA{0xA9, 0xB6, 0xBF, 0xFF}
	nightNaN    = color.RGBA{0xA9, 0xB6, 0xBF, 0xFF}
	nightLine   = color.RGBA{0x8A, 0x94, 0x9C, 0xFF}

	// 歌词背景卡片：色值取自车机中控屏「正在播放条」下方那层底色
	// （夜间 #191A1F、白天 #DFE3EB），跟仪表昼夜自动切换。
	//
	// 这两个常量只作**兜底**：正常路径是 bgmap.go 里的原厂底图色值表——
	// 原厂那一层不是纯色而是横向渐变（夜间 #15171A→#090B0F、白天 #E7EDF8→#DFE8F5），
	// 平涂只能对上渐变上的某一个 x，两端必然一侧亮一侧暗，卡片就成一块贴上去的矩形
	// （实机取证：夜间右边缘台阶 +13.1、白天左边缘 −9.7）。
	lyricBgNight = color.RGBA{0x19, 0x1A, 0x1F, 0xFF}
	lyricBgDay   = color.RGBA{0xDF, 0xE3, 0xEB, 0xFF}

	navFillDay   = color.RGBA{0xF2, 0xF6, 0xFA, 0xFF}
	navBorderDay = color.RGBA{0xC9, 0xD3, 0xDC, 0xFF}
	navFillNight = color.RGBA{0x11, 0x15, 0x1A, 0xFF}
	navBorderNit = color.RGBA{0x31, 0x3A, 0x42, 0xFF}
	navLabelDay  = color.RGBA{0x5E, 0x6B, 0x78, 0xFF}
	navLabelNit  = color.RGBA{0x98, 0xA4, 0xAD, 0xFF}

	bsdRed = color.RGBA{0xE5, 0x30, 0x30, 0xFF}

	panelBG = color.RGBA{0x11, 0x16, 0x1C, 0xFF}

	// 红绿灯卡片：深色玻璃质感浮层。色值锚点取原厂 overlay 实测
	// （红 #E23E52、黄 #F2AF3C、绿 #008A5F）。
	tlCardTop  = color.RGBA{0x17, 0x1E, 0x28, 0xF2} // 卡片顶：偏亮的蓝灰
	tlCardBot  = color.RGBA{0x07, 0x0A, 0x0E, 0xF2} // 卡片底：近黑（不能纯黑，会被色键透掉）
	tlCardEdge = color.RGBA{0xFF, 0xFF, 0xFF, 0x22} // 外描边
	tlCardHi   = color.RGBA{0xFF, 0xFF, 0xFF, 0x10} // 内侧上缘高光
	tlCardShad = color.RGBA{0x00, 0x00, 0x00, 0x4A} // 投影
	tlNumOn    = color.RGBA{0xF5, 0xFA, 0xFD, 0xFF} // 秒数
	tlDiscRing = color.RGBA{0xFF, 0xFF, 0xFF, 0x18} // 盘内圈细描边
	tlArrowOn  = color.RGBA{0xFF, 0xFF, 0xFF, 0xFF} // 盘内白箭头

	tlRedC    = color.RGBA{0xE2, 0x3E, 0x52, 0xFF} // 红 #E23E52 → RGB565 0xC896
	tlYellowC = color.RGBA{0xF2, 0xAF, 0x3C, 0xFF} // 黄 #F2AF3C → 0xAD40
	tlGreenC  = color.RGBA{0x00, 0x8A, 0x5F, 0xFF} // 绿 #008A5F → 0x0450

	// 白天：跟原厂日间导航卡片（navFillDay #F2F6FA）同一套语言——浅底、深字、
	// 淡灰投影。前景层按「纯黑」做色键，浅底不会触发；投影刻意压得很淡，
	// 否则白底上会糊出一块黑影。
	tlCardTopDay  = color.RGBA{0xFF, 0xFF, 0xFF, 0xFF}
	tlCardBotDay  = color.RGBA{0xE7, 0xED, 0xF4, 0xFF}
	tlCardEdgeDay = color.RGBA{0xB7, 0xC5, 0xD2, 0xFF}
	tlCardHiDay   = color.RGBA{0xFF, 0xFF, 0xFF, 0x50}
	tlCardShadDay = color.RGBA{0x1C, 0x28, 0x30, 0x20}
	tlNumOnDay    = color.RGBA{0x1C, 0x28, 0x30, 0xFF} // = dayValue，秒数改深色
	tlDiscRingDay = color.RGBA{0xFF, 0xFF, 0xFF, 0x38} // 彩盘上白细边稍强一点
)

// tlPalette 红绿灯卡片昼夜配色。夜间沿用最初的深色玻璃浮层，白天换浅底深字。
// 灯盘三色和盘内白箭头两种模式共用：白底上彩色盘依然醒目，不需要另调色值。
type tlPalette struct {
	top, bot, edge, hi, shadow color.RGBA
	num, discRing, arrow       color.RGBA
	glowA                      uint8
}

func tlPal(night bool) tlPalette {
	if night {
		return tlPalette{
			top: tlCardTop, bot: tlCardBot, edge: tlCardEdge, hi: tlCardHi,
			shadow: tlCardShad, num: tlNumOn, discRing: tlDiscRing,
			arrow: tlArrowOn, glowA: 0x38,
		}
	}
	return tlPalette{
		top: tlCardTopDay, bot: tlCardBotDay, edge: tlCardEdgeDay, hi: tlCardHiDay,
		shadow: tlCardShadDay, num: tlNumOnDay, discRing: tlDiscRingDay,
		arrow: tlArrowOn, glowA: 0x20,
	}
}

type palette struct {
	value, label, accent, nan, line color.RGBA
}

func pal(night bool) palette {
	if night {
		return palette{nightValue, nightLabel, nightAccent, nightNaN, nightLine}
	}
	return palette{dayValue, dayLabel, dayAccent, dayNaN, dayLine}
}

// wheelData 单轮数据
type wheelData struct {
	bar    float64
	barOK  bool
	temp   float64
	tempOK bool
}

func levelColor(bar float64, night bool) color.RGBA {
	switch {
	case bar < 1.8 || bar > 3.2:
		return cRed
	case bar < 2.0 || bar > 3.0:
		return cOrange
	default:
		if night {
			return nightValue
		}
		return dayValue
	}
}

// drawTemp 画 "21℃"：数字大、℃ 小一号，整体按 align 对齐
func (c *canvas) drawTemp(v float64, x, baseline, fs float64, col color.RGBA, align int) {
	num := fmt.Sprintf("%.0f", v)
	nw := measure(num, fs)
	uw := measure("℃", fs*0.62)
	total := nw + 1 + uw
	start := x
	switch align {
	case -1:
		start = x - total
	case 0:
		start = x - total/2
	}
	c.drawText(num, start, baseline, fs, col, 1, false)
	c.drawText("℃", start+nw+1, baseline, fs*0.62, col, 1, false)
}

// paintFrame 画整帧。park=true 用驻车布局；tAnim 为行驶态收拢进度 0..1
func paintFrame(cv *canvas, cfg Config, s snapshot, park bool, tAnim float64, night bool) {
	// 充电页是"换页"而不是叠加：开启且正在充电时整屏接管，其余组件一概不画
	// （对齐原厂 charging.kzb 置顶并隐藏底盘的行为）。
	// 充电页：只在"充电中"或"调试开关打开"时接管。
	// 调试开关单独生效——之前要求同时打开「充电界面」，用户点了调试看不到效果。
	if (cfg.Features.ChargePage && s.chg.on) || cfg.Features.ChargeDebug {
		if paintCharge(cv, cfg, s, tAnim) {
			return
		}
		// 没有可用背景（车机断开 / 无帧）：不接管，继续画常规组件，
		// 让原厂仪表画面露出来，而不是留一片空白。
	}
	P := pal(night)
	car := carPark
	if !park {
		car = lerpCar(carNormal, carCompact, tAnim)
	}

	// 整体偏移/缩放（车机端"位置微调"）
	applyT := func(x, y float64) (float64, float64) {
		cx, cy := frameW/2.0, frameH/2.0
		return cx + (x-cx)*cfg.Layout.Scale + cfg.Layout.DX, cy + (y-cy)*cfg.Layout.Scale + cfg.Layout.DY
	}
	carT := func(r carRect) carRect {
		x0, y0 := applyT(r.x0, r.y0)
		x1, y1 := applyT(r.x1, r.y1)
		return carRect{x0, y0, x1, y1, r.gap * cfg.Layout.Scale, r.fs * cfg.Layout.Scale, r.line * cfg.Layout.Scale}
	}
	car = carT(car)

	paintTPMS(cv, cfg, s, car, park, P, night)
	paintBottom(cv, cfg, s, P, night, applyT)
	paintBSD(cv, cfg, s, car, park)
	paintNav(cv, cfg, s, night, applyT)
	paintTrafficLight(cv, cfg, s, park, night, applyT)
	paintLyric(cv, cfg, s, night, applyT)
	if cfg.Features.Debug {
		paintDebug(cv, cfg, s, park, tAnim, night)
	}
}

func paintTPMS(cv *canvas, cfg Config, s snapshot, car carRect, park bool, P palette, night bool) {
	// 对齐原版：胎压/胎温按档位各一个开关，每个开关管该档位下「胎压 + 胎温」一起
	enabled := cfg.Features.TPMSD
	if park {
		enabled = cfg.Features.TPMSP
	}
	if !enabled {
		return
	}
	if !s.anyBar() && !s.anyTemp() && !cfg.Features.Debug {
		return // 压力、温度都没有有效值：整块不画，绝不显示假数据
	}
	pos := layoutWheels(car, park)
	for i := 0; i < 4; i++ {
		p := pos[i]
		d := s.wheels[i]
		align := 1
		if p.left {
			align = -1
		}
		if d.barOK {
			// 数据来源不同，精度也不同：总线 0.1bar → 一位小数（2.4）；
			// 车机 CarProperty 0.01bar → 两位小数（2.42）。位数本身就是来源标记。
			barFmt := "%.1f"
			if s.tpHi {
				barFmt = "%.2f"
			}
			cv.drawText(fmt.Sprintf(barFmt, d.bar), p.anchorX, p.baseY, p.fs, levelColor(d.bar, night), align, true)
		} else {
			cv.drawText("--", p.anchorX, p.baseY, p.fs, P.nan, align, true)
		}
		if p.left {
			cv.hline(p.anchorX-p.lineLen, p.anchorX, p.lineY, P.line)
		} else {
			cv.hline(p.anchorX, p.anchorX+p.lineLen, p.lineY, P.line)
		}
		if park {
			// 驻车：温度压在压力下面，空间充裕
			if d.tempOK {
				cv.drawTemp(d.temp, p.anchorX, p.tempY, p.tempFS, P.label, align)
			} else {
				cv.drawText("--", p.anchorX, p.tempY, p.tempFS, P.label, align, false)
			}
		} else {
			// 行驶：紧凑布局行距只有 15px，温度改到压力同一行，避免上下行打架
			tfs := p.fs * 0.62
			tstr := "--"
			if d.tempOK {
				tstr = fmt.Sprintf("%.0f℃", d.temp)
			}
			gap := p.fs * 0.28
			if p.left {
				cv.drawText(tstr, p.anchorX-p.fs*2.05-gap, p.baseY, tfs, P.label, -1, false)
			} else {
				cv.drawText(tstr, p.anchorX+p.fs*2.05+gap, p.baseY, tfs, P.label, 1, false)
			}
		}
	}
}

func paintBottom(cv *canvas, cfg Config, s snapshot, P palette, night bool, applyT func(x, y float64) (float64, float64)) {
	// 左下：电瓶电压 / 车内温度（二选一，与原 App 一致；电瓶优先）
	if cfg.Features.Volt && s.voltOK {
		x, y := applyT(voltX, voltBottom)
		x = cv.drawBottom("电瓶", x, y, 26, P.label, 1, false)
		x = cv.drawBottom(fmt.Sprintf("%.1f", s.volt), x+10, y, 34, P.value, 1, true)
		cv.drawBottom("V", x+3, y, 22, P.accent, 1, false)
	} else if cfg.Features.Cabin && s.cabinOK {
		x, y := applyT(voltX, voltBottom)
		x = cv.drawBottom("车内", x, y, 26, P.label, 1, false)
		x = cv.drawBottom(fmt.Sprintf("%.0f", s.cabin), x+10, y, 34, P.value, 1, true)
		cv.drawBottom("℃", x+3, y, 22, P.accent, 1, false)
	}

	// 底部中央：行程（里程 / 时间 / 平均车速 / 能耗）
	// 歌词选了「下方」时这块区域要交给歌词，行程行整块让位（两者占同一位置，互斥）。
	if cfg.Features.LyricBottom && cfg.Features.Lyric {
		return
	}
	t := cfg.Features.Trip
	if !(t.Dist || t.Time || t.Speed || t.Kwh) || !s.tripOK {
		return
	}
	type seg struct {
		text string
		w    bool    // true = 数值（加粗、主色）
		k    float64 // 字号系数
	}
	var segs []seg
	add := func(sg seg) { segs = append(segs, sg) }
	if t.Dist {
		add(seg{"里程", false, 0.82})
		add(seg{" ", false, 1})
		add(seg{fmt1(s.tripKm), true, 1})
		add(seg{" ", false, 0.6})
		add(seg{"km", false, 0.72})
	}
	if t.Time {
		if len(segs) > 0 {
			add(seg{" ", false, 1})
		}
		add(seg{fmtInt(s.tripMin), true, 1})
		add(seg{" ", false, 0.6})
		add(seg{"min", false, 0.72})
	}
	if t.Speed {
		if len(segs) > 0 {
			add(seg{" ", false, 1})
		}
		add(seg{"平均", false, 0.82})
		add(seg{" ", false, 1})
		add(seg{fmtInt(s.tripAvg), true, 1})
		add(seg{" ", false, 0.6})
		add(seg{"km/h", false, 0.72})
	}
	if t.Kwh {
		if len(segs) > 0 {
			add(seg{" ", false, 1})
		}
		add(seg{fmt1(s.tripKwh), true, 1})
		add(seg{" ", false, 0.6})
		add(seg{"kWh/100km", false, 0.72})
	}
	total := 0.0
	for _, sg := range segs {
		total += measureWith(sg.text, sg.k*34, sg.w)
	}
	cx, cy := applyT(tripCenter, tripBottom)
	x := cx - total/2
	for _, sg := range segs {
		col := P.label
		if sg.w {
			col = P.value
		}
		x = cv.drawBottom(sg.text, x, cy, sg.k*34, col, 1, sg.w)
	}
}

func paintBSD(cv *canvas, cfg Config, s snapshot, car carRect, park bool) {
	// 原版：盲区提醒(bsd)=按真实信号显示；盲区调试(debug)=弧线恒显示（忽略真实信号）
	if park {
		return
	}
	if !cfg.Features.BSD && !cfg.Features.Debug {
		return
	}
	force := cfg.Features.Debug // 盲区调试：左右都恒显，便于观察显示效果
	showL := force || s.bsdL
	showR := force || s.bsdR
	if !showL && !showR {
		return
	}
	cy := car.y1 - 13
	rx, ry := 35.0, 23.0
	if showL {
		cv.strokeArc(car.x0+5, cy, rx, ry, 85, 225, 5, bsdRed)
	}
	if showR {
		cv.strokeArc(car.x1-5, cy, -rx, ry, 85, 225, 5, bsdRed)
	}
}

// paintNav 两张卡片：剩余里程/时间、到达时间
func paintNav(cv *canvas, cfg Config, s snapshot, night bool, applyT func(x, y float64) (float64, float64)) {
	if !cfg.Features.Nav || !s.navOK {
		return
	}
	fill, border, label := navFillDay, navBorderDay, navLabelDay
	value, unit := dayValue, dayAccent
	if night {
		fill, border, label = navFillNight, navBorderNit, navLabelNit
		value, unit = nightValue, nightAccent
	}

	// 卡片 1：剩余里程 / 时间
	var segs []struct {
		text string
		w    bool
	}
	dist := s.nav.distM / 1000
	switch {
	case dist < 1:
		segs = append(segs, struct {
			text string
			w    bool
		}{fmt.Sprintf("%d", int(math.Max(0, math.Round(s.nav.distM)))), true})
		segs = append(segs, struct {
			text string
			w    bool
		}{"m", false})
	case dist < 10:
		segs = append(segs, struct {
			text string
			w    bool
		}{fmt.Sprintf("%.1f", dist), true})
		segs = append(segs, struct {
			text string
			w    bool
		}{"km", false})
	default:
		segs = append(segs, struct {
			text string
			w    bool
		}{fmt.Sprintf("%d", int(math.Round(dist))), true})
		segs = append(segs, struct {
			text string
			w    bool
		}{"km", false})
	}
	segs = append(segs, struct {
		text string
		w    bool
	}{" / ", false})
	mins := int(math.Max(0, math.Round(s.nav.timeS/60)))
	if mins >= 60 {
		segs = append(segs, struct {
			text string
			w    bool
		}{fmt.Sprintf("%d", mins/60), true})
		segs = append(segs, struct {
			text string
			w    bool
		}{"h", false})
		if mins%60 > 0 {
			segs = append(segs, struct {
				text string
				w    bool
			}{fmt.Sprintf("%d", mins%60), true})
			segs = append(segs, struct {
				text string
				w    bool
			}{"m", false})
		}
	} else {
		segs = append(segs, struct {
			text string
			w    bool
		}{fmt.Sprintf("%d", mins), true})
		segs = append(segs, struct {
			text string
			w    bool
		}{"m", false})
	}
	x, y := applyT(navCardX1, navCardY)
	drawCard(cv, x, y, "剩余里程 / 时间", segs, fill, border, label, value, unit)

	// 卡片 2：到达时间
	var segs2 []struct {
		text string
		w    bool
	}
	arr := s.nav.arrive
	if len(arr) == 5 && arr[2] == ':' {
		segs2 = append(segs2, struct {
			text string
			w    bool
		}{arr[:2], true})
		segs2 = append(segs2, struct {
			text string
			w    bool
		}{" : ", false})
		segs2 = append(segs2, struct {
			text string
			w    bool
		}{arr[3:], true})
	} else {
		segs2 = append(segs2, struct {
			text string
			w    bool
		}{"-- : --", true})
	}
	x2, y2 := applyT(navCardX2, navCardY)
	drawCard(cv, x2, y2, "到达时间", segs2, fill, border, label, value, unit)
}

// paintTrafficLight 红绿灯小件：横向仍占原厂胶囊位（x 875~1045），纵向扩成卡片。
//
// 版式：灯盘排在同一水平行，秒数落在各自盘的正下方。盘内只放方向箭头，秒数
// 脱离圆盘后能放大到 30px —— 原来箭头和数字抢同一块横向空间，两个都委屈。
//
// 只有有相位的方向才画：
//   - 两个都有 → 左转在左列、直行在右列，各占一列
//   - 只有一个 → 灯居中，卡片尺寸不变，也不给缺的那个方向留灰盘和破折号
//     （留个灰盘容易被误读成"这个方向是红灯"，而且空着更干净）
//   - 一个都没有 → 整块不画
//
// park=true 直接不画：原厂这块是行车信息带的一部分，停车/充电时车模会放大到
// 屏幕中上部（carPark y0=130），画上去会压在车模顶上。实车观察也是"只有 D 档
// 才有"。数据层其实已经天然满足（停车时 com.xiaopeng.light 只写短格式、没有
// phases），这里再挡一道，避免残留的旧相位在挂 P 档后还挂在屏上。
func paintTrafficLight(cv *canvas, cfg Config, s snapshot, park, night bool, applyT func(x, y float64) (float64, float64)) {
	// 调试模拟（ext.tl.demo=true）时放开驻车限制，方便停车调版式；
	// 真实数据仍严格只在 D 档出现，避免挂在放大的驻车车模上。
	if !cfg.Features.TL || !s.tlOK || (park && !s.tlDemo) {
		return
	}
	P := tlPal(night)
	// 先挑出真正有相位的方向。槽位顺序固定（左转、直行），所以两个时左右不变。
	var show []tlLight
	for i, dir := range tlSlotDirs {
		lg := s.tl.lights[i]
		lg.dir = dir // 熄灭时结构体是零值，方向必须按槽位补，否则箭头形状会错
		if lg.cur != 0 {
			show = append(show, lg)
		}
	}
	if len(show) == 0 {
		return
	}

	x0, y0 := applyT(tlCardX, tlCardY)
	x1, y1 := applyT(tlCardX+tlCardW, tlCardY+tlCardH)
	sc := (x1 - x0) / tlCardW
	if sc <= 0 || y1 <= y0 {
		return
	}
	r := tlCardR * sc

	// 投影：向下偏 2px 的圆角块，让卡片从地图上浮起来（白天用淡灰，夜间用黑）
	cv.fillRoundRectAA(x0+sc, y0+2*sc, x1+sc, y1+3*sc, r, P.shadow)
	// 底色纵向渐变（顶亮底暗）。注意不能是纯黑 #000000 —— 前景层按像素值 0 做
	// 色键，纯黑会被当成透明直接透出原厂画面。白天是浅底，天然不触发色键。
	cv.fillRoundRectVGrad(x0, y0, x1, y1, r, P.top, P.bot)
	// 外缘 1px 亮线 + 内圈 1px 高光：夜间做玻璃边缘，白天做浅卡片的分界
	cv.strokeRoundRectAA(x0, y0, x1, y1, r, sc, P.edge)
	cv.strokeRoundRectAA(x0+sc, y0+sc, x1-sc, y1-sc, r-sc, sc, P.hi)

	discCY := y0 + (tlPad+tlDiscD/2)*sc
	numCY := y0 + (tlPad+tlDiscD+tlNumGap+tlNumFS/2)*sc

	if len(show) == 1 {
		// 单相位：整块居中，宁可两侧留白也不摆一个假的空灯位
		cx := (x0 + x1) / 2
		drawTLDisc(cv, cfg.Features.TLStyle, cx, discCY, tlDiscD*sc/2, sc, show[0], P)
		drawTLNum(cv, cx, numCY, tlSlotW*sc, sc, show[0], P)
		return
	}

	for i, lg := range show {
		cx := x0 + (tlPad+tlSlotW*(float64(i)+0.5))*sc
		drawTLDisc(cv, cfg.Features.TLStyle, cx, discCY, tlDiscD*sc/2, sc, lg, P)
		drawTLNum(cv, cx, numCY, tlSlotW*sc, sc, lg, P)
	}
}

func tlColorOf(cur int) color.RGBA {
	switch cur {
	case tlRed:
		return tlRedC
	case tlYellow:
		return tlYellowC
	case tlGreen:
		return tlGreenC
	}
	return tlRedC
}

// shade 整体提亮（k>0）或压暗（k<0），给灯盘做纵向渐深用。
func shade(c color.RGBA, k float64) color.RGBA {
	f := func(v uint8) uint8 {
		if k >= 0 {
			return uint8(float64(v) + (255-float64(v))*k)
		}
		return uint8(float64(v) * (1 + k))
	}
	return color.RGBA{f(c.R), f(c.G), f(c.B), c.A}
}

// drawTLDisc 画一个点亮的灯盘，外面套一圈同色柔光。盘内只放方向箭头，不放文字。
// 熄灭态不再绘制（单相位时另一个方向直接不占位），所以这里不处理 cur==0。
func drawTLDisc(cv *canvas, style string, cx, cy, r, sc float64, lg tlLight, P tlPalette) {
	if r <= 0 || lg.cur == 0 {
		return
	}
	d := 2 * r
	dx, dy := cx-r, cy-r

	col := tlColorOf(lg.cur)
	// 点亮盘外面套一圈同色柔光。夜间靠这点辉光衬出"亮着"，
	// 白天浅底上把辉光压弱，免得彩盘外糊出一圈脏边。
	glow := shade(col, 0.35)
	glow.A = P.glowA
	cv.strokeRoundRectAA(dx-2*sc, dy-2*sc, dx+d+2*sc, dy+d+2*sc, r+2*sc, 2.5*sc, glow)

	if style == "icon" {
		// 素材原样：径向渐变盘 + 白箭头
		if im := tlIcon(lg.dir, lg.cur); im != nil {
			cv.drawImg(im, dx, dy, d, d)
		} else {
			cv.fillRoundRectAA(dx, dy, dx+d, dy+d, r, col)
		}
		return
	}

	// native：矢量盘纵向渐深做出球面感；箭头仍取素材剪影，保证两种样式同形
	cv.fillRoundRectVGrad(dx, dy, dx+d, dy+d, r, shade(col, 0.16), shade(col, -0.12))
	if im := tlIcon(lg.dir, lg.cur); im != nil {
		cv.drawImgArrow(im, dx, dy, d, d, P.arrow)
	}
	cv.strokeRoundRectAA(dx, dy, dx+d, dy+d, r, sc, P.discRing)
}

// drawTLNum 画盘下方的秒数，以 cx 居中。avail 是可用列宽（放不下才缩字号）。
//
// 三位数（如 120s）不强制降字号：盘下横向有整列 73px 可用，30px 的三位数
// 只有 51px，放得下。硬降成 24px 会让左右两列数字忽大忽小，反而不齐。
func drawTLNum(cv *canvas, cx, cy, avail, sc float64, lg tlLight, P tlPalette) {
	if lg.cur == 0 {
		return
	}
	txt := fmtInt(lg.sec)
	fs := tlNumFS * sc
	if avail > 4 {
		if w := measureWith(txt, fs, true); w > avail*0.92 {
			fs = fs * avail * 0.92 / w
		}
	}
	cv.drawText(txt, cx, cy+fs*0.36, fs, P.num, 0, true)
}

func drawCard(cv *canvas, x, y float64, title string, segs []struct {
	text string
	w    bool
}, fill, border, label, value, unit color.RGBA) {
	sc := 1.0 // 卡片按整体缩放裁剪，这里保持原尺寸
	w, h := navCardW*sc, navCardH*sc
	cv.fillRoundRect(x, y, x+w, y+h, 12, fill)
	cv.strokeRoundRect(x, y, x+w, y+h, 12, 2, border)
	cv.drawText(title, x+w/2, y+9+ascentOf(fCJK, 20), 20, label, 0, false)

	total := 0.0
	for _, sg := range segs {
		if sg.w {
			total += measureWith(sg.text, 34, true)
		} else {
			total += measure(sg.text, 20)
		}
	}
	const maxW = 168.0 // 卡片内左右各留 16px，避免压到描边
	k := 1.0
	if total > maxW {
		k = maxW / total
	}
	if k < 0.5 {
		k = 0.5
	}
	cx := x + w/2 - total*k/2
	for _, sg := range segs {
		if sg.w {
			cx = cv.drawText(sg.text, cx, y+h-10, 34*k, value, 1, true)
		} else {
			cx = cv.drawText(sg.text, cx, y+h-10, 20*k, unit, 1, false)
		}
	}
}

func paintDebug(cv *canvas, cfg Config, s snapshot, park bool, tAnim float64, night bool) {
	x0, y0 := 24.0, 40.0
	w, h := 560.0, 150.0
	cv.blendPanel(x0, y0, x0+w, y0+h, panelBG)
	txt := color.RGBA{0xE8, 0xF0, 0xF4, 0xFF}
	dim := color.RGBA{0x8A, 0x97, 0xA0, 0xFF}
	ok := color.RGBA{0x2B, 0xD8, 0x8F, 0xFF}
	bad := color.RGBA{0xFF, 0x5A, 0x5A, 0xFF}

	line := func(i int, str string, col color.RGBA) {
		cv.drawText(str, x0+14, y0+26+float64(i)*20, 16, col, 1, false)
	}
	layout := "PARK"
	if !park {
		layout = "DRIVE"
	}
	dn := "DAY"
	if night {
		dn = "NIGHT"
	}
	busC, busS := ok, "BUS OK"
	if !s.busOK {
		busC, busS = bad, "BUS DOWN"
	}
	line(0, fmt.Sprintf("P7 ASSIST %s   mask=%d   enabled=%v", appVersion, cfg.mask(), cfg.Enabled), txt)
	line(1, fmt.Sprintf("%s  %s  %s  t=%.2f  frames=%d", busS, layout, dn, tAnim, s.frames), busC)
	line(2, fmt.Sprintf("gear=%d  speed=%.1f  cabin=%.1f  ext=%dms", s.gear, s.speed, s.cabin, s.extAgeMs), dim)
	line(3, fmt.Sprintf("TPMS  %.2f  %.2f  %.2f  %.2f", s.wheels[0].bar, s.wheels[1].bar, s.wheels[2].bar, s.wheels[3].bar), dim)
	line(4, fmt.Sprintf("TEMP  %.0f  %.0f  %.0f  %.0f", s.wheels[0].temp, s.wheels[1].temp, s.wheels[2].temp, s.wheels[3].temp), dim)
	line(5, fmt.Sprintf("NAV age=%dms  BSD L=%v R=%v  dy=%.0f", s.navAgeMs, s.bsdL, s.bsdR, cfg.Layout.DY), dim)
}

func fmt1(v float64) string {
	if math.IsNaN(v) || v < 0 {
		return "--"
	}
	return fmt.Sprintf("%.1f", v)
}

// ---------- 歌词（左上角）----------
//
// 只负责"怎么显示"：数据仍是车机 ext.json 推来的 cur/next/title/artist。
//
// 旧版是三行裸文字直接压在原厂画面上，字号跳变、没有视觉锚点，观感很"贴"。
// 现在改成有容器、有层次的信息块：
//
//	歌名行（小字 + 强调色竖条） / 当前行（大字） / 下一行（小字）
//
// 三套候选样式，靠 lyricStyleMode 切换（1=竖条 2=玻璃卡片 3=渐变遮罩）。
var lyricStyleMode = 1

// 歌词配色：夜间用浅色文字，日间用深色文字。
// accent 是原厂 READY 指示的绿（夜间取自实机 #1DAE65），竖条与「正在唱」的这句都用它，
// 这样歌词的强调色和仪表自身的状态色是同一个绿，不会出现两种青绿打架。
type lyricPalette struct {
	text, dim, faint, accent, fill, border color.RGBA
}

func lyricPal(night bool) lyricPalette {
	if night {
		return lyricPalette{
			text:   color.RGBA{0xEC, 0xF3, 0xF7, 0xFF},
			dim:    color.RGBA{0xB4, 0xC0, 0xC9, 0xFF},
			faint:  color.RGBA{0x9D, 0xAA, 0xB4, 0xFF},
			accent: color.RGBA{0x1D, 0xAE, 0x65, 0xFF}, // = 原厂 READY 绿（实机取样）
			fill:   color.RGBA{0xFF, 0xFF, 0xFF, 0x14}, // 提亮玻璃（深底上比加深更自然）
			border: color.RGBA{0xFF, 0xFF, 0xFF, 0x22},
		}
	}
	return lyricPalette{
		text:  color.RGBA{0x16, 0x21, 0x29, 0xFF},
		dim:   color.RGBA{0x46, 0x53, 0x5E, 0xFF},
		faint: color.RGBA{0x5E, 0x6B, 0x76, 0xFF},
		// 日间底是浅灰（#E9EDF2 一带），READY 那个绿直接放上去对比只有 2.5:1，
		// 同色相压暗一档到 3.9:1，保持"同一个绿"的观感又不至于发灰。
		accent: color.RGBA{0x0E, 0x8A, 0x4E, 0xFF},
		fill:   color.RGBA{0xF7, 0xFA, 0xFC, 0xCC},
		border: color.RGBA{0x1C, 0x28, 0x30, 0x22},
	}
}

// paintLyric 左上角歌词块：歌名 + 当前行 + 下一行 —— 布局沿用旧版。
//
// 区域限制（旧版口径）：限宽 = 仪表宽 1/3（≈610）、限高 = 仪表高 1/4（180）；
// 优先级 当前行 > 歌名 > 下一行：先保证「歌名 + 当前行（可换行，最多 2 行）」，
// 放不下就先砍歌名、再把当前行压到 1 行，剩下的空间才给下一行预览。
//
// 与接力版的区别只在配色：当前行用原厂 READY 绿强调（用户要求保留），
// 歌名与下一行仍用旧版的副色；不画竖条、不画卡片、不做暂停隐藏。
func paintLyric(cv *canvas, cfg Config, s snapshot, night bool, applyT func(x, y float64) (float64, float64)) {
	if !cfg.Features.Lyric || !s.lyricOK {
		return
	}
	cur := strings.TrimSpace(s.lyric.cur)
	if cur == "" {
		return
	}
	// 暂停播放就整块隐藏（开关开时）。放在分底部模式之前，两种布局都生效。
	if cfg.Features.LyricHideOnPause && !s.lyric.playing {
		return
	}
	// 用户选了「歌词显示在下方」：改走底部两行布局（与行程行互斥）
	if cfg.Features.LyricBottom {
		paintLyricBottom(cv, s, night)
		return
	}

	// 左上角歌词块的整体拖动位置（车机大屏「歌词位置」页设置）。
	// 只在这段生效，defer 清零，绝不影响底部布局与同帧其它组件。
	cv.ox, cv.oy = cfg.LyricPos.DX, cfg.LyricPos.DY
	defer func() { cv.ox, cv.oy = 0, 0 }()

	p := lyricPal(night)
	// 主行用绿（强调"正在唱"），歌名/下一行用旧版副色
	dim := nightLabel
	if !night {
		dim = dayLabel
	}

	// 整体缩放：块内字号、行高、间距、限宽限高一律乘同一个比例 k。
	// left/top 是块的左上角锚点，不参与缩放——用户拖到的位置就是块的左上角。
	// 包围盒在 config.go 里按 640k×186k 校验，与这里 maxW≈610k 是同一套尺度。
	k := cfg.LyricPos.Scale
	if k <= 0 {
		k = 1
	}
	// 区域限宽：仪表宽度的 1/3；限高：仪表高度的 1/4
	maxW := (frameW/3 - 30) * k // 原始 ≈ 610
	maxH := (frameH / 4) * k    // 原始 180
	left := 90.0
	top := 18.0

	// 三档字号：当前行 42 是基准，歌名 28（≈0.67×）、下一行 34（≈0.81×当前行）。
	// 原来歌名 24、下一行 26，实车抓帧笔画高只有 22 / 23px——两行小字糊成一片，
	// 而且下一行比歌名还小一号、层次是反的。现在读起来是「歌名 < 下一句 < 正在唱」。
	titleFS := 28.0 * k
	titleLH := 34.0 * k
	curFS := 42.0 * k
	curLH := 52.0 * k
	nextFS := 34.0 * k
	nextLH := 40.0 * k
	gap := 16.0 * k // 块间间距

	// 歌名 + 歌手
	title := strings.TrimSpace(s.lyric.title)
	if artist := strings.TrimSpace(s.lyric.artist); artist != "" {
		if title != "" {
			title += " · " + artist
		} else {
			title = artist
		}
	}
	next := strings.TrimSpace(s.lyric.next)

	// 优先级：当前歌词行 > 歌名 > 下一行预览。
	// 先按"歌名 1 行 + 当前行 N 行(N≤2)"排预算，确保这两项完整显示；剩下的才给下一行。
	curAll := wrapLyric(cur, curFS, maxW)
	curN := len(curAll)
	if curN > 2 {
		curN = 2
	}
	titleN := 0
	if title != "" {
		titleN = 1
	}

	// 歌名 + 当前行 占的高度
	head := float64(titleN)*(titleLH+gap) + float64(curN)*curLH
	// 如果歌名+当前行就超过限高，压缩歌名（歌名省略），优先当前行
	if head > maxH {
		titleN = 0
		head = float64(curN) * curLH
		// 还超就压缩当前行到 1 行
		if head > maxH {
			curN = 1
			head = curLH
		}
	}

	// 背景卡片：高度按这一帧实际会画的内容算（不是固定 186），避免底部大片留白。
	// 必须在文字之前画，且手动叠加 cv.ox/oy（fillRoundRectAA 不带块偏移）。
	//
	// 底部余量必须留够：行高 curLH(52) 小于「字号 + 下伸部」的实际占高
	// （42 + 42×0.4 = 58.8），每块差约 7px，三块叠起来最后一行会探出背景。
	// 原来只留 6k，实测第二行会露出来，这里放大到 20k。
	if cfg.Features.LyricBg {
		h := 0.0
		if titleN > 0 {
			h += titleLH + gap
		}
		h += float64(curN)*curLH + gap
		if next != "" && h+nextLH <= maxH {
			h += nextLH
		}
		// 底色取自原厂底图色值表（bgmap.go）：卡片占据哪块区域，就从底图上
		// 取哪块的色值，拖动时自动跟着变。拿不到色值表才退回固定色。
		bx0 := left - 30*k + cv.ox
		by0 := top - 14*k + cv.oy
		bx1 := left + maxW + 18*k + cv.ox
		by1 := top - 14*k + h + 20*k + cv.oy
		if !cv.fillLyricBg(bx0, by0, bx1, by1, 14*k, night) {
			col := lyricBgDay
			if night {
				col = lyricBgNight
			}
			cv.fillRoundRectAA(bx0, by0, bx1, by1, 14*k, col)
		}
	}

	// 对齐方式：「居中排布」开关决定块内文字是居中还是左对齐。
	// 居中时锚点取限宽区域中点（drawLyricWrapped 的 align=0 表示 x 是中心），
	// 块本身的位置、背景卡片宽度都不变，只是文字在框内居中。
	tx, talign := left, 1
	if cfg.Features.LyricCenter {
		tx, talign = left+maxW/2, 0
	}

	used := 0.0
	if titleN > 0 {
		cv.drawLyricWrapped(title, tx, top+titleFS, titleFS, titleLH, maxW, dim, talign, 1)
		top += titleLH + gap
		used += titleLH + gap
	}
	// 「当行」开头的强调竖条：贴在这一行左侧，与当前行同色（原厂 READY 绿）。
	// 只有一条、只标正在唱的那句，歌名和下一行都不带。
	// fillRoundRectAA 不带临时偏移，这里手动叠加，保证竖条跟着拖动一起走。
	// 左对齐时贴一条强调竖条在行首；居中排布时文字轨道不在左边界，
	// 竖条留在原地会离文字很远，索性不画（居中版面本身已经够清楚）。
	if !cfg.Features.LyricCenter {
		bx, by := left-24*k+cv.ox, top+curFS+cv.oy
		cv.fillRoundRectAA(bx, by-33*k, bx+4*k, by+3, 2*k, p.accent)
	}
	// 正在唱的这句：旧版布局 + 原厂 READY 绿
	cv.drawLyricWrapped(cur, tx, top+curFS, curFS, curLH, maxW, p.accent, talign, curN)
	top += float64(curN)*curLH + gap
	used += float64(curN)*curLH + gap

	// 下一行预览：剩余空间够一行才画
	if next != "" && used+nextLH <= maxH {
		cv.drawLyricWrapped(next, tx, top+nextFS, nextFS, nextLH, maxW, dim, talign, 1)
	}
}

// paintLyricBottom 把歌词画到仪表底部中央——原行程行（里程/时长/平均/能耗）的位置。
// 两行歌词（当前 + 下一行）居中；歌名/歌手作为一行小字放在歌词左上方。
// 行程行由 paintBottom 整块让位，两者互斥。
//
// 坐标依据 tool/ProbeMain 的区域表：行程行以 x=960 居中、基线 y=700（我们已整体
// 下移到 708）；底部 y[660,720] 是 delta 帧兜底带，内容落进去没有额外约束。
// 这里两行都压在这个带内：当前行基线 674、下一行基线 710。
//
// 位置限制（用户要求）：只允许落在 x∈[610,1310]——左边是电瓶/车内温度
// （x≈430 起、右沿约 580），右边是导航卡（x≥1311），越界会压到别的数据。
// 所以宽度上限 700px：超宽先缩字号，再超就截断。
func paintLyricBottom(cv *canvas, s snapshot, night bool) {
	main, dim := nightValue, nightLabel
	if !night {
		main, dim = dayValue, dayLabel
	}
	const (
		maxW    = 700.0
		centerX = 960.0
		infoX   = 610.0 // 歌曲信息左对齐到歌词区左边界（用户要的「左边一点」）
		infoY   = 638.0 // 小字行基线，与两行歌词垂直错开
		infoFS  = 20.0
		curFS   = 32.0
		nxtFS   = 26.0
		curY    = 674.0 // 当前行基线
		nxtY    = 710.0 // 下一行基线（与原行程行 708 同一带）
	)
	// 歌曲信息：歌名 · 歌手，一行小字放在歌词左上方；同样做限宽，不会越出区域
	info := s.lyric.title
	if s.lyric.artist != "" {
		if info != "" {
			info += " · "
		}
		info += s.lyric.artist
	}
	if txt, sz := fitLyricLine(info, infoFS, maxW); txt != "" {
		cv.drawLyricText(txt, infoX, infoY, sz, dim, 1, 0)
	}
	if txt, sz := fitLyricLine(s.lyric.cur, curFS, maxW); txt != "" {
		cv.drawLyricText(txt, centerX, curY, sz, main, 0, 0)
	}
	if txt, sz := fitLyricLine(s.lyric.next, nxtFS, maxW); txt != "" {
		cv.drawLyricText(txt, centerX, nxtY, sz, dim, 0, 0)
	}
}

// fitLyricLine 把一行歌词压进 maxW：先按比例缩小字号（不低于原字号 80%），
// 仍然超宽就截断加省略号，保证任何情况下都不会越出底部中央区域。
// 测量必须和 drawLyricText 用同一把字体（fCJKFull），否则英文歌词宽度会算小。
func fitLyricLine(s string, px, maxW float64) (string, float64) {
	return fitLyricLineScale(s, px, maxW, 0.8)
}

// fitLyricLineScale 同上，可指定缩字号的底线（minRatio）。
// 当前行给 0.72：中文长句（≤15 字）能完整放下，少截断。
func fitLyricLineScale(s string, px, maxW, minRatio float64) (string, float64) {
	s = strings.TrimSpace(s)
	if s == "" {
		return "", px
	}
	measure := func(txt string, sz float64) float64 {
		return float64(font.MeasureString(face(fCJKFull, sz), txt)) / 64
	}
	if measure(s, px) <= maxW {
		return s, px
	}
	w := measure(s, px)
	if sz := px * maxW / w; sz >= px*minRatio {
		return s, sz
	}
	sz := px * minRatio
	r := []rune(s)
	for len(r) > 1 {
		r = r[:len(r)-1]
		if measure(strings.TrimSpace(string(r))+"…", sz) <= maxW {
			break
		}
	}
	return strings.TrimSpace(string(r)) + "…", sz
}

func fmtInt(v float64) string {
	if math.IsNaN(v) || v < 0 {
		return "--"
	}
	return fmt.Sprintf("%.0f", v)
}
