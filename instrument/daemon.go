//go:build linux

package main

// 守护进程：DBus 采集 → 布局/绘制 → 写 fb1 前景层
//
// 设计原则（安全优先）：
//   1. 没有任何有效胎压数据时整块不画，绝不显示假数据
//   2. fb1 被原厂/OTA 重置时自动重设并恢复绘制
//   3. 退出（stop 文件 / 信号）时清空自身像素并关闭 FG 层，原厂仪表立即透出
//   4. 配置文件、ext.json 都是热加载：车机端写完即生效，不需要重启进程
//   5. restart 文件 → 进程原地 exec 自己（用于升级二进制）

import (
	"fmt"
	"os"
	"os/signal"
	"runtime/debug"
	"strings"
	"sync"
	"syscall"

	"github.com/godbus/dbus/v5"
	"time"
)

const (
	fastMS  = 250  // 挡位/车速
	slowMS  = 2000 // 胎压/温度/行程/盲区（稳态；开机 90s 内未读全时自动提速到 600ms）
	frameMS = 50   // 渲染节拍 20fps
)

// box 是采集线程与渲染线程之间的共享状态
type box struct {
	mu      sync.Mutex
	s       snapshot
	extAt   time.Time
	cfgAt   time.Time
	cfg     Config
	ext     ExtData
	night   bool
	logLine string

	// 数据偶发读不到时的计数（轮转页/瞬时空值），用于"保持上一次值"而不是让界面闪没
	tripFail  int
	cabinFail int

	// 车机推来的高精度四轮胎压（bar，FL FR RL RR）与收到时刻。
	// 总线上只有 0.1 bar 分辨率，这里优先用车机 CarProperty 的 kPa 值。
	tpExt   [4]float64
	tpExtAt time.Time

	// 原厂画面亮度采样（判昼夜）：仪表优先，采样不可靠才回落车机/时间
	lumAt    time.Time
	lumNight bool
	lumValid bool
	lumCount int

	// 充电让出：仪表 DBus 的原始读数 + 去抖后的判定结果（状态机在 charge.go）
	chgS chargeState

	// 控制视频模式进出的充电判定：非对称去抖（进快出慢），跨轮持久。
	// chg.on 偶发读到 false 不应立刻退出视频模式——那会触发近 6 秒阻塞 + 黑闪。
	chgOnDeb chgOnDebounce

	// 连续多少轮「电流和 MCU 状态同时读不到」。短时间读不到时保持上次结论
	// （防 DBus 偶发超时打断视频），但超过上限必须保守判定为未充电——
	// 否则一次误判会让 chg.on 永久卡在 true，视频模式再也退不出来。
	chgBlind int
}

// pollScreenNight 每 500ms 采样一次原厂画面亮度，带滞回 + 去抖。
// 原厂白天浅底（中位亮度 ≈220）、夜间深底（≈20）；中间区不切换，
// 连续 3 次一致才采信，避免昼夜过渡动画/隧道进出造成来回跳。
func (b *box) pollScreenNight(fb *fbDev) (bool, int) {
	if fb == nil || fb.bg == nil {
		return false, 0
	}
	if time.Since(b.lumAt) < 500*time.Millisecond {
		return false, 0
	}
	b.lumAt = time.Now()
	prevNight, prevValid := b.lumNight, b.lumValid
	lum, ok := fb.bg.avgLum()
	if !ok { // 全黑或读不到：本次采样作废，交回上层回落
		b.lumValid = false
		b.lumCount = 0
		return prevValid, lum
	}
	var cur bool
	switch {
	case lum >= 140:
		cur = false
	case lum <= 70:
		cur = true
	default: // 过渡区：不切换、不计数
		b.lumCount = 0
		return false, lum
	}
	if cur == b.lumNight {
		if b.lumCount < 99 {
			b.lumCount++
		}
		if b.lumCount >= 3 {
			b.lumValid = true
		}
	} else {
		b.lumNight = cur
		b.lumCount = 1
	}
	changed := b.lumValid != prevValid || (b.lumValid && b.lumNight != prevNight)
	return changed, lum
}

// ---------- 充电让出 ----------

// pollCharge 读一次充电相关属性，推进去抖状态机并落日志。
// 由独立 goroutine 每秒调一次：这几条 DBus 调用在总线卡住时可能阻塞十几秒，
// 不能放进 20fps 的渲染循环。
func (b *box) pollCharge(c *busCtx) {
	p := readChargeProbe(c)
	b.mu.Lock()
	hold, first, prevHold := b.chgS.apply(p)
	b.mu.Unlock()

	switch {
	case first:
		c.lg.Printf("充电探测: %s → %s", p, chgWord(hold))
	case hold && !prevHold:
		c.lg.Printf("充电让出: 检测到充电（%s），停止绘制，原厂充电页露出", p)
	case !hold && prevHold:
		c.lg.Printf("充电让出: 充电结束（%s），恢复绘制", p)
	}
}

func (b *box) chargeHold() bool {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.chgS.hold
}

// chargeRaw 充电判定原始读数（写 status.json，实车标定枚举时看它）
func (b *box) chargeRaw() string {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.chgS.raw()
}

// pollChargeLoop 充电判定采集线程：1 秒一次，与快/慢通道解耦
func pollChargeLoop(c *busCtx, b *box, stop <-chan struct{}) {
	for {
		b.pollCharge(c)
		select {
		case <-stop:
			return
		case <-time.After(chargePollMS * time.Millisecond):
		}
	}
}

