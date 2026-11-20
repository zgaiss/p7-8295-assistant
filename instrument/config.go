package main

// 配置 / 外部数据 / 运行状态
//
//   config.json  车机写入，守护进程按 mtime 热加载（改完即生效，不用重启）
//   ext.json     车机写入的"本车总线拿不到"的数据：12V 电压、导航余量/时间、日夜
//   status.json   守护进程每 2 秒回写，车机 App 读它显示实时状态
//
// 所有写盘都走 tmp + rename，避免车机读到半个文件。

import (
	"encoding/json"
	"os"
	"path/filepath"
	"time"
)

// Injected from AndroidManifest.xml by tools/build_version.py. Raw go builds are not release builds.
var appVersion = "dev-unversioned"

// buildTag 显示在充电页右下角（仅调试模式），用来区分同版本号的不同构建
const buildTag = "b2026-11-18q"

type TripCfg struct {
	Dist  bool `json:"dist"`
	Time  bool `json:"time"`
	Speed bool `json:"speed"`
	Kwh   bool `json:"kwh"`
}

type FeatureCfg struct {
	TPMSP bool `json:"tpmsP"` // 胎压 / 胎温（P 档）
	TPMSD bool `json:"tpmsD"` // 胎压 / 胎温（D 档）
	Volt  bool `json:"volt"`  // 电瓶电压（需车机 ext）
	Cabin bool `json:"cabin"` // 车内温度（本车空调温度）
	BSD   bool `json:"bsd"`   // 盲区监测
	Nav   bool `json:"nav"`   // 导航卡片（需车机 ext）
	Lyric bool `json:"lyric"` // 歌词（需车机 ext）
	// LyricBottom 歌词改到仪表底部中央（原行程行的位置）：画两行歌词 +
	// 左上方一行歌名/歌手，并且行程行（里程/时长/平均/能耗）让位不画，
	// 两者占同一块区域，只能二选一。
	LyricBottom bool `json:"lyricBottom"`
	// LyricBg 歌词块背后加一层底色卡片。颜色取自车机中控屏「正在播放条」下方那层，
	// 昼夜各一套（夜 #191A1F / 昼 #DFE3EB），跟随仪表昼夜自动切换。
	LyricBg bool `json:"lyricBg"`
	// LyricHideOnPause 暂停播放时整块隐藏歌词（默认关：暂停后停在最后一句）。
	// playing 字段一直有，但 paint 从来没读过它，等于没生效，这里接到开关上。
	LyricHideOnPause bool `json:"lyricHideOnPause"`
	// LyricCenter 左上角歌词块内文字水平居中（默认左对齐）。
	// 只影响块内文字对齐，块本身位置仍由「歌词位置」的拖动坐标决定。
	LyricCenter bool `json:"lyricCenter"`
	// ChargePage 充电时用整屏充电页替代原厂充电界面（对齐原厂 charging.kzb 的换页行为）。
	ChargePage bool `json:"chargePage"`
	// ChargeDebug 充电页调试：不考虑真实充电状态，直接用截图里的样值铺满整屏，
	// 方便在驻车、没插枪时调版面和动画。实车验收前请关掉。
	ChargeDebug bool `json:"chargeDebug"`
	// ChargeWarnPreview 调试专用：把原厂 16 条充电警示轮着显示一遍（每 4 秒换一条），
	// 用来核对每条文案的版式。只在 ChargeDebug 打开时生效。
	ChargeWarnPreview bool `json:"chargeWarnPreview"`
	// ChargeStatePreview 调试专用：把 8 个界面态每 6 秒换一个轮播，
	// 用来在驻车状态核对每个态的版面（只在 ChargeDebug 打开时生效）。
	ChargeStatePreview bool    `json:"chargeStatePreview"`
	Trip               TripCfg `json:"trip"`
	// TL 红绿灯小件（原厂 normalinfo 层胶囊位）。数据来自车机推来的 ext.tl。
	TL bool `json:"tl"`
	// TLStyle 灯盘画法：
	//   native = 纯色盘 + 白色倒计时数字（原厂观感，默认）
	//   icon   = 直接贴素材包 PNG（盘内是方向箭头，无倒计时）
	TLStyle string `json:"tlStyle"`
	// ChargeYield 充电让出：检测到充电就停止绘制、关掉前景层，让原厂充电页露出来，
	// 充电结束（或拔枪）后自动恢复。默认开；config.json 里写 false 可关掉。
	ChargeYield bool `json:"chargeYield"`
	Debug       bool `json:"debug"` // 调试信息
}

