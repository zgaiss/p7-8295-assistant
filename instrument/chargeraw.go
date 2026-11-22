package main

// 充电页的原始值与温度探测。
//
// 两件事：
//  1. 温度类属性名不确定（原厂是 BMS/充电口自己的量），这里用候选表逐个试，
//     第一次试通就记住名字，之后只读那一个——不成功后每秒都去撞一堆不存在的
//     方法，DBus 上会很吵。
//  2. 把 mcu/gun/state 等原始枚举落盘到 /data/p7assist/chgraw.json，供实车
//     充一次电后对齐枚举（车机端「充电模式」页会把这份读出来显示）。

import (
	"encoding/json"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"time"
)

var (
	chgTempOnce sync.Once
	chgCellName string
	chgPortName string
)

// 候选属性名：命中即用，全部不命中就当作没有温度数据（不误报警示）
var chgCellCandidates = []string{
	"get_host_vcu_bms_maxtemp",
	"get_host_vcu_bms_max_temp",
	"get_host_bcu_maxcelltemp",
	"get_host_bcu_celltemp_max",
	"get_host_vcu_bms_celltemperaturemax",
}

var chgPortCandidates = []string{
	"get_host_bcm_chargeport_left_temperature",
	"get_host_bcm_l_charger_port_temp",
	"get_host_echarge_port_temp",
}

// readChgTemps 读电芯/充电口温度；拿不到就返回 nil（警示层不会误报）
func readChgTemps(c *busCtx) *chgTemps {
	chgTempOnce.Do(func() {
		for _, n := range chgCellCandidates {
			if v, ok := c.getF(n); ok && v > -60 && v < 200 {
				chgCellName = n
				break
			}
		}
		for _, n := range chgPortCandidates {
			if v, ok := c.getF(n); ok && v > -60 && v < 200 {
				chgPortName = n
				break
			}
		}
	})
	if chgCellName == "" && chgPortName == "" {
		return nil
	}
	tm := &chgTemps{}
	if chgCellName != "" {
		if v, ok := c.getF(chgCellName); ok {
			tm.cellC, tm.cellOK = v, true
		}
	}
	if chgPortName != "" {
		if v, ok := c.getF(chgPortName); ok {
			tm.portC, tm.portOK = v, true
		}
	}
	return tm
}

// chgRawDoc 落盘的原始值（字段名即 DBus 量名，方便对照）
type chgRawDoc struct {
	AtMs     int64   `json:"atMs"`    // 写盘时刻（epoch ms）
	UptimeS  float64 `json:"uptimeS"` // 车机 uptime，便于和日志对齐
	Mcu      int     `json:"mcu"`     // host-mcu-charging-state 原始值
	Gun      int     `json:"gun"`     // host-vcu-charge-gun-status 原始值
	St       int     `json:"st"`      // host-echarge-status 原始值
	State    string  `json:"state"`   // 推断出的界面态名
	Volt     float64   `json:"volt"`
	Cur      float64   `json:"cur"`
	Power    float64   `json:"power"`
	RawVolt  []float64 `json:"rawVolt,omitempty"`  // DBus 原始数组，诊断取值用
	RawCur   []float64 `json:"rawCur,omitempty"`
	RawPow   []float64 `json:"rawPow,omitempty"`
	Soc      int     `json:"soc"`
	LeftMin  int     `json:"leftMin"`
	RangeKm  int     `json:"rangeKm"`
	DurMin   int     `json:"durMin"`
	On       bool    `json:"on"`
	CellTemp float64 `json:"cellTemp"`
	CellOK   bool    `json:"cellOK"`
	PortTemp float64 `json:"portTemp"`
	PortOK   bool    `json:"portOK"`
	BatHot   int     `json:"batHot"`
	BatCold  int     `json:"batCold"`
	BatFault int     `json:"batFault"`
	HvCut    int     `json:"hvCutoff"`
	Ind12v   int     `json:"ind12v"`
	PwLimit  int     `json:"pwLimit"`
	SocLow   int     `json:"socLow"`
	PortL    int     `json:"portL"`
	PortR    int     `json:"portR"`
	PortLF   int     `json:"portLFault"`
	PortRF   int     `json:"portRFault"`
	ApptH    int     `json:"apptH"`
	ApptM    int     `json:"apptM"`
	VoltLvl  int     `json:"voltLevel"`
	BatPrep  int     `json:"batPrepMin"`
	AlarmID  int     `json:"alarmID"`
	// 桩类型判定（看本次会话峰值，不看瞬时）
	Pile     int      `json:"pile"`
	PileName string   `json:"pileName"`
	MaxPower float64  `json:"maxPower"`
	MaxCur   float64  `json:"maxCur"`
	MaxVolt  float64  `json:"maxVolt"`
	SessSec  int      `json:"sessSec"`
	Warns    []string `json:"warns"`
	CellProp string   `json:"cellProp"`
	PortProp string   `json:"portProp"`
}

