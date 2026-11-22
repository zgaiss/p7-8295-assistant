package main

// 充电页：充电时整屏替代原厂充电界面。
//
// 对齐原厂 charging.kzb 的行为——它是"换页"不是"弹窗"：把 Charging 层顶上来、
// 同时隐藏底盘页面，8 个界面态由 Charging.MainState 驱动。我们做不到那个内部
// 状态机，只能从 DBus 的几条车辆量反推，规则集中在 deriveState()。
//
// 数据全部走仪表自己的 DBus，不经过车机：
//
//	host-mcu-charging-state    → 整车充电状态枚举（值域待实车标定）
//	host-vcu-charge-gun-status → 充电枪插枪状态枚举
//	host-echarge-status        → 充电桩状态枚举
//	host-echarge-cur           → A   （double[]，取首元素）
//	host-echarge-voltage       → V   （double[]，未充电时是 [0,0,100] 占位，需过滤）
//	host-echarge-power         → kW  （有就直接用，没有就用 U×I 算）
//	host-echarge-limit-time    → min（剩余充电时间）
//	host-vcu-bms-socdisp       → %   （SOC 显示值）
//	host-vcu-dstbat-disp       → km  （剩余续航）
//	host-icm-charging-duration → min（本次充电时长）
//
// 功率兜底用 U×I：截图里 450.3V × 353.3A ≈ 159.1kW，正好吻合。

import (
	"fmt"
	"image/color"
	"time"
)

// 原始枚举常量。全部"待实车标定"：先按文档 + 常见约定取值，
// 首次实车充电后看 /data/p7assist/chgraw.json 里的 mcu/gun/state 实际值，
// 对齐这里即可，不用改逻辑。
const (
	chgPollMS = 1000

	// 连续这么多轮（每秒一轮）电流与 MCU 状态同时读不到，就保守判为未充电。
	// 短于此值保持上次结论，防 DBus 偶发超时打断视频模式；超过则强制退出，
	// 避免一次误判永久卡在"充电中"。
	chgBlindMax = 10

	// host-vcu-charge-gun-status：0=未插枪 1=已插枪 2=充电中（推断）
	gunPlugged int32 = 1

	// host-mcu-charging-state：1/2 视为充电中，3 视为完成，4 视为故障（推断）
	mcuChargingA int32 = 1
	mcuChargingB int32 = 2
	mcuDone      int32 = 3
	mcuFault     int32 = 4

	chgCurMinA = 0.5  // 电流大于这个值即认定在拉电流
	chgVoltMin = 50.0 // 低于此值视为占位/未充电
)

var (
	pbgImgNil    bool
	pbgAw, pbgAh int
	pbgFill      time.Duration
	pbgDraw      time.Duration
	pbgFloor     time.Duration
	pcBg         time.Duration
	pcHead       time.Duration
	pcFoot       time.Duration
	pcN          int
)

// 充电页配色：深底 + 青绿强调
var (
	chgBgTop    = color.RGBA{0x0A, 0x1B, 0x2E, 0xFF}
	chgBgBot    = color.RGBA{0x07, 0x0D, 0x14, 0xFF}
	chgAccent   = color.RGBA{0x53, 0xE0, 0xC0, 0xFF}
	chgWhite    = color.RGBA{0xF2, 0xF8, 0xFF, 0xFF}
	chgDim      = color.RGBA{0x9F, 0xB3, 0xC6, 0xFF}
	chgTrack    = color.RGBA{0x24, 0x37, 0x49, 0xFF}
	chgSep      = color.RGBA{0x2A, 0x3D, 0x50, 0xFF}
	chgErrCol   = color.RGBA{0xEF, 0x6B, 0x5B, 0xFF}
	chgTitleCol = color.RGBA{0xE8, 0xF2, 0xFF, 0xFF}
	chgWarnBg   = color.RGBA{0x3A, 0x14, 0x14, 0xFF}
	chgWarnBgHi = color.RGBA{0x4A, 0x12, 0x12, 0xFF}
	chgWarnEdge = color.RGBA{0xE8, 0x5A, 0x4A, 0xFF}
	chgWarnText = color.RGBA{0xFF, 0xE9, 0xE2, 0xFF}
)