type ZoomCfg struct {
	Speed float64 `json:"speed"` // km/h，布局开始收拢的车速
	Ms    float64 `json:"ms"`    // 收拢/展开所用的毫秒
}

type LayoutCfg struct {
	DX    float64 `json:"dx"`    // 整体横向偏移（像素）
	DY    float64 `json:"dy"`    // 整体纵向偏移
	Scale float64 `json:"scale"` // 缩放，1.0 = 原厂位置
}

// LyricPosCfg 左上角歌词块的拖动位置（仪表 1920×720 绝对像素）。
// DX/DY 是歌词块左上角相对原默认位置的偏移，0/0 = 原左上角位置。
// 只在「歌词显示在下方」关闭时生效；块包围盒 640×186，
// 合法范围 DX∈[0,1280] DY∈[0,534]，保证整块始终在屏幕内（出界由车机端预览吸附）。
type LyricPosCfg struct {
	DX    float64 `json:"dx"`
	DY    float64 `json:"dy"`
	Scale float64 `json:"scale"` // 歌词块整体缩放，1.0 = 原始大小；0.6 ~ 1.8
}

type ExtCfg struct {
	TTLMs int `json:"ttlMs"` // ext.json 超过这个时间视为过期
}

// VideoCfg 打灯盲区影像：车机把该侧后方画面经 zstd 推到仪表，仪表半屏显示。
//
// 默认**关闭**，并且字段缺失一律按关闭处理。这里故意不用 popupHide 那种
// 「缺失即视为开」的写法：popupHide 缺失最多是少藏两张卡，影像缺失按开处理
// 会直接盖住半屏界面，属于把旧版车机的用户升级后立刻踩坑。
type VideoCfg struct {
	Enable bool `json:"enable"`
	Port   int  `json:"port"` // 监听端口，默认 9002；9000 留给车机端 H.264 实验
}

type Config struct {
	Version   int         `json:"version"`
	Enabled   bool        `json:"enabled"` // 全局开关
	BSDSrc    string      `json:"bsdSrc"`  // 盲区数据源：car=车机 CarProperty(默认) / bus=仪表 DBus
	Features  FeatureCfg  `json:"features"`
	Zoom      ZoomCfg     `json:"zoom"`
	DayNight  string      `json:"dayNight"` // auto | day | night
	Layout    LayoutCfg   `json:"layout"`
	LyricPos  LyricPosCfg `json:"lyricPos"` // 左上角歌词块拖动位置
	Ext       ExtCfg      `json:"ext"`
	Video     VideoCfg    `json:"video"`     // 打灯盲区影像
	ShowClock bool        `json:"showClock"` // 预留：左下角时钟
}

func defaultConfig() Config {
	return Config{
		Version: 1,
		Enabled: true,
		BSDSrc:  "car",
		Features: FeatureCfg{
			TPMSP:   true,
			TPMSD:   true,
			Volt:    false,
			Cabin:   false,
			BSD:     true,
			Nav:     true,
			Lyric:   true,
			TL:      true,
			TLStyle: "native",
			// 充电中让出原厂充电页：默认开。多数人希望充电时看到原厂的充电界面。
			ChargeYield: true,
			Trip:        TripCfg{Dist: true, Time: true, Speed: true, Kwh: true},
			Debug:       false,
		},
		Zoom:     ZoomCfg{Speed: 60, Ms: 4000},
		DayNight: "auto",
		Layout:   LayoutCfg{DX: 0, DY: 0, Scale: 1},
		LyricPos: LyricPosCfg{DX: 0, DY: 0},
		Ext:      ExtCfg{TTLMs: 30000},
	}
}

