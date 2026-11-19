package main

// 运行时数据快照（采集线程写、渲染线程读，统一在 box.mu 下）

// lyricInfo 当前歌词行（车机通过 ext.json 推来）
type lyricInfo struct {
	cur, next, title, artist string
	pos                      float64
	playing                  bool // 播放中才绘制；暂停/停止时整块隐藏
}

type navInfo struct {
	distM  float64
	timeS  float64
	arrive string
}

// tlLight 单个灯位的显示状态（三盘之一）
type tlLight struct {
	dir int     // 方向码 1=左转 2=直行 3=右转
	cur int     // 相位 1=红 2=绿 3=黄；0 = 该位熄灭
	sec float64 // 剩余秒（已扣掉从车机观测到现在的耗时）
}

// tlInfo 红绿灯小件状态
type tlInfo struct {
	lights [3]tlLight
	distM  float64
	lim    int // 限速，0 = 无
}

// lit 是否有任意一个灯位点亮（决定整块显示与否）
func (t tlInfo) lit() bool {
	for i := 0; i < 3; i++ {
		if t.lights[i].cur != 0 {
			return true
		}
	}
	return false
}

// chgState 充电页主状态，对齐原厂 charging.kzb 的 Charging.MainState（8 态）。
//
// 原厂这 8 个态由内部信号机驱动；我们只能从 DBus 的几条状态量反推，
// 所以 derive 里写的是"可解释的推断规则"，并在 /data/p7assist/chgraw.json
// 里持续落原始枚举值——实车充一次电就能把枚举对齐，之后只改常量即可。
type chgState int

const (
	chgIdle    chgState = iota // 充电枪未连接（Ready）
	chgPrepare                 // 充电准备中 / 等待充电桩响应
	chgRunning                 // 充电中（Runing）
	chgDone                    // 充电完成（Over）
	chgError                   // 充电故障（Error）
	chgAppoint                 // 定时充电（Appointment）
	chgHeating                 // 电池加热中（HeatBattery）
	chgCooling                 // 电池冷却中（HeatBattery）
)

// chargeInfo 充电页数据：全部来自仪表 DBus（不经车机），车机只在缺失时兜底。
type chargeInfo struct {
	ok      bool          // 至少拿到一条状态量，读数可信
	on      bool          // 充电中
	st      chgState      // 推断出的界面态
	state   int           // host-echarge-status 原始枚举（桩状态，值域待实车标定）
	mcu     int           // host-mcu-charging-state 原始枚举
	gun     int           // host-vcu-charge-gun-status 原始枚举
	volt    float64       // V（充电电压）
	cur     float64       // A（充电电流）
	power   float64       // kW（= V × A / 1000，截图 450.3×353.3≈159.1 对得上）
	soc     int           // %（BMS SOC 显示值）
	leftMin int           // 剩余充电时间（min）
	rangeKm int           // 剩余续航（km）
	durMin  int           // 本次已充时长（min）
	warns   []chgWarnItem // 当前应显示的充电警示（来自 chargewarn.go）

	// 以下为标定/判定用的车辆侧原始量（属性名见 dbus_props.txt）。
	// 取值语义多为 0=无 / 非0=有，实车对一次就能确认。
	batHot, batCold        int // host-vcu-bat-hot-disp / host-vcu-batcold-disp
	batFault, hvCutoff     int // host-vcu-batt-fault-disp / host-vcu-hv-cutoff-disp
	ind12v                 int // host-vcu-ind-12v-bat（12V 蓄电池灯）
	pwLimit                int // host-vcu-powerlimitation-dsp（功率受限）
	socLow                 int // host-vcu-bms-soclow-status
	chgPwLimit             int // host-esocmaxchg-pwlimit
	portL, portR           int // host-bcm-l/r-charger-port（左右枪口插枪）
	portLFault, portRFault int // host-bcm-chargeport-left-fault / echargeport-right-fault
	apptH, apptM           int // host-tbox-appoint-chg-set-hour / min（预约充电）
	voltLevel              int // host-echarge-voltage-level
	batPrepMin             int // host-ebat-prepare-time（电池预处理剩余时间）
	alarmID                int // host-ealarm-id（整车告警号）

	// 本次充电会话的峰值与时长：判"直流快充 / 交流慢充"必须看峰值，
	// 不能看瞬时——直流桩充到 90% 以后会降到 6kW，瞬时值和交流桩一模一样。
	maxPower, maxCur, maxVolt float64
	sessSec                   int // 本次会话已持续秒数（每秒轮询 +1）
	pile                      int // 0=未判定 1=直流快充 2=交流慢充
}

type snapshot struct {
	chg chargeInfo

	wheels [4]wheelData // 0=FL 1=FR 2=RL 3=RR

	gear    int32
	gearOK  bool
	speed   float64
	speedOK bool

	cabin   float64
	cabinOK bool

	tripKm, tripMin, tripAvg, tripKwh float64
	tripOK                            bool
	avgOK                             bool

	bsdL, bsdR bool

	volt   float64
	voltOK bool

	nav      navInfo
	navOK    bool
	navAgeMs int64

	tl     tlInfo
	tlOK   bool
	tlDemo bool // ext.tl.demo：调试模拟相位，允许驻车态也画

	lyric   lyricInfo
	lyricOK bool

	// 原厂弹窗避让：左右两侧的本地截止时刻（由 ext.json 的 popup 字段折算，见 popup.go）
	pop popupGate

	busOK    bool
	extAgeMs int64
	frames   int64

	// 本轮胎压是否来自车机 CarProperty（true=0.01bar 高精度，false=仪表总线 0.1bar）
	tpHi bool
}

func (s snapshot) anyBar() bool {
	for i := 0; i < 4; i++ {
		if s.wheels[i].barOK {
			return true
		}
	}
	return false
}

func (s snapshot) anyTemp() bool {
	for i := 0; i < 4; i++ {
		if s.wheels[i].tempOK {
			return true
		}
	}
	return false
}