// intOr 读一个整型属性，读不到记 0（这些量大多是 0=无 / 非0=有 的显示标志）
func intOr(c *busCtx, m string) int {
	if v, ok := c.getI(m); ok {
		return int(v)
	}
	if f, ok := c.getF(m); ok {
		return int(f)
	}
	// 有些量是 int32 数组（比如枪口状态按左右分别上报），取首元素
	if a, ok := c.getIA(m); ok && len(a) > 0 {
		return int(a[0])
	}
	return 0
}

// firstRealValue 从 DBus double[] 里取第一个真实值：跳过 <= min 的标志位，
// 也不取 max（快充数组后面会混入峰值/限值，取 max 会把实时值读成峰值）。
// 慢充 [29.5,29.5] / 快充 [2,2,71.8,471.8,471.8] 都能拿到正确值。
func firstRealValue(a []float64, min float64) float64 {
	for _, v := range a {
		if v > min {
			return v
		}
	}
	return 0
}

func adOr(c *busCtx, m string) float64 {
	if a, ok := c.getAD(m); ok && len(a) > 0 {
		return a[0]
	}
	return 0
}

// pollChargePage 读一次充电相关属性，更新状态机、警示与原始值快照

// 桩类型判定阈值（物理约束，不是猜的）：
//   · P7 的车载充电机（OBC）上限约 11kW，交流桩再怎么充也超不过；
//   · 交流充电时电流受 OBC 限制，直流侧约 ≤32A；50A 以上只有直流能做到；
//   · P7 是 400V 平台，交流时电压就是电池电压（≤460V），交流充不出 500V。
//
// 所以只要"本次会话"达到过 15kW / 50A / 500V 任一条，就必然是直流快充。
// 反之要等充满 2 分钟、且峰值仍然很低，才判交流——避免刚插枪功率没爬上来误判。
const (
	pileUnknown = 0
	pileDC      = 1
	pileAC      = 2

	pileDCKw      = 15.0
	pileDCAmpere  = 50.0
	pileDCVolt    = 500.0
	pileACMaxKw   = 11.0
	pileACMaxAmp  = 32.0
	pileACMaxVolt = 460.0
	pileACWaitSec = 120
)

func derivePile(ci chargeInfo) int {
	if ci.maxPower >= pileDCKw || ci.maxCur >= pileDCAmpere || ci.maxVolt >= pileDCVolt {
		return pileDC
	}
	if ci.sessSec >= pileACWaitSec && ci.maxPower > 0 &&
		ci.maxPower <= pileACMaxKw && ci.maxCur <= pileACMaxAmp && ci.maxVolt <= pileACMaxVolt {
		return pileAC
	}
	return pileUnknown
}

// pileName 给界面/日志用
func pileName(p int) string {
	switch p {
	case pileDC:
		return "直流快充"
	case pileAC:
		return "交流慢充"
	default:
		return "未判定"
	}
}

// deriveState 从车辆量反推 8 态界面。规则刻意"可解释"而不是拍脑袋：
//
//	① 故障类标志（电池故障/高压切断/左右枪口故障）→ 故障
//	② 有电流 → 充电中（353A 这种量不会误判）
//	③ 充满后电流掉下来但 SOC 已高 → 完成（靠上一态滞后，避免来回跳）
//	④ 电池冷/热显示标志 → 加热/冷却（原厂 HeatBattery 两个态共用一套页面）
//	⑤ 插了枪但没电流 → 准备中（等桩响应）；设了预约时间则是定时态
//	⑥ 都没 → 未连接
func deriveState(ci chargeInfo, prev chgState) chgState {
	plugged := ci.gun >= int(gunPlugged) || ci.portL != 0 || ci.portR != 0

	if ci.batFault != 0 || ci.hvCutoff != 0 || ci.portLFault != 0 || ci.portRFault != 0 {
		return chgError
	}
	// 完成态必须在电流判定之前：实车充满后 mcu=3，桩仍有 3A 左右的尾电流，
	// 若先看电流会被误判成"充电中"，完成页就永远出不来（2026-11-23 实车复现）。
	if ci.mcu == int(mcuDone) {
		return chgDone
	}
	if ci.cur >= chgCurMinA {
		return chgRunning
	}
	if prev == chgRunning && ci.soc >= 95 {
		return chgDone
	}
	if ci.state == int(mcuFault) || ci.mcu == int(mcuFault) {
		return chgError
	}
	// 电池预处理：原厂在这一态画倒计时，和"加热/冷却"共用页面
	if ci.batCold != 0 {
		return chgHeating
	}
	if ci.batHot != 0 {
		return chgCooling
	}
	if ci.apptValid() && plugged {
		return chgAppoint
	}
	if plugged {
		return chgPrepare
	}
	if prev == chgDone && ci.soc >= 95 {
		return chgDone // 充满后拔枪前保持"完成"
	}
	return chgIdle
}