// loadConfig 读取配置；文件不存在时落一份默认配置，方便车机端"先连上再改"
func loadConfig(path string) (Config, time.Time, error) {
	cfg := defaultConfig()
	st, err := os.Stat(path)
	if err != nil {
		if os.IsNotExist(err) {
			_ = saveJSON(path, cfg)
			if st2, e2 := os.Stat(path); e2 == nil {
				return cfg, st2.ModTime(), nil
			}
			return cfg, time.Now(), nil
		}
		return cfg, time.Time{}, err
	}
	b, err := os.ReadFile(path)
	if err != nil {
		return cfg, st.ModTime(), err
	}
	// 解析失败（例如刚好读到半截文件）要把错误抛回去，让调用方沿用旧配置
	if err := json.Unmarshal(b, &cfg); err != nil {
		cfg.normalize()
		return cfg, st.ModTime(), err
	}
	cfg.normalize()
	return cfg, st.ModTime(), nil
}

func (c *Config) normalize() {
	if c.Version == 0 {
		c.Version = 1
	}
	if c.Zoom.Speed < 30 {
		c.Zoom.Speed = 60
	}
	if c.Zoom.Ms < 500 {
		c.Zoom.Ms = 4000
	}
	if c.Layout.Scale <= 0.3 || c.Layout.Scale > 2 {
		c.Layout.Scale = 1
	}
	// 歌词块整体缩放：0.6~1.8（0 或缺失视为 1，兼容旧配置）。
	// 包围盒随缩放变化，边界必须按缩放后的尺寸算，否则放大后 DX 上限仍按 640 算，
	// 块会有一截探出屏外。
	if c.LyricPos.Scale <= 0 {
		c.LyricPos.Scale = 1
	} else if c.LyricPos.Scale < 0.6 {
		c.LyricPos.Scale = 0.6
	} else if c.LyricPos.Scale > 1.8 {
		c.LyricPos.Scale = 1.8
	}
	bw, bh := lyricBoxW*c.LyricPos.Scale, lyricBoxH*c.LyricPos.Scale
	if c.LyricPos.DX < 0 {
		c.LyricPos.DX = 0
	} else if c.LyricPos.DX > frameW-bw {
		c.LyricPos.DX = frameW - bw
	}
	if c.LyricPos.DY < 0 {
		c.LyricPos.DY = 0
	} else if c.LyricPos.DY > frameH-bh {
		c.LyricPos.DY = frameH - bh
	}
	if c.Ext.TTLMs <= 0 {
		c.Ext.TTLMs = 30000
	}
	// 影像端口只在开启时才需要有效值；关掉时保持 0，避免误开监听
	if c.Video.Port <= 0 || c.Video.Port > 65535 {
		c.Video.Port = 9002
	}
	// 旧配置没有 bsdSrc 字段时默认走车机数据（仪表 DBus 的 eind_lbsd 只是指示灯，实车不可靠）
	if c.BSDSrc != "bus" {
		c.BSDSrc = "car"
	}
	switch c.DayNight {
	case "auto", "day", "night":
	default:
		c.DayNight = "auto"
	}
	switch c.Features.TLStyle {
	case "native", "icon":
	default:
		c.Features.TLStyle = "native"
	}
}