func (b *box) snap() snapshot {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.s
}

func (b *box) config() Config {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.cfg
}

// ---------- 采集 ----------

var corners = [4]string{"lf", "rf", "lb", "rb"} // FL FR RL RR

func collectFast(c *busCtx, b *box, stop <-chan struct{}) {
	fail := 0
	for {
		g, gok := c.getI("get_host_vcu_current_gearlev")
		sp, sok := c.getF("get_host_vcu_raw_car_speed")
		b.mu.Lock()
		if gok {
			b.s.gear, b.s.gearOK = g, true
		}
		if sok {
			b.s.speed, b.s.speedOK = sp, true
		}
		if gok || sok {
			b.s.busOK = true
			fail = 0
		} else {
			fail++
		}
		b.mu.Unlock()
		if fail > 0 && fail%20 == 1 {
			c.lg.Printf("快通道连续失败 %d 次", fail)
		}
		if fail >= 20 {
			c.reconnect()
			fail = 0
		}
		select {
		case <-stop:
			return
		case <-time.After(fastMS * time.Millisecond):
		}
	}
}

func collectSlow(c *busCtx, b *box, stop <-chan struct{}) {
	start := time.Now()
	for {
		cfg := b.config() // 必须在 b.mu 锁外取：config() 内部会加同一把锁
		var wheels [4]wheelData
		cnt := 0
		for i, cn := range corners {
			if v, ok := c.getF("get_host_etpms_pressure_" + cn); ok && v > 0.1 && v < 5.0 {
				wheels[i].bar, wheels[i].barOK = v, true
				cnt++
			}
			if arr, ok := c.getIA("get_host_etpms_temperature_" + cn); ok && len(arr) >= 2 {
				flag, temp := arr[0], arr[len(arr)-1]
				if flag == 0 && temp > -45 && temp < 150 {
					wheels[i].temp, wheels[i].tempOK = float64(temp), true
				}
			}
		}

		cabin, cabinOK := c.getF("get_host_eac_temp_value")
		if !cabinOK || cabin < -30 || cabin > 60 {
			cabinOK = false
		}

		// 行程：host-trip-distance-time 是四个行程页的轮转快照，每页约停留 2 秒：
		//   [页号, 距离 km, 时间 min]
		//   实测 [0.0, 0.0, 25.0]      ↔ 官方左侧"自启动后 0.0 km / 25 min"
		//        [1.0, 256.2, 552.0]  ↔ 官方"上次充电后 256.2 km"
		//        [2/3, 3302.7, 5999]  ↔ 行程 A / B
		// 第 3 个元素直接就是分钟数（不是秒）。只要页号 0；这一轮没轮到就补读几次。
		var tripKm, tripMin, tripAvg float64
		var tripOK bool
		for try := 0; try < 4; try++ {
			if arr, ok := c.getAD("get_host_trip_distance_time"); ok && len(arr) >= 3 &&
				arr[0] == 0 && arr[1] >= 0 && arr[2] >= 0 {
				tripKm, tripMin = arr[1], arr[2]
				tripOK = true
				if tripMin > 0 {
					tripAvg = tripKm * 60 / tripMin
					if tripAvg < 0 || tripAvg > 300 {
						tripAvg = -1
					}
				}
				break
			}
			if try < 3 {
				time.Sleep(400 * time.Millisecond)
			}
		}
		kwh, kwhOK := c.getF("get_host_eavg_vehelcconsp")

		bl, _ := c.getI("get_host_eind_lbsd")
		br, _ := c.getI("get_host_eind_rbsd")

		b.mu.Lock()
		// 车机推来的 kPa 级胎压优先。总线属性只有 0.1 bar 分辨率（四轮都显示 2.40），
		// 车机 CarProperty 的 TPMS_PRxx 是 kPa，能到 0.01 bar（2.42/2.39/2.36/2.44）。
		tpOK := false
		if !b.tpExtAt.IsZero() && time.Since(b.tpExtAt) < 120*time.Second {
			for i := 0; i < 4; i++ {
				if b.tpExt[i] >= 0.5 && b.tpExt[i] <= 5.0 {
					wheels[i].bar, wheels[i].barOK = b.tpExt[i], true
					tpOK = true
				}
			}
		}
		b.s.tpHi = tpOK
		if cnt > 0 || tpOK { // 整轮读不到就保留上一轮，别清屏
			b.s.wheels = wheels
			b.s.busOK = true
		} else {
			// 压力没有、温度可能有，仍然写入温度
			for i := 0; i < 4; i++ {
				b.s.wheels[i].temp, b.s.wheels[i].tempOK = wheels[i].temp, wheels[i].tempOK
			}
		}
		// 行程页是轮转播报的（每页约 2 秒、一圈 8 秒），这一轮没轮到页号 0 属于正常现象：
		// 必须沿用上一次的值，否则下方数据栏会周期性闪没。连续多次都没有才判为真失效。
		if tripOK {
			b.s.tripKm, b.s.tripMin, b.s.tripAvg, b.s.tripOK = tripKm, tripMin, tripAvg, true
			b.tripFail = 0
		} else {
			b.tripFail++
			if b.tripFail > 10 {
				b.s.tripOK = false
			}
		}
		// 车内温度：车机 CarProperty 推来的真实车内温度（HVAC_INNER_TEMPERATURE）优先；
		// 没有时才用仪表总线的空调"设定"温度（host-eac-temp-value）兜底。
		if b.ext.Cabin != nil && *b.ext.Cabin > -30 && *b.ext.Cabin < 80 {
			b.s.cabin, b.s.cabinOK = *b.ext.Cabin, true
			b.cabinFail = 0
		} else if cabinOK {
			b.s.cabin, b.s.cabinOK = cabin, true
			b.cabinFail = 0
		} else {
			b.cabinFail++
			if b.cabinFail > 10 {
				b.s.cabinOK = false
			}
		}
		if kwhOK && kwh >= 0 && kwh < 100 {
			b.s.tripKwh, b.s.avgOK = kwh, true
		}
		// 盲区默认走车机：ext.json 一到就在 watchExt(100ms) 里更新 snapshot，
		// 这里不再覆盖，避免 2 秒慢通道把实时值拖回去（这才是之前延迟大的瓶颈）。
		// 仪表 DBus 的 eind_lbsd/rbsd 只是"指示灯"，实车验证不可靠；
		// 仅当 config.json 里 bsdSrc="bus" 时才强制走仪表通道（保留可回退）。
		if cfg.BSDSrc == "bus" {
			b.s.bsdL, b.s.bsdR = bl > 0, br > 0
		}
		b.mu.Unlock()

		// 慢通道间隔：开机 90s 内若还有轮没读到压力，提速到 600ms 尽快出数（对齐 p7hud）
		iv := slowMS
		if cnt < 4 && time.Since(start) < 90*time.Second {
			iv = 600
		}
		select {
		case <-stop:
			return
		case <-time.After(time.Duration(iv) * time.Millisecond):
		}
	}
}