// apptValid 预约充电时间是否有效（时/分都在合法范围且非 0:0）
func (ci chargeInfo) apptValid() bool {
	return ci.apptH >= 0 && ci.apptH <= 23 && ci.apptM >= 0 && ci.apptM <= 59 &&
		(ci.apptH != 0 || ci.apptM != 0)
}

// stateTitle 状态主标题（用固件里的原文，见充电相关 HMI 汇总 §02）
func stateTitle(st chgState, ch chargeInfo) string {
	switch st {
	case chgPrepare:
		return "充电准备中"
	case chgRunning:
		// 充电档位只看当前功率（车主 2026-11-18 明确：60kW 以下不算极速）。
		// 早先用电压等级判"直流桩"的写法作废——那样 6kW 涓流也会叫极速，不对。
		switch {
		case ch.power >= 60:
			return "极速充电中"
		case ch.power >= 30:
			return "快速充电中"
		default:
			return "充电中"
		}
	case chgDone:
		return "充电完成"
	case chgError:
		return "充电故障"
	case chgAppoint:
		return "定时充电"
	case chgHeating:
		return "电池加热中"
	case chgCooling:
		return "电池冷却中"
	default:
		return "充电枪未连接"
	}
}

// stateSub 状态副标题：把该态最该说的一句话放这里
func stateSub(st chgState, ch chargeInfo) string {
	switch st {
	case chgPrepare:
		if ch.gun >= int(gunPlugged) {
			return "等待充电桩响应"
		}
		return "请插入充电枪"
	case chgRunning:
		if ch.leftMin > 240 {
			return "充电时间较长，可先离车"
		}
		return fmt.Sprintf("已充 %s，预计还需 %s", fmtMin(ch.durMin), fmtMin(ch.leftMin))
	case chgDone:
		if ch.durMin > 0 {
			return fmt.Sprintf("本次已充 %s", fmtMin(ch.durMin))
		}
		return "充电完成，请拔枪"
	case chgError:
		return "未能正常充电，请检查充电桩"
	case chgAppoint:
		if ch.apptValid() {
			return fmt.Sprintf("预约开始时间 %02d:%02d，等待开始", ch.apptH, ch.apptM)
		}
		return "已预约定时充电，等待开始"
	case chgHeating:
		if ch.leftMin > 0 && ch.leftMin <= 5 {
			return "电池加热即将完成"
		}
		return "电池温度低，正在加热"
	case chgCooling:
		if ch.leftMin > 0 && ch.leftMin <= 5 {
			return "电池冷却即将完成"
		}
		return "电池温度高，正在冷却"
	default:
		return "连接充电枪后可查看充电状态"
	}
}

// fmtMin 分钟说成人话：80 → "1 小时 20 分钟"
func fmtMin(m int) string {
	if m <= 0 {
		return "--"
	}
	if m < 60 {
		return fmt.Sprintf("%d 分钟", m)
	}
	h, r := m/60, m%60
	if r == 0 {
		return fmt.Sprintf("%d 小时", h)
	}
	return fmt.Sprintf("%d 小时 %d 分钟", h, r)
}

// ---------- 绘制 ----------

// paintCharge 画整屏充电页
// chargeVyOff 数据块整体下移量（px）。
//
// 原始排版照交付文档的基线（110/229/312/382/442），实测在 1920×720 上整体偏上：
// 内容只占 14~454，下面空 260px，看着"贴顶"。下移 80 后内容落在 94~534，
// 视觉上垂直居中，同时**不会超出视频模式的文字层上限（左上 760×540）**——
// p7videoplay 的 overlay 只合成 540 高，再多就会被裁掉底行。
const chargeVyOff = 80.0

// paintCharge 画整屏充电页。返回 true 表示已接管画面；false 表示没有可用背景
// （车机断开、也没有兜底帧），调用方应继续画常规组件，把原厂画面还原出来。
func paintCharge(cv *canvas, cfg Config, s snapshot, tAnim float64) bool {
	st, ch := chargeView(cfg, s, tAnim)
	if !paintChargeBg(cv) {
		return false // 无背景资源 → 不接管，原厂画面保持
	}
	paintChargeWarns(cv, ch.warns)
	paintChargeBlock(cv, st, ch, cfg.Features.ChargeDebug, chargeVyOff)
	return true
}