// mask 把配置折算成位掩码，方便状态显示与调试
func (c Config) mask() int {
	m := 0
	if c.Features.Volt {
		m |= 1
	}
	if c.Features.TPMSP {
		m |= 2
	}
	if c.Features.BSD {
		m |= 4
	}
	if c.Features.Debug {
		m |= 8
	}
	if c.Features.Cabin {
		m |= 16
	}
	if c.Features.Trip.Dist {
		m |= 32
	}
	if c.Features.Trip.Time {
		m |= 64
	}
	if c.Features.Trip.Speed {
		m |= 128
	}
	if c.Features.Trip.Kwh {
		m |= 256
	}
	if c.Features.TPMSD {
		m |= 512
	}
	if c.Features.Nav {
		m |= 1024
	}
	if c.Features.Lyric {
		m |= 2048
	}
	if c.Features.LyricBottom {
		m |= 4096
	}
	if c.Features.TL {
		m |= 8192
	}
	if c.Features.LyricBg {
		m |= 16384
	}
	if c.Features.LyricHideOnPause {
		m |= 32768
	}
	if c.Features.LyricCenter {
		m |= 65536
	}
	if c.Features.ChargePage {
		m |= 131072
	}
	if c.Features.ChargeDebug {
		m |= 262144
	}
	if c.Features.ChargeWarnPreview {
		m |= 524288
	}
	if c.Features.ChargeStatePreview {
		m |= 1048576
	}
	return m
}

// ---------- ext.json（车机 → 仪表） ----------

// ExtLyric 车机推来的歌词（一行当前、一行预告）
type ExtLyric struct {
	Cur    string  `json:"cur"`
	Next   string  `json:"next"`
	Title  string  `json:"title"`
	Artist string  `json:"artist"`
	Pos    float64 `json:"pos"` // 0..1 播放进度
	// Play 用指针区分「字段缺失」与「明确为 0」：旧版车机 APK 不上报 play，
	// nil 按播放中处理，避免升级仪表后歌词整块消失；只有明确的 0 才隐藏。
	Play *int `json:"play"` // 1=正在播放 0=暂停 nil=车机未上报
}

type ExtNav struct {
	DistM  float64 `json:"distM"`
	TimeS  float64 `json:"timeS"`
	Arrive string  `json:"arrive"`
}

// ExtTLPhase 一个方向的相位（车机从 com.xiaopeng.light 日志解析后推来）
//
// 只推「剩余毫秒」而不是相位结束的绝对时间戳：车机日志里的 end 是车机本地
// 时钟域（中控本地时间），仪表走 UTC，两边差 8 小时，直接传绝对时间会永远
// 被判成过期。这和 loadExt 里 ext.json 时效一律按 mtime 计是同一个原因。
type ExtTLPhase struct {
	Dir      int     `json:"dir"`      // 1=左转 2=直行
	Cur      int     `json:"cur"`      // 1=红 2=绿 3=黄 0=未知
	RemainMs float64 `json:"remainMs"` // 当前相位还剩多少毫秒
	Nxt      int     `json:"nxt"`      // 下一相位颜色，0=未知
	NxtMs    float64 `json:"nxtMs"`    // 下一相位时长（毫秒）
}

type ExtTL struct {
	Ret    int          `json:"ret"`    // 0=成功 2=尚无数据
	DistM  float64      `json:"distM"`  // 距路口米数
	Lim    int          `json:"lim"`    // 限速 km/h，0=无
	Demo   bool         `json:"demo"`   // 车机端「模拟相位」调试开关：为 true 时允许驻车态也画
	Phases []ExtTLPhase `json:"phases"` // 单个路口最多三组
}

type ExtData struct {
	TS    int64     `json:"ts"`    // 写入时间（毫秒）
	Volt  *float64  `json:"volt"`  // 12V 电压
	Night *int      `json:"night"` // 1=夜间 0=日间（车机系统 uiMode）
	Cabin *float64  `json:"cabin"` // 车内温度（℃，车机 CarProperty HVAC_INNER_TEMPERATURE）
	BSD   []int     `json:"bsd"`   // [左,右] 盲区，1=有车（车机 CarProperty SCU_BSD_WARNING）
	Nav   *ExtNav   `json:"nav"`
	Lyric *ExtLyric `json:"lyric"`
	TL    *ExtTL    `json:"tl"`
	// 四轮胎压（bar，FL FR RL RR）。
	// 仪表总线上的 host-etpms-pressure-* 只有 0.1 bar 分辨率（2.4 / 2.4 / 2.4 / 2.4），
	// 车机 CarProperty 的 TPMS_PRxx 是 kPa，能分辨到 0.01 bar（2.42 / 2.39 / 2.36 / 2.44）。
	TPMS []float64 `json:"tpms"`
}