// watchExt 每秒检查 ext.json（车机写入的电压/导航/日夜）
func watchExt(b *box, stop <-chan struct{}) {
	var lastMod time.Time
	var lastSize int64
	for {
		cfg := b.config()
		st, err := os.Stat(extPath)
		if err == nil && (st.ModTime() != lastMod || st.Size() != lastSize) {
			lastMod, lastSize = st.ModTime(), st.Size()
			ext, age := loadExt(extPath, time.Duration(cfg.Ext.TTLMs)*time.Millisecond)
			b.mu.Lock()
			b.ext = ext
			b.s.extAgeMs = age.Milliseconds()
			// 盲区：默认只认车机 CarProperty 推来的 SCU_BSD_WARNING，
			// ext.json 一变就立即更新，不必等 collectSlow 的 2 秒慢通道。
			if cfg.BSDSrc == "bus" {
				// 交给 collectSlow 用仪表 DBus
			} else if len(ext.BSD) >= 2 {
				b.s.bsdL, b.s.bsdR = ext.BSD[0] > 0, ext.BSD[1] > 0
			} else {
				b.s.bsdL, b.s.bsdR = false, false
			}
			if ext.Volt != nil {
				b.s.volt, b.s.voltOK = *ext.Volt, true
			} else {
				b.s.voltOK = false
			}
			if ext.Nav != nil && ext.Nav.DistM >= 0 && ext.Nav.TimeS >= 0 {
				b.s.nav = navInfo{distM: ext.Nav.DistM, timeS: ext.Nav.TimeS, arrive: ext.Nav.Arrive}
				b.s.navOK = true
				b.s.navAgeMs = age.Milliseconds()
			} else {
				b.s.navOK = false
			}
			// 歌词
			if ext.Lyric != nil && (ext.Lyric.Cur != "" || ext.Lyric.Next != "") {
				b.s.lyric = lyricInfo{
					cur: ext.Lyric.Cur, next: ext.Lyric.Next,
					title: ext.Lyric.Title, artist: ext.Lyric.Artist,
					pos: ext.Lyric.Pos,
					// 车机未上报 play（旧 APK）时保持显示，明确 play:0 才隐藏
					playing: ext.Lyric.Play == nil || *ext.Lyric.Play != 0,
				}
				b.s.lyricOK = true
			} else {
				b.s.lyricOK = false
			}
			// 车机推来的高精度胎压，覆盖总线上的 0.1 bar 粗值（见 collectSlow）
			if len(ext.TPMS) == 4 {
				ok := true
				for i, v := range ext.TPMS {
					if v < 0.5 || v > 5.0 {
						ok = false
						break
					}
					b.tpExt[i] = v
				}
				if ok {
					b.tpExtAt = time.Now()
				} else {
					b.tpExtAt = time.Time{}
				}
			} else {
				// 车机没在推高精度胎压（用户选了仪表总线）→ 立刻回落到总线值，
				// 不要等 120 秒窗口自然过期，否则状态里会一直显示 "car"
				b.tpExtAt = time.Time{}
			}
			b.mu.Unlock()
		} else if err == nil {
			b.mu.Lock()
			b.s.extAgeMs = time.Since(st.ModTime()).Milliseconds()
			if time.Duration(b.s.extAgeMs)*time.Millisecond > time.Duration(cfg.Ext.TTLMs)*time.Millisecond {
				b.s.voltOK, b.s.navOK, b.s.lyricOK = false, false, false
				if cfg.BSDSrc != "bus" { // 车机不再推 ext 时清掉盲区，避免圆弧一直亮着
					b.s.bsdL, b.s.bsdR = false, false
				}
			}
			b.mu.Unlock()
		} else {
			b.mu.Lock()
			b.s.voltOK, b.s.navOK, b.s.lyricOK = false, false, false
			b.s.extAgeMs = -1
			if cfg.BSDSrc != "bus" {
				b.s.bsdL, b.s.bsdR = false, false
			}
			b.mu.Unlock()
		}
		// 红绿灯必须每轮重算：ext.json 可能几十秒才更新一次（车机只在相位
		// 变化时推），但盘上的秒数要一直往下走。这里用 mtime 当观测时刻，
		// 用本地时钟差值把剩余时间扣掉，所以不依赖车机每秒推包。
		if err == nil {
			b.mu.Lock()
			age := time.Since(st.ModTime())
			b.s.tlDemo = b.ext.TL != nil && b.ext.TL.Demo
			if cfg.Features.TL {
				if t, ok := tlFromExt(b.ext.TL, age); ok {
					b.s.tl, b.s.tlOK = t, true
				} else {
					b.s.tlOK = false
				}
			} else {
				b.s.tlOK = false
			}
			b.mu.Unlock()
		} else {
			b.mu.Lock()
			b.s.tlOK = false
			b.mu.Unlock()
		}
		select {
		case <-stop:
			return
		case <-time.After(100 * time.Millisecond):
		}
	}
}