// chargeView 决定"这一帧要画成什么状态"：真充电数据、调试演示数据、
// 状态轮播、警示轮播都收敛到这里，让"整屏自绘"和"只出文字层"两条路走同一套数据。
func chargeView(cfg Config, s snapshot, tAnim float64) (chgState, chargeInfo) {
	ch := s.chg
	// 调试模式：没在充电也按截图样值渲染，方便驻车调版面与动画
	if cfg.Features.ChargeDebug && !ch.on {
		ch = debugChargeInfo()
		if cfg.Features.ChargeStatePreview {
			ch = debugStateAt(int(tAnim/6) % 8)
		}
		switch {
		case cfg.Features.ChargeWarnPreview:
			ch.warns = debugWarnPreview(tAnim, 4)
		case ch.st == chgError:
			ch.warns = []chgWarnItem{{"W106", "充电口温度过高，导致充电停止", false, 3}}
		case ch.st == chgHeating:
			ch.warns = []chgWarnItem{{"W93", "电池加热中", false, 1}}
		case ch.st == chgCooling:
			ch.warns = []chgWarnItem{{"W94", "电池冷却中", false, 1}}
		default:
			// 演示数据不造假警示：要验证警示条请用「警示条预览」开关
			ch.warns = nil
		}
	}
	st := ch.st
	if !ch.ok {
		st = chgIdle
	}
	return st, ch
}

// paintChargeBg 动画背景（整屏铺满）+ 上下压暗，保证文字在任何帧上都读得清。
//
// 动画源是 1470×310 的横向宽幅（比例 4.74），屏幕是 1920×720（2.67）。
// 这里按"等比铺满 + 居中裁切"（cover）：高度撑满 720，宽度按比例放大到约
// 3415 再左右各裁掉一半，车始终在正中，画面不会被拉扁。
func paintChargeBg(cv *canvas) bool {
	img, isStatic := frameFor()
	if img == nil {
		return false // 没有任何背景资源：整块不画，把屏幕让回原厂
	}
	_ = isStatic
	b := img.Bounds()
	if b.Dx() == frameW && b.Dy() == frameH {
		// 尺寸正好（车机直接投 1920×720 的帧）：1:1 拷贝
		cv.drawImg(img, 0, 0, frameW, frameH)
	} else {
		// 车机投的是小图（960×360），等比拉伸铺满 + 定点转换 + 黑地板
		cv.drawImgFit(img, frameW, frameH)
	}
	return true
}

// paintChargeWarns 顶部警示条
//
// 参考图里没有警示元素，但充电异常必须能看到，所以做成顶部一条窄条：
// 52px 高、暗红底 + 白字，只有存在警示时才画，平时完全不占地方。
func paintChargeWarns(cv *canvas, warns []chgWarnItem) {
	paintChargeWarnsBox(cv, warns, false, 0)
}

// paintChargeWarnsBox left=true 时从左边开始画。
//
// 视频模式的文字层只覆盖左上 760×540（播放器的 overlay 限制），按 1920 居中算
// 出来的 x=(1920-420)/2=750 会让整条跑到 760 以外，屏幕上只剩边上一条线
// —— 就是"警示只显示了一点点"。这种模式要左对齐，还要按 maxW 收窄。
func paintChargeWarnsBox(cv *canvas, warns []chgWarnItem, left bool, maxW float64) {
	if len(warns) == 0 {
		return
	}
	const (
		h  = 52.0
		y0 = 14.0
	)
	for i, wn := range warns {
		if i >= 2 {
			break
		}
		y := y0 + float64(i)*(h+8)
		// 文案过长时按 maxW 自动缩字号，避免被 overlay 宽度裁掉
		fs := 26.0
		tw := measureWith(wn.Text, fs, true)
		if maxW > 0 && tw+96 > maxW {
			fs = fs * (maxW - 96) / tw
			if fs < 16 {
				fs = 16
			}
			tw = measureWith(wn.Text, fs, true)
		}
		w := tw + 96
		if w < 420 {
			w = 420
		}
		if maxW > 0 && w > maxW {
			w = maxW
		}
		if w > 900 {
			w = 900
		}
		var x float64
		if left {
			x = 48
		} else {
			x = (float64(frameW) - w) / 2
		}
		bg := chgWarnBg
		if wn.Sev >= 3 {
			bg = chgWarnBgHi
		}
		cv.fillRoundRectAA(x, y, x+w, y+h, h/2, bg)
		cv.strokeRoundRectAA(x, y, x+w, y+h, h/2, 1.5, chgWarnEdge)
		icx, icy := x+34, y+h/2
		cv.fillRoundRectAA(icx-3, icy-11, icx+3, icy+3, 3, chgWarnEdge)
		cv.fillRoundRectAA(icx-3, icy+7, icx+3, icy+11, 3, chgWarnEdge)
		cv.drawText(wn.Text, x+58, y+h/2+9, fs, chgWarnText, 1, true)
	}
}