func loadExt(path string, ttl time.Duration) (ExtData, time.Duration) {
	var e ExtData
	st, err := os.Stat(path)
	if err != nil {
		return e, -1
	}
	age := time.Since(st.ModTime())
	b, err := os.ReadFile(path)
	if err != nil {
		return e, age
	}
	if json.Unmarshal(b, &e) != nil {
		return ExtData{}, age
	}
	// 时效一律按文件 mtime 计。
	// 曾经优先用 json 里的 ts，但车机写的是本地时间、仪表走 UTC，两边差 8 小时，
	// 于是 ext 永远被判定"过期"——导航卡片和电压因此一直显示不出来。
	// mtime 是仪表本地文件系统写入时刻，和这里的 time.Since 同源，不受时钟偏差影响。
	if ttl > 0 && age > ttl {
		// 过期：只保留"无时效"的日夜，其余当没有
		return ExtData{Night: e.Night}, age
	}
	return e, age
}

// ---------- status.json（仪表 → 车机） ----------

type statusDoc struct {
	TS      int64  `json:"ts"`
	PID     int    `json:"pid"`
	UptimeS int    `json:"uptimeS"`
	Version string `json:"version"`
	// ChargeAnim 充电动画已载入帧数，0 = 未载入（车机端据此判断要不要上传帧包）
	ChargeAnim int `json:"chargeAnim"`
	// 投屏状态（screen=车机投帧 / selfplay=仪表自播 / none=无画面）
	ChgMode   string `json:"chgMode"`
	ChgFrmAge int    `json:"chgFrmAgeMs"`
	ChgFrmKB  int    `json:"chgFrmKB"`
	// ChgVideo 本地播放器是否在跑（vplay=播放器接管整屏 / ""=没跑）
	ChgVideo string `json:"chgVideo"`
	// 本地播放器实测指标（车机「刷新播放器状态」直接用这些，别再猜 24fps）
	ChgWantFps    string    `json:"chgWantFps"`    // 配置的目标帧率（videofps）
	ChgWrite      string    `json:"chgWrite"`      // 写屏方式 pwrite / mmap
	ChgPackFrames int       `json:"chgPackFrames"` // 帧包包头帧数
	ChgPackKB     int       `json:"chgPackKB"`     // 帧包大小
	ChgRealFps    float64   `json:"chgRealFps"`    // 播放器实测帧率
	ChgDecMs      float64   `json:"chgDecMs"`      // 解码耗时
	ChgRenMs      float64   `json:"chgRenMs"`      // 缩放/转 565 耗时
	ChgWriMs      float64   `json:"chgWriMs"`      // 写屏耗时
	ChgVsync      string    `json:"chgVsync"`      // vsync ioctl 探测结果
	ChgVsyncMode  string    `json:"chgVsyncMode"`  // 是否启用 vsync（on/off）
	ChgStatsOK    bool      `json:"chgStatsOk"`    // 是否已解析到统计行
	Enabled       bool      `json:"enabled"`
	Screen        string    `json:"screen"` // on / off
	Bus           bool      `json:"bus"`
	Mode          string    `json:"mode"` // park / drive
	Night         int       `json:"night"`
	Gear          int32     `json:"gear"`
	Speed         float64   `json:"speed"`
	Mask          int       `json:"mask"`
	TPMS          []float64 `json:"tpms"`    // bar，无效为 -1
	TpmsSrc       string    `json:"tpmsSrc"` // bus=仪表总线(0.1bar) / car=车机CarProperty(0.01bar)
	Temp          []float64 `json:"temp"`    // ℃，无效为 -999
	Volt          float64   `json:"volt"`
	Cabin         float64   `json:"cabin"`
	TripKm        float64   `json:"tripKm"`
	TripMin       float64   `json:"tripMin"`
	TripAvg       float64   `json:"tripAvg"`
	TripKwh       float64   `json:"tripKwh"`
	NavAgeMs      int64     `json:"navAgeMs"`
	Lyric         string    `json:"lyric"`     // 当前歌词行（车机 ext 推来，空=无）
	LyricNxt      string    `json:"lyricNxt"`  // 下一行预览
	LyricPlay     bool      `json:"lyricPlay"` // 车机上报的播放态；false=暂停，歌词隐藏
	Frames        int64     `json:"frames"`
	ExtAgeMs      int64     `json:"extAgeMs"`
	// 充电让出：charging=true 表示当前判为充电中（前景层已关，原厂充电页可见），
	// chg 是判定用的原始读数，实车标定枚举时看它
	Charging bool   `json:"charging"`
	Chg      string `json:"chg"`
	Log      string `json:"log"`
}