// uptimeSec 读 /proc/uptime 的第一列（秒），和车机日志时间戳对齐用
func uptimeSec() float64 {
	b, err := os.ReadFile("/proc/uptime")
	if err != nil {
		return 0
	}
	f := strings.Fields(string(b))
	if len(f) == 0 {
		return 0
	}
	v, _ := strconv.ParseFloat(f[0], 64)
	return v
}

func chgStateName(st chgState) string {
	switch st {
	case chgPrepare:
		return "prepare"
	case chgRunning:
		return "running"
	case chgDone:
		return "done"
	case chgError:
		return "error"
	case chgAppoint:
		return "appoint"
	case chgHeating:
		return "heating"
	case chgCooling:
		return "cooling"
	default:
		return "idle"
	}
}

// dumpChgRaw 原子写 chgraw.json（tmp + rename，避免读到半截）
func dumpChgRaw(ci chargeInfo, tm *chgTemps, rawVolt, rawCur, rawPow []float64) {
	doc := chgRawDoc{
		AtMs: time.Now().UnixMilli(), UptimeS: uptimeSec(), Mcu: ci.mcu, Gun: ci.gun, St: ci.state,
		State: chgStateName(ci.st), Volt: ci.volt, Cur: ci.cur, Power: ci.power,
		RawVolt: rawVolt, RawCur: rawCur, RawPow: rawPow,
		Soc: ci.soc, LeftMin: ci.leftMin, RangeKm: ci.rangeKm, DurMin: ci.durMin,
		On: ci.on, CellProp: chgCellName, PortProp: chgPortName,
		BatHot: ci.batHot, BatCold: ci.batCold, BatFault: ci.batFault,
		HvCut: ci.hvCutoff, Ind12v: ci.ind12v, PwLimit: ci.pwLimit,
		SocLow: ci.socLow, PortL: ci.portL, PortR: ci.portR,
		PortLF: ci.portLFault, PortRF: ci.portRFault,
		ApptH: ci.apptH, ApptM: ci.apptM, VoltLvl: ci.voltLevel,
		BatPrep: ci.batPrepMin, AlarmID: ci.alarmID,
		Pile: ci.pile, PileName: pileName(ci.pile),
		MaxPower: ci.maxPower, MaxCur: ci.maxCur, MaxVolt: ci.maxVolt,
		SessSec: ci.sessSec,
	}
	if tm != nil {
		doc.CellTemp, doc.CellOK = tm.cellC, tm.cellOK
		doc.PortTemp, doc.PortOK = tm.portC, tm.portOK
	}
	for _, w := range ci.warns {
		doc.Warns = append(doc.Warns, w.ID)
	}
	b, err := json.Marshal(doc)
	if err != nil {
		return
	}
	dst := filepath.Join(appDir(), "chgraw.json")
	tmp := dst + ".tmp"
	if err := os.WriteFile(tmp, b, 0644); err != nil {
		return
	}
	_ = os.Rename(tmp, dst)
}