// paintChargeBlock 左上角数据块。
//
// 坐标与字号完全照《充电动画仪表投屏_交付文档》第 4 节"仪表文字层排版（已固化）"：
//
//	段落            字号  基线y
//	极速充电中       44   110
//	公里数           96   229
//	剩余时间/电量    30   312
//	数值(min/%)      56   382
//	底行(kW/V/A)     34   442
//
// 水平：x0=70；电量列 370；底行起点 70/260/450；竖线 250/440；格宽 190
// 单位偏移：km +14、min +10、% +8
func paintChargeBlock(cv *canvas, st chgState, ch chargeInfo, cfgTagShow bool, vyOff float64) {
	const x0 = 70.0
	const xCol2 = 370.0
	y := func(v float64) float64 { return v + vyOff }

	// 标题 44px
	cv.drawText(stateTitle(st, ch), x0, y(110), 44, chgTitleCol, 1, false)

	// 公里数 96px + 单位 km（偏移 +14）
	// 完成态对齐原厂：只显示电量（SOC），不再显示续航——原厂完成页不报续航，
	// 而且桩在充满后 often 把 rangeKm 刷新成不确定值。
	val, unit := "", ""
	if st == chgDone {
		if ch.soc > 0 {
			val, unit = fmt.Sprintf("%d", ch.soc), "%"
		}
	} else if ch.rangeKm > 0 {
		val, unit = fmt.Sprintf("%d", ch.rangeKm), "km"
	} else if ch.soc > 0 {
		val, unit = fmt.Sprintf("%d", ch.soc), "%"
	}
	if val != "" {
		end := cv.drawText(val, x0, y(229), 96, chgWhite, 1, true)
		cv.drawText(unit, end+14, y(229), 34, chgWhite, 1, false)
	} else {
		cv.drawText("--", x0, y(229), 96, chgDim, 1, true)
	}

	// 标签行 30px。完成态原厂显示"已充时长"，不是"剩余时间"。
	leftLabel := "剩余时间"
	if st == chgDone {
		leftLabel = "已充时长"
	}
	cv.drawText(leftLabel, x0, y(312), 30, chgDim, 1, false)
	cv.drawText("电量", xCol2, y(312), 30, chgDim, 1, false)

	// 数值行 56px（min 偏移 +10、% 偏移 +8）
	leftVal := ch.leftMin
	if st == chgDone {
		leftVal = ch.durMin // 完成态显示本次已充时长
	}
	if leftVal > 0 {
		end := cv.drawText(fmt.Sprintf("%d", leftVal), x0, y(382), 56, chgWhite, 1, true)
		cv.drawText("min", end+10, y(382), 30, chgWhite, 1, false)
	} else {
		cv.drawText("--", x0, y(382), 56, chgDim, 1, true)
	}
	if ch.soc > 0 {
		end := cv.drawText(fmt.Sprintf("%d", ch.soc), xCol2, y(382), 56, chgWhite, 1, true)
		cv.drawText("%", end+8, y(382), 30, chgWhite, 1, false)
	} else {
		cv.drawText("--", xCol2, y(382), 56, chgDim, 1, true)
	}

	// 底行 34px：功率 | 电压 | 电流，竖线在 250 / 440
	yLine0, yLine1 := y(410), y(452)
	cv.fillRect(250, yLine0, 251.5, yLine1, chgSep)
	cv.fillRect(440, yLine0, 441.5, yLine1, chgSep)

	items := []struct {
		x    float64
		v, u string
		ok   bool
	}{
		// 完成态桩仍有尾电流（实车 3A）和残留电压，原厂完成页不显示这些实时量。
		{70, fmt.Sprintf("%.1f", ch.power), "kW", st != chgDone && ch.power > 0},
		{260, fmt.Sprintf("%.1f", ch.volt), "V", st != chgDone && ch.volt > 0},
		{450, fmt.Sprintf("%.1f", ch.cur), "A", st != chgDone && ch.cur > 0},
	}
	for _, it := range items {
		txt, col := it.v, chgWhite
		if !it.ok {
			txt, col = "--", chgDim
		}
		end := cv.drawText(txt, it.x, y(442), 34, col, 1, true)
		cv.drawText(it.u, end+6, y(442), 22, chgTitleCol, 1, false)
	}

	// 调试模式在右下角标一行构建号，方便一眼分清"是不是新版本"
	if cfgTagShow {
		w := measureWith(buildTag, 20, false)
		cv.drawText(buildTag, float64(frameW)-w-24, float64(frameH)-18, 20, chgDim, 1, false)
	}
}