// ---------- 主循环 ----------

var lockFile *os.File

func daemonMain() {
	// 兜底：任何 panic 也会走到 defer fb.close()（其内部先 restore），
	// 这里只负责把 panic 记进日志，方便实车排障。
	defer func() {
		if r := recover(); r != nil {
			dbgLog().Printf("PANIC 已捕获（前景层已恢复）: %v\n%s", r, debug.Stack())
		}
	}()

	lg := dbgLog()
	dir := appDir()

	lf, err := os.OpenFile(dir+"/run.lock", os.O_CREATE|os.O_RDWR, 0644)
	if err == nil {
		if e := syscall.Flock(int(lf.Fd()), syscall.LOCK_EX|syscall.LOCK_NB); e != nil {
			fmt.Println("p7assist: another instance is running, exit")
			return
		}
		lockFile = lf
	}

	lg.Printf("=== p7assist %s start pid=%d dir=%s ===", appVersion, os.Getpid(), dir)
	os.Remove(stopPath)

	// charge.bin 内置帧包不再加载：自播兜底与调试静态图都已移除，
	// 充电页只有"车机投帧"和"本地播放器"两个来源，都没有就不接管、还原原厂。

	cfg, cfgAt, _ := loadConfig(cfgPath)
	b := &box{cfg: cfg, cfgAt: cfgAt}
	lg.Printf("配置: 全局=%v mask=%d 日夜=%s zoom=%.0fkmh/%.0fms", cfg.Enabled, cfg.mask(), cfg.DayNight, cfg.Zoom.Speed, cfg.Zoom.Ms)

	// 1) 先连 bus 并起采集：切 fb1 要几秒，这段时间足够把胎压拿到手
	conn, err := waitBus(lg, 5*time.Minute)
	if err != nil {
		lg.Printf("FATAL bus: %v", err)
		return
	}
	c := &busCtx{conn: conn, obj: conn.Object(busDest, dbus.ObjectPath(busPath)), lg: lg}
	lg.Printf("bus ok: %s", findBusAddress())
	if err := c.enableSignals(); err != nil {
		lg.Printf("订阅属性信号失败（退回轮询）: %v", err)
	}

	stopc := make(chan struct{})
	defer close(stopc)
	go collectFast(c, b, stopc)
	go collectSlow(c, b, stopc)
	go watchExt(b, stopc)
	go pollChargeLoop(c, b, stopc)
	go collectChargeLoop(c, b, stopc)
	// 导出一次 DBus 属性清单（已存在则跳过），供温度/警示类属性标定
	go dumpBusProps(c, lg, false)

	// 信号线程：只更新快照，渲染循环下一帧就会用上
	go func() {
		for {
			ch := c.currentSig()
			if ch == nil {
				select {
				case <-stopc:
					return
				case <-time.After(time.Second):
				}
				continue
			}
			select {
			case <-stopc:
				return
			case sig := <-ch:
				if sig == nil {
					continue
				}
				b.mu.Lock()
				applySignal(&b.s, sig, b.cfg.BSDSrc == "bus")
				b.mu.Unlock()
			case <-time.After(5 * time.Second):
			}
		}
	}()

	// 上一次遗留的播放器（车机把仪表二进制刷回旧版时会出现孤儿进程）还在写 fb1，
	// 必须先清掉，否则两边互抢——它关了色键，我们的黑就变成不透明整层。
	killStaleVideo(lg)
	reapZombies(lg)
	reapZombies(lg)

	// 2) 切 fb1
	if err := ensureMode(dir, lg); err != nil {
		lg.Printf("FATAL mode: %v", err)
		return
	}
	fb, err := openFB()
	if err != nil {
		lg.Printf("FATAL open fb: %v", err)
		return
	}
	defer fb.close()
	if fb.w != frameW || fb.h != frameH {
		lg.Printf("FATAL fb geom %dx%d", fb.w, fb.h)
		restore(fb)
		return
	}
	setFGAttrs(fb)
	layerOn(fb)
	fb.clearAll()
	// 开层失败时不谎报「已打开」，交给主循环的 applyOn(true) 再试
	screenOnInit := !layerBlanked()
	lg.Printf("fb ok: %dx%d stride=%d", fb.w, fb.h, fb.lineLen)

	// 3) 信号处理：退出一定清像素 + 关层
	sigc := make(chan os.Signal, 1)
	signal.Notify(sigc, syscall.SIGTERM, syscall.SIGINT, syscall.SIGHUP)
	done := make(chan struct{})
	go func() {
		select {
		case s := <-sigc:
			lg.Printf("signal %v -> restore", s)
		case <-done:
		}
		stopVideo(lg) // 播放器还在跑就先收掉，避免它继续写 fb1
		restore(fb)
		lg.Printf("=== p7assist exit ===")
		os.Exit(0)
	}()

	cv := newCanvas()
	var prev rect
	first := true
	screenOn := screenOnInit
	tAnim := -1.0
	lastT := time.Now()
	var lastStatus time.Time
	var lastCfgChk time.Time
	var lastGeomChk time.Time
	lastForce := -1 // charge.force 上一次的值（-1=文件不存在），只在变化时打日志
	// 待升级文件的 (size/mtime) 指纹：只有连续 upgradeHoldS 秒不变才采纳
	var upSig string
	var upSince time.Time
	// 上一次因帧包更新重启播放器的时间（防抖，见视频模式分支）
	var lastPackRestart time.Time
	frames := int64(0)

	// 屏幕层开关：关掉时清像素 + FBIOBLANK(4)，原厂画面立即透出
	applyOn := func(on bool, why string) {
		if on == screenOn {
			return
		}
		if !on {
			prev = prev.clamp()
			fb.clearBox(prev)
			fbBlank(fb, 4)
			// 画布和脏区一起清掉：下次开层必须整帧重画，不能拿旧内容补
			cv.clearAll()
			prev = rect{}
			first = true
			screenOn = false
			lg.Printf("屏幕: 关闭（%s）", why)
			return
		}
		ensureMode(dir, lg)
		if d, err := openFB(); err == nil {
			fb.closeQuiet()
			fb = d
		} else {
			lg.Printf("开层: 重开 fb1 失败 %v（沿用旧句柄）", err)
		}
		setFGAttrs(fb)
		layerOn(fb)
		fb.clearAll()
		cv.clearAll()
		prev = rect{}
		first = true
		screenOn = true
		lg.Printf("屏幕: 打开（%s）", why)
	}

	// recoverFG 强制重走 setMode 并恢复本进程的 fb1 状态。
	//
	// 为什么不能用 ensureMode：播放器退出时把 fb1 写回 240x320/1920,720 后，
	// 几何"看起来正常"，ensureMode 会直接返回，但 overlay 通道的注册已经掉了
	// —— 内存里像素是对的，屏幕上什么都不显示。只有真正重走 setmode 才会重新注册。
	recoverFG := func(why string) {
		if err := setMode(dir, lg); err != nil {
			lg.Printf("视频: %s 重设 mode 失败 %v", why, err)
			return
		}
		if d, err := openFB(); err == nil {
			fb.closeQuiet()
			fb = d
			setFGAttrs(fb)
			layerOn(fb)
			fb.clearAll()
			cv.clearAll()
			prev = rect{}
			first = true
			lg.Printf("视频: %s → fb1 已重新注册并清屏", why)
		} else {
			lg.Printf("视频: %s 重开 fb1 失败 %v", why, err)
		}
	}

	var (
		lastChargeSig uint64
		chargeActive  bool
	)
	tick := time.NewTicker(frameMS * time.Millisecond)
	defer tick.Stop()

	for range tick.C {
		now := time.Now()

		// stop 文件
		if _, err := os.Stat(stopPath); err == nil {
			lg.Printf("stop 文件 -> 退出")
			os.Remove(stopPath)
			close(done)
			stopVideo(lg)
			restore(fb)
			lg.Printf("=== p7assist exit ===")
			return
		}
		// restartvideo 文件 -> 重启本地播放器（视频模式下的调试入口）
		//
		// 车机「上传资源」推完新帧包也会写这个文件：旧版 daemon 不会自己发现换包，
		// 没有这个信号就只能让用户先「还原」再「上传」才能换掉内存里的旧帧。
		// 新版 daemon 的 packChanged 可能已经在同一秒自己重启过一次，
		// 所以这里和换包重启共用 5 秒冷却，避免一次上传触发两次黑屏。
		if _, err := os.Stat(dir + "/restartvideo"); err == nil {
			os.Remove(dir + "/restartvideo")
			if time.Since(lastPackRestart) > 5*time.Second {
				lastPackRestart = now
				lg.Printf("视频: 收到重启请求")
				stopVideo(lg)
				recoverFG("重启播放器") // 不重注册的话新播放器写了也不显示
				videoTouched = false
			} else {
				lg.Printf("视频: 重启请求与换包重启重合，已合并为一次")
			}
		}
		// restart 文件 -> 原地 exec
		if _, err := os.Stat(restartPath); err == nil {
			os.Remove(restartPath)
			lg.Printf("restart 文件 -> 重新载入自身")
			stopVideo(lg)
			restore(fb)
			exe, _ := os.Executable()
			syscall.Exec(exe, os.Args, os.Environ())
			return
		}
		// reinit 文件 -> 强制重新初始化前景层。
		// 用在「几何、blank 都正常，进程在画，但屏幕上看不到」这种情形：原厂接管过
		// fb1 之后 overlay 通道可能不再参与合成，只有重走 setmode 才能重新注册。
		if _, err := os.Stat(reinitPath); err == nil {
			os.Remove(reinitPath)
			lg.Printf("reinit: 强制重新初始化 fb1（当前 stride=%s vsize=%s blank=%s）",
				readSys("stride"), readSys("virtual_size"), readSys("blank"))
			fbBlank(fb, 4)
			time.Sleep(400 * time.Millisecond)
			if err := setMode(dir, lg); err != nil {
				lg.Printf("reinit: setmode 失败 %v", err)
			}
			if d, err := openFB(); err == nil {
				fb.closeQuiet()
				fb = d
			} else {
				lg.Printf("reinit: 重开 fb1 失败 %v", err)
			}
			setFGAttrs(fb)
			layerOn(fb)
			fb.clearAll()
			cv.clearAll()
			prev = rect{}
			first = true
			lg.Printf("reinit: 完成 stride=%s vsize=%s blank=%s fb=%v",
				readSys("stride"), readSys("virtual_size"), readSys("blank"), listFB())
		}

		// 新二进制（车机经 FTP 推上来）-> 等写入稳定 + 校验 ELF 后热替换并 exec。
		// 不能一看到就换：传输中途的文件是个缺尾巴的半截 ELF，换上就等于把
		// 进程换成起不来的文件（2026-11-15 实车踩过，见 upgrade.go 注释）。
		if st, err := os.Stat(upgradePath); err == nil && st.Size() > 64*1024 {
			sig := fmt.Sprintf("%d/%d", st.Size(), st.ModTime().UnixNano())
			if sig != upSig {
				upSig, upSince = sig, now
				lg.Printf("待升级文件 %d 字节，等写入稳定 %ds 后再替换", st.Size(), upgradeHoldS)
			} else if upgradeStable(sig, upSig, upSince, now) {
				if !isELF(upgradePath) {
					lg.Printf("%s 不是 ELF（%d 字节），已忽略", upgradePath, st.Size())
					_ = os.Remove(upgradePath)
					upSig = ""
				} else {
					lg.Printf("发现新版本 %d 字节（已稳定 %ds），热替换并重启", st.Size(), upgradeHoldS)
					_ = os.Chmod(upgradePath, 0755)
					restore(fb)
					if exe, err := os.Executable(); err == nil {
						if err := os.Rename(upgradePath, exe); err == nil {
							syscall.Exec(exe, os.Args, os.Environ())
							return
						}
					}
					lg.Printf("热替换失败，保留旧版本运行")
				}
			}
		} else if upSig != "" {
			upSig = "" // 文件被拿走（上传方改名/删除），重置观察窗口
		}

		// 抓帧请求：写一张当前屏幕的合成图，供 PC 侧下载查看
		// （shot.req 内容为 bg 时只导原厂画面，用作预览底图）
		if _, err := os.Stat(shotReqPath); err == nil {
			onlyBg := false
			if b, err := os.ReadFile(shotReqPath); err == nil && strings.TrimSpace(string(b)) == "bg" {
				onlyBg = true
			}
			os.Remove(shotReqPath)
			out := shotPath
			if onlyBg {
				out = strings.TrimSuffix(shotPath, ".png") + "_bg.png"
			}
			if err := captureShot(fb, out, onlyBg); err != nil {
				lg.Printf("抓帧失败: %v", err)
			} else {
				lg.Printf("抓帧完成: %s", out)
			}
		}

		// 配置热加载
		if now.Sub(lastCfgChk) > 500*time.Millisecond {
			lastCfgChk = now
			if st, err := os.Stat(cfgPath); err == nil && !st.ModTime().Equal(b.cfgAt) {
				nc, at, err := loadConfig(cfgPath)
				if err != nil {
					lg.Printf("配置解析失败（可能是半截文件），沿用旧配置: %v", err)
				}
				if err == nil {
					b.mu.Lock()
					b.cfg, b.cfgAt = nc, at
					b.mu.Unlock()
					lg.Printf("配置已更新: 全局=%v mask=%d 日夜=%s zoom=%.0f/%.0f", nc.Enabled, nc.mask(), nc.DayNight, nc.Zoom.Speed, nc.Zoom.Ms)
				}
			}
		}
		cfg2 := b.config()

		// 视频模式：充电页整屏交给仪表本地播放器（p7videoplay 读 charge.pack 播动画，
		// 本进程只负责每秒刷一次文字层 JPEG）。见 videoplay.go / chgoverlay.go。
		//
		// 必须在本轮任何 fb1 操作之前定性——包括下面的几何自愈、applyOn、blit，
		// 否则会把播放器刚设好的 fb1 mode / 层状态顶掉，两边互相打断。
		bs := b.snap()
		// 视频模式的进入条件：真充电，或用户主动打开「充电页调试」。
		// 调试必须走视频——当前实现就是本地播放器播 charge.pack，调试静态页没有意义
		// （用户 2026-11-19 明确指出）。文字层由 chargeView 提供，调试时会用演示数据。
		chgOnNow := (cfg2.Features.ChargePage && bs.chg.on) || cfg2.Features.ChargeDebug
		gearHiddenNow := bs.gearOK && bs.gear == 3
		wantVideo := cfg2.Enabled && chgOnNow && !gearHiddenNow && videoUsable(dir)
		// 会话状态机（Issue #7）：进入要满足冷却，退出立即生效但设 5s 冷却。
		// 这样 chg.on 抖动时不会反复退出重进（每次退出近 6s 阻塞 + 黑闪）。
		// 唯一合法退出原因是拔枪/停充/挂 R 挡/资源消失——都已体现在 wantVideo。
		switch {
		case !videoOn && wantVideo && now.After(videoCoolUntil):
			videoOn = true
		case videoOn && !wantVideo:
			videoOn = false
			videoCoolUntil = now.Add(5 * time.Second)
		}
		videoMode := videoOn
		if videoMode {
			// 三种情况都要重启播放器：换了帧包 / 改了帧率 / 改了写屏方式。
			// 播放器只在启动时读一次包和参数，不重启就一直按旧的跑。
			// 重启会黑屏约 4~6 秒（播放器 prime 要重走 setmode），所以加 5 秒冷却防抖。
			wantRestart, whyRestart := false, ""
			if packChanged(dir) {
				wantRestart, whyRestart = true, "帧包已更新("+packSig(dir)+")"
			} else if tuneChanged(dir) {
				wantRestart, whyRestart = true, "播放参数已改("+videoTuneNow(dir)+")"
			}
			if wantRestart && now.Sub(lastPackRestart) > 5*time.Second {
				lastPackRestart = now
				lg.Printf("视频: %s，重启播放器加载新设置", whyRestart)
				stopVideo(lg)
				startVideo(dir, lg)
				videoTouched = true
			}
			startVideo(dir, lg)
			videoTouched = true
			if overlayDue() {
				if err := writeChargeOverlay(dir, cfg2, bs, tAnim); err != nil {
					lg.Printf("文字层生成失败: %v", err)
				}
			}
		} else if videoTouched || videoRunning() {
			// 退出视频模式。注意触发条件用 videoTouched 而不是 videoRunning：
			// 播放器被外部 kill（升级、调试、崩溃）时 videoCmd 已经是 nil，
			// 但它已经把 fb1 的色键关掉了，不重置的话我们的黑会变成不透明整层，
			// 直接把原厂仪表盖成一片黑（踩过一次）。
			stopVideo(lg)
			recoverFG("退出视频模式") // 同时重开色键（纯黑=透明）
			videoTouched = false
			// 画面被播放器改过，签名作废，下一帧必须整帧重绘
			lastChargeSig = 0
			chargeActive = false
			lg.Printf("视频: 已退出视频模式，fb1 重新初始化完成")
		}

		// 充电让出：车在充电时停止绘制，画面整块交还原厂充电页；充电结束/拔枪后自动恢复。
		// 判定来自仪表本地 DBus（charge.go），charge.force 只用于调试与应急强制。
		if f := readChargeForce(dir); f != lastForce {
			switch f {
			case 1:
				lg.Printf("充电让出: charge.force=1 强制让出（停止绘制）")
			case 0:
				lg.Printf("充电让出: charge.force=0 强制不让出（继续绘制）")
			default:
				lg.Printf("充电让出: charge.force 已移除，按车况判断")
			}
			lastForce = f
		}
		// chgDet 是"车在充电"的原始判定；hold 是"要不要把屏幕让给原厂充电页"。
		// 开了充电页之后必须 hold=false，否则会被自己让出屏幕，永远画不出来。
		chgDet := b.chargeHold()
		hold := resolveHold(cfg2, chgDet, lastForce)
		if cfg2.Features.ChargePage || cfg2.Features.ChargeDebug {
			hold = false
		}

		// fb1 被重置 / 被原厂挪走 / 层被关掉时自动恢复。
		// 充电让出期间绝不能碰：原厂会把 fb1 切到它自己的尺寸，这时再 ensureMode
		// 等于把原厂充电页顶掉，而且两边会互相抢模式。
		if !hold && !videoMode && now.Sub(lastGeomChk) > 2*time.Second {
			lastGeomChk = now
			switch {
			case !fbGeomOK():
				// 尺寸/缓冲被改：必须重走 setmode（慢，会阻塞十几秒，但这种情况很少）
				lg.Printf("fb1 几何异常(stride=%s vsize=%s)，重新设置",
					readSys("stride"), readSys("virtual_size"))
				ensureMode(dir, lg)
				if d, err := openFB(); err == nil {
					fb.closeQuiet()
					fb = d
					setFGAttrs(fb)
					layerOn(fb)
					fb.clearAll()
					cv.clearAll()
					prev = rect{}
					first = true
				} else {
					lg.Printf("fb1 重开失败: %v（沿用旧句柄）", err)
				}
			case screenOn && layerBlanked():
				// 几何正常、只是层被别人关了：重新开层 + 整帧重画，不动 setmode
				lg.Printf("前景层被关(blank=%s)，重新打开", readSys("blank"))
				layerOn(fb)
				fb.clearAll()
				cv.clearAll()
				prev = rect{}
				first = true
			}
		}

		// 视频模式下显式不碰 fb1（Issue #7 ③）：播放器已接管整层，
		// 这里一旦执行 applyOn 的关层分支就会 clearBox + fbBlank，把播放器画面抹掉。
		// 之前靠 hold=false 让 applyOn 恰好早退是巧合，不是约束。
		// 注意不能写成 applyOn(cfg2.Enabled && !hold && !videoMode, ...)——
		// 那样反而会在进入播放时触发关层分支，播放器就没有层可写了。
		if !videoMode {
			applyOn(cfg2.Enabled && !hold, screenWhy(cfg2.Enabled, hold))
		}

		// 状态文件 2s 一次
		if now.Sub(lastStatus) > 2*time.Second {
			lastStatus = now
			chgRaw := b.chargeRaw() // 必须在 b.mu 之外取（chargeRaw 自己会加锁）
			b.mu.Lock()
			b.s.frames = frames
			writeStatus(b.s, cfg2, screenOn, chgDet, chgRaw)
			b.mu.Unlock()
		}

		// 视频模式：动画和文字都由播放器那侧合成，本进程这一轮完全不碰 fb1
		if videoMode {
			// ⑦ 缩放动画时钟即便在视频模式也要推进（Issue #7）：
			// tAnim 供文字层 overlay 的警示条轮播使用；原实现把它放在本 continue
			// 之后，导致播放期间 tAnim 冻结、警示条不转。这里单独推进它。
			vs := b.snap()
			vtarget := 0.0
			if vs.speedOK && vs.speed >= cfg2.Zoom.Speed {
				vtarget = 1.0
			}
			vdt := float64(now.Sub(lastT)) / float64(time.Millisecond)
			lastT = now
			tAnim = stepT(tAnim, vtarget, vdt, cfg2.Zoom.Ms)
			continue
		}

		// 充电中 / 全局关闭：都不画，保持原厂画面
		if !cfg2.Enabled || hold {
			continue
		}

		b.mu.Lock()
		s := b.s
		ext := b.ext
		b.mu.Unlock()

		if changed, lum := b.pollScreenNight(fb); changed {
			if b.lumValid {
				if b.lumNight {
					lg.Printf("昼夜采样: 原厂画面亮度=%d → 夜间", lum)
				} else {
					lg.Printf("昼夜采样: 原厂画面亮度=%d → 白天", lum)
				}
			} else {
				lg.Printf("昼夜采样失效(亮度=%d)，回落车机/时间", lum)
			}
		}
		night := resolveNight(cfg2, ext, b.lumNight, b.lumValid)

		// 挡位 -> 布局
		park := true
		if s.gearOK {
			park = s.gear == 4
		}
		// 倒挡(R=3)沿用原厂行为：整块隐藏
		hidden := s.gearOK && s.gear == 3

		target := 0.0
		if s.speedOK && s.speed >= cfg2.Zoom.Speed {
			target = 1.0
		}
		dt := float64(now.Sub(lastT)) / float64(time.Millisecond)
		lastT = now
		tAnim = stepT(tAnim, target, dt, cfg2.Zoom.Ms)

		// 收车机投过来的充电动画帧（一个 stat 的开销；有变化才解码）
		if (cfg2.Features.ChargePage && s.chg.on) || cfg2.Features.ChargeDebug {
			pollChgFrame(dir)
		}

		// 充电页：画面没变就整帧跳过。
		// 仪表全屏重绘一次要 300~450ms，如果被 30fps 的 ticker 一直拖着画，
		// 整个界面（数字、警示）都会跟着卡；改成"有新帧/数据变了才重绘"之后，
		// 空闲时几乎不占 CPU，观感才正常。
		if !hidden && ((cfg2.Features.ChargePage && s.chg.on) || cfg2.Features.ChargeDebug) {
			sig := chargeDrawSig(cfg2, s, tAnim)
			if chargeActive && sig == lastChargeSig {
				continue
			}
			lastChargeSig, chargeActive = sig, true
		} else if chargeActive {
			chargeActive = false
		}

		// 清上一帧区域
		cv.clearRect(prev)
		cv.dirty = rect{}
		// 歌词卡片要按原厂底图取色，这里把 fb0 句柄挂上（fb 可能被重开过，每帧赋值）
		cv.bg = fb.bg

		drawn := false
		chargeFull := false
		if !hidden {
			// 充电页是整屏不透明画面：标记出来，上屏走整块写快路径
			chargeFull = (cfg2.Features.ChargePage && s.chg.on) || cfg2.Features.ChargeDebug
			paintFrame(cv, cfg2, s, park, tAnim, night)
			drawn = !cv.dirty.empty()
		}
		nd := cv.dirty.clamp()

		needBlit := !first && (!prev.empty() || !nd.empty())
		if drawn || needBlit {
			boxBlit := prev.union(nd).clamp()
			if chargeFull {
				fb.blitOpaqueFull(cv)
			} else if !boxBlit.empty() {
				fb.blit(cv, boxBlit)
			}
		}
		first = false
		prev = nd
		if drawn {
			frames++
		}
	}
}

func isELF(path string) bool {
	f, err := os.Open(path)
	if err != nil {
		return false
	}
	defer f.Close()
	var h [4]byte
	if _, err := f.Read(h[:]); err != nil {
		return false
	}
	return h[0] == 0x7f && h[1] == 'E' && h[2] == 'L' && h[3] == 'F'
}

func writeStatus(s snapshot, cfg Config, screenOn bool, charging bool, chgRaw string) {
	_ = saveJSON(statusPath, buildStatus(s, cfg, screenOn, charging, chgRaw))
}
