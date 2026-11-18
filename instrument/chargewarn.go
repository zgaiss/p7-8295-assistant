package main

// 充电 / 电池类警示（对齐原厂 warning.xml 里充电相关的 16 条文案）。
//
// 原厂这 16 条走"报警通条"层（warning.kzb）：16 条里只有 W27、W28 带专属
// 「电池充电故障」图标，其余都是纯文字卡片。我们整屏接管充电页后，原厂那条
// 通条会被压在下面看不见，所以这一层必须自己画。
//
// 数据来源的实话（决定了哪些能真出现）：
//   · 能自己算出来的只有 4 类：续航低(W85)、充电速度慢(W95)、电池加热/冷却
//     (W93/W94，由状态机给出)、双枪(W10，需左右枪口状态)。
//   · 其余（12V 蓄电池电流传感器故障、电池包不匹配、不支持智能放电…）属于
//     整车故障码，仪表 DBus 上没有对应量，只能等车机端推过来——已预留
//     snapshot.extWarns 通道，车机侧以后接上就能显示。
//   · 电池温度类（W90/W106/W124/W126）需要电芯温度属性，属性名待实车标定，
//     标定后把 bmsTempOK/bmsTemp 填上即可自动生效。

// chgWarnItem 一条充电警示
type chgWarnItem struct {
	ID   string
	Text string
	Icon bool // W27/W28 带专属「电池充电故障」图标
	Sev  int  // 1=提示 2=警告 3=严重（决定卡片配色深浅）
}

// chgWarnAll 原厂 16 条充电/电池类警示，顺序与 warning.xml 一致
var chgWarnAll = []chgWarnItem{
	{"W10", "同时插枪，无法充电", false, 2},
	{"W27", "蓄电池电流传感器故障", true, 2},
	{"W28", "低压蓄电池电压过低", true, 3},
	{"W36", "插枪连接状态，无法切换挡位", false, 1},
	{"W65", "电池即将耗尽，请控制好车速，谨慎驾驶", false, 3},
	{"W85", "续航里程低，请及时充电", false, 1},
	{"W90", "电池温度低，影响充电速度", false, 1},
	{"W93", "电池加热中", false, 1},
	{"W94", "电池冷却中", false, 1},
	{"W95", "充电速度慢", false, 1},
	{"W97", "请确认拔枪后再驾驶", false, 2},
	{"W106", "充电口温度过高，导致充电停止", false, 3},
	{"W121", "该车辆不支持智能放电功能", false, 1},
	{"W124", "电池过温，远离车辆，请联系维修", false, 3},
	{"W126", "电池温度太低，请充电后重试", false, 2},
	{"W182", "电池包不匹配，请联系售后确认", false, 3},
}

func warnByID(id string) (chgWarnItem, bool) {
	for _, w := range chgWarnAll {
		if w.ID == id {
			return w, true
		}
	}
	return chgWarnItem{}, false
}

// 几个判定阈值（可按实车观感调整）
const (
	warnRangeKmLow = 50.0 // 续航低于这个值提示充电（W85）
	warnSlowKw     = 20.0 // 充电功率低于这个值提示"充电速度慢"（W95）
	warnTempLowC   = 5.0  // 电芯温度低于此值 → W90
	warnTempHighC  = 55.0 // 电芯温度高于此值 → W124
	warnPortHotC   = 70.0 // 充电口温度高于此值 → W106
)

// deriveChargeWarns 由当前读数推断该显示哪些警示。
//
// 判定依据全部是仪表总线上"确定存在"的量（属性名来自 dbus_props.txt 内省，
// 不是猜的），取值为 0=无 / 非0=有 的显示标志：
//
//	host-vcu-bat-hot-disp        → W124 电池过温
//	host-vcu-batcold-disp        → W90  电池温度低
//	host-vcu-ind-12v-bat         → W28  低压蓄电池电压过低
//	host-bcm-l/r-charger-port    → W10  同时插枪（左右都插）
//	host-vcu-powerlimitation-dsp → W95  充电速度慢（功率受限）
//	host-vcu-bms-soclow-status   → W85  续航低（SOC 低标志）
//	host-bcm-chargeport-*-fault  → W106 充电口故障导致停止
//
// 输出上限 2 条：通条一屏最多叠两条，再多会盖住主要信息。
func deriveChargeWarns(ch chargeInfo, st chgState, tm *chgTemps) []chgWarnItem {
	out := make([]chgWarnItem, 0, 2)
	push := func(id string) {
		if len(out) >= 2 {
			return
		}
		for _, e := range out {
			if e.ID == id {
				return
			}
		}
		if w, ok := warnByID(id); ok {
			out = append(out, w)
		}
	}

	// 越严重越靠前：过温 / 12V 亏电 / 充电口故障
	if ch.batHot != 0 {
		push("W124")
	}
	if ch.ind12v != 0 {
		push("W28")
	}
	if st == chgError && (ch.portLFault != 0 || ch.portRFault != 0) {
		push("W106")
	}
	if ch.portL != 0 && ch.portR != 0 {
		push("W10")
	}
	if ch.batCold != 0 {
		push("W90")
	}
	switch st {
	case chgHeating:
		push("W93")
	case chgCooling:
		push("W94")
	}
	if st == chgRunning && (ch.pwLimit != 0 || (ch.power > 0 && ch.power < warnSlowKw)) {
		push("W95")
	}
	if !ch.on && (ch.socLow != 0 || (ch.rangeKm > 0 && float64(ch.rangeKm) <= warnRangeKmLow)) {
		push("W85")
	}
	// 温度属性若标定到了，再补一层数值判定（拿不到就跳过，不会误报）
	if tm != nil && tm.cellOK {
		if tm.cellC >= warnTempHighC {
			push("W124")
		} else if tm.cellC <= warnTempLowC && st != chgHeating {
			push("W90")
		}
	}
	if tm != nil && tm.portOK && tm.portC >= warnPortHotC {
		push("W106")
	}
	return out
}

// chgTemps 温度类读数（属性名待实车标定；拿到就填，拿不到 ok=false 不误报）
type chgTemps struct {
	cellC  float64
	cellOK bool
	portC  float64
	portOK bool
}

// debugWarnPreview 调试用：把所有 16 条轮着显示一遍，方便核对每条版式。
// 每 w 秒换一条，走一遍约 w×16 秒。
func debugWarnPreview(now float64, w float64) []chgWarnItem {
	if w <= 0 {
		w = 4
	}
	i := int(now/w) % len(chgWarnAll)
	return []chgWarnItem{chgWarnAll[i]}
}