// buildStatus 组装 status.json 内容。
// 放在无平台约束的文件里，PC 上就能用假 snapshot 直接断言字段（见 charge_mock_test.go）。
func buildStatus(s snapshot, cfg Config, screenOn bool, charging bool, chgRaw string) statusDoc {
	chgMode, chgAgeMs, chgKB := chgFrameStatus()
	vs := readVideoStats(appDir())
	mode := "park"
	if s.gearOK && s.gear != 4 {
		mode = "drive"
	}
	doc := statusDoc{
		TS:         time.Now().UnixMilli(),
		PID:        os.Getpid(),
		Version:    appVersion,
		ChargeAnim: chgAnim.count(),
		ChgMode:    chgMode,
		ChgFrmAge:  chgAgeMs,
		ChgFrmKB:   chgKB,
		ChgVideo:   chgVideoTag(),

		ChgWantFps:    vs.WantFps,
		ChgWrite:      vs.WriteMode,
		ChgPackFrames: vs.PackFrames,
		ChgPackKB:     vs.PackKB,
		ChgRealFps:    vs.RealFps,
		ChgDecMs:      vs.DecMs,
		ChgRenMs:      vs.RenMs,
		ChgWriMs:      vs.WriMs,
		ChgVsync:      vs.Vsync,
		ChgVsyncMode:  vs.VsyncMode,
		ChgStatsOK:    vs.OK,
		Enabled:       cfg.Enabled,
		Screen:        "off",
		Bus:           s.busOK,
		Mode:          mode,
		Gear:          s.gear,
		Speed:         s.speed,
		Mask:          cfg.mask(),
		TPMS:          []float64{-1, -1, -1, -1},
		Temp:          []float64{-999, -999, -999, -999},
		Volt:          -1,
		Cabin:         -999,
		TripKm:        s.tripKm,
		TripMin:       s.tripMin,
		TripAvg:       s.tripAvg,
		TripKwh:       s.tripKwh,
		NavAgeMs:      s.navAgeMs,
		TpmsSrc:       "bus",
		Frames:        s.frames,
		ExtAgeMs:      s.extAgeMs,
		Charging:      charging,
		Chg:           chgRaw,
	}
	if s.tpHi {
		doc.TpmsSrc = "car"
	}
	if screenOn {
		doc.Screen = "on"
	}
	for i := 0; i < 4; i++ {
		if s.wheels[i].barOK {
			doc.TPMS[i] = s.wheels[i].bar
		}
		if s.wheels[i].tempOK {
			doc.Temp[i] = s.wheels[i].temp
		}
	}
	if s.voltOK {
		doc.Volt = s.volt
	}
	if s.cabinOK {
		doc.Cabin = s.cabin
	}
	if s.lyricOK {
		doc.Lyric = s.lyric.cur
		doc.LyricNxt = s.lyric.next
		doc.LyricPlay = s.lyric.playing
	}
	return doc
}

func saveJSON(path string, v any) error {
	b, err := json.MarshalIndent(v, "", "  ")
	if err != nil {
		return err
	}
	tmp := path + ".tmp"
	if err := os.MkdirAll(filepath.Dir(path), 0755); err != nil {
		return err
	}
	if err := os.WriteFile(tmp, b, 0644); err != nil {
		return err
	}
	return os.Rename(tmp, path)
}