// chargeDrawSig 当前充电页画面的指纹：帧（车机投的帧序号/静态）、状态、
// 各项数值（保留 1 位小数）、警示 ID、调试轮播的相位。
// 渲染循环拿它和上一帧比较，一样就整帧跳过——这是"不卡"的关键：
// 仪表这颗 CPU 每帧全屏重绘要 300~450ms，绝不能被 30fps 的 ticker 拖着一直画。
func chargeDrawSig(cfg Config, s snapshot, tAnim float64) uint64 {
	h := uint64(1469598103934665603)
	add := func(v uint64) {
		h ^= v
		h *= 1099511628211
	}
	ch := s.chg
	add(frameSig())
	add(uint64(ch.st))
	add(uint64(ch.soc))
	add(uint64(ch.rangeKm))
	add(uint64(ch.leftMin))
	add(uint64(ch.durMin))
	add(uint64(int64(ch.power * 10)))
	add(uint64(int64(ch.volt * 10)))
	add(uint64(int64(ch.cur * 10)))
	add(uint64(ch.gun))
	if cfg.Features.ChargeDebug {
		add(uint64(int(tAnim / 4))) // 警示轮播相位
		add(uint64(int(tAnim / 6))) // 8 态轮播相位
	}
	for _, w := range ch.warns {
		for i := 0; i < len(w.ID); i++ {
			add(uint64(w.ID[i]))
		}
	}
	return h
}

// debugChargeInfo 演示数据：按实车参考图的样值（478km / 99% / 5min / 6.0kW / 494.7V / 14.8A）
func debugChargeInfo() chargeInfo {
	// 数值照实车参考图（478km / 99% / 5min / 6.0kW / 494.7V / 14.8A）。
	// 注意 6.0kW < 60，所以标题会显示"充电中"——这是车主定的分档，不是 bug。
	return chargeInfo{ok: true, on: true, st: chgRunning, volt: 494.7, cur: 14.8,
		power: 6.0, soc: 99, leftMin: 5, rangeKm: 478, durMin: 42, gun: 2}
}

// debugStateAt 按序号返回一个"看起来对"的演示态，供 8 态轮播用
func debugStateAt(i int) chargeInfo {
	base := debugChargeInfo()
	switch i {
	case 0:
		return chargeInfo{ok: true, soc: 46, rangeKm: 223, gun: 0}
	case 1:
		return chargeInfo{ok: true, st: chgPrepare, soc: 46, rangeKm: 223, gun: 1}
	case 2:
		return base
	case 3:
		return chargeInfo{ok: true, st: chgDone, soc: 100, rangeKm: 512, gun: 2, durMin: 68}
	case 4:
		return chargeInfo{ok: true, st: chgError, soc: 46, rangeKm: 223, gun: 2, portLFault: 1}
	case 5:
		return chargeInfo{ok: true, st: chgAppoint, soc: 46, rangeKm: 223, gun: 1,
			apptH: 22, apptM: 30}
	case 6:
		return chargeInfo{ok: true, st: chgHeating, soc: 46, rangeKm: 210, gun: 1,
			leftMin: 18, volt: 448.0, cur: 90.0, power: 40.3}
	default:
		return chargeInfo{ok: true, st: chgCooling, soc: 46, rangeKm: 210, gun: 1,
			leftMin: 12, volt: 449.0, cur: 120.0, power: 53.9}
	}
}
