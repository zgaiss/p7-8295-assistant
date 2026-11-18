package main

// 充电让出的「假数据」端到端验证：不用真车、不连 DBus、不碰 fb1。
//
// 做法是把真机主循环里每帧要做的那三步原样搬过来：
//
//	chargeReader 取数 → box.applyProbe 去抖 → resolveHold 合成结论 → 层开关
//
// 只有最后一步换成记录用的假层（fakeLayer），其余全是线上代码。
// 跑法：go test -run TestChargeYieldMock -v
//
// 真机上还有更省事的验证手段：echo 1 > /data/p7assist/charge.force
// （不看车况直接让出），本文末尾的 TestResolveHoldPriority 覆盖了它的优先级。

import (
	"fmt"
	"strings"
	"testing"
	"time"
)

// mockChargeBus 假的 DBus：按方法名返回预设值，模拟仪表总线的读数
type mockChargeBus struct {
	mcu, gun, st int32
	cur          float64
	okMcu        bool
	okGun        bool
	okSt         bool
	okCur        bool
}

func (m mockChargeBus) getI(method string) (int32, bool) {
	switch method {
	case "get_host_mcu_charging_state":
		return m.mcu, m.okMcu
	case "get_host_vcu_charge_gun_status":
		return m.gun, m.okGun
	case "get_host_echarge_status":
		return m.st, m.okSt
	}
	return 0, false
}

func (m mockChargeBus) getAD(method string) ([]float64, bool) {
	if method == "get_host_echarge_cur" {
		return []float64{m.cur}, m.okCur
	}
	return nil, false
}

// 常用车况
func idleBus() mockChargeBus {
	return mockChargeBus{mcu: 0, gun: 0, st: 15, okMcu: true, okGun: true, okSt: true}
}
func chargingBus() mockChargeBus {
	return mockChargeBus{mcu: 2, gun: 2, st: 3, cur: 12.5, okMcu: true, okGun: true, okSt: true, okCur: true}
}
func deadBus() mockChargeBus { return mockChargeBus{} }

// fakeLayer 代替 fb1 的开关层动作：只记录「开/关」和「这一帧画没画」
type fakeLayer struct {
	on      bool
	switchN int
	actions []string
	paintN  int
}

// apply 对应主循环里的 applyOn(cfg.Enabled && !hold, why)
func (l *fakeLayer) apply(want bool, sec int) {
	if want == l.on {
		return
	}
	l.on = want
	l.switchN++
	word := "off"
	if want {
		word = "on"
	}
	l.actions = append(l.actions, fmt.Sprintf("%s@%ds", word, sec))
}

type fakeTick struct {
	bus   mockChargeBus
	force int // -1 无 force 文件
	note  string
}

// runTimeline 按秒驱动一次「采集 → 去抖 → 决策 → 开层 → 画」，返回层记录与判定轨迹
func runTimeline(t *testing.T, cfg Config, ticks []fakeTick) (*fakeLayer, []string) {
	t.Helper()
	var st chargeState
	// 初始按「进程起来时前景层是开的」算（等价 ensureMode 成功后的常态），
	// 这样记录里只剩真正的开关动作
	lay := &fakeLayer{on: true}
	var holdTrace []string

	for sec, tk := range ticks {
		p := readChargeProbe(tk.bus)            // 线上代码：取数
		deb, _, _ := st.apply(p)                // 线上代码：去抖
		hold := resolveHold(cfg, deb, tk.force) // 线上代码：合成结论
		lay.apply(cfg.Enabled && !hold, sec)    // 假层：记录开关
		if cfg.Enabled && !hold {
			lay.paintN++
		}
		h := "画"
		if hold {
			h = "停"
		}
		holdTrace = append(holdTrace, fmt.Sprintf("t=%02ds %-10s %-28s hold=%v → %s",
			sec, tk.note, p, hold, h))
	}
	return lay, holdTrace
}

func dumpTrace(t *testing.T, name string, trace []string, lay *fakeLayer) {
	t.Helper()
	t.Logf("── %s ──", name)
	for _, line := range trace {
		t.Log("  " + line)
	}
	t.Logf("  层动作: %v（共 %d 次），实际绘制 %d 帧", lay.actions, lay.switchN, lay.paintN)
}

// TestChargeYieldMock 完整插拔一遍：未充 → 插枪 → 充电 → 拔枪 → 恢复
func TestChargeYieldMock(t *testing.T) {
	cfg := defaultConfig()
	cfg.Enabled = true

	var ticks []fakeTick
	for sec := 0; sec < 30; sec++ {
		switch {
		case sec < 5:
			ticks = append(ticks, fakeTick{idleBus(), -1, "行驶/未充电"})
		case sec == 5:
			ticks = append(ticks, fakeTick{chargingBus(), -1, "插枪(第1次)"})
		case sec < 20:
			ticks = append(ticks, fakeTick{chargingBus(), -1, "充电中"})
		case sec == 20:
			ticks = append(ticks, fakeTick{idleBus(), -1, "拔枪(第1次)"})
		default:
			ticks = append(ticks, fakeTick{idleBus(), -1, "未充电"})
		}
	}

	lay, trace := runTimeline(t, cfg, ticks)
	dumpTrace(t, "完整插拔 30 秒", trace, lay)

	if got := strings.Join(lay.actions, ","); got != "off@6s,on@21s" {
		t.Fatalf("层动作应为 off@6s,on@21s，实际 %q", got)
	}
	// 0-5 秒画 6 帧，21-29 秒画 9 帧，充电期间一帧都不画
	if lay.paintN != 15 {
		t.Fatalf("绘制帧数应为 15，实际 %d", lay.paintN)
	}
	// 关层时刻之后必须立刻停画，不能残留旧像素
	if lay.switchN != 2 {
		t.Fatalf("层只该切换 2 次（进/出充电），实际 %d 次: %v", lay.switchN, lay.actions)
	}
}

// TestChargeYieldMockJitter 插枪抖了一下又拔掉：去抖必须拦住，不能来回闪
func TestChargeYieldMockJitter(t *testing.T) {
	cfg := defaultConfig()
	var ticks []fakeTick
	for sec := 0; sec < 10; sec++ {
		if sec == 5 {
			ticks = append(ticks, fakeTick{chargingBus(), -1, "瞬时命中"})
		} else {
			ticks = append(ticks, fakeTick{idleBus(), -1, "未充电"})
		}
	}
	lay, trace := runTimeline(t, cfg, ticks)
	dumpTrace(t, "插枪抖动 1 秒", trace, lay)
	if lay.switchN != 0 {
		t.Fatalf("单次命中不该切层，实际: %v", lay.actions)
	}
	if lay.paintN != 10 {
		t.Fatalf("应始终绘制 10 帧，实际 %d", lay.paintN)
	}
}

// TestChargeYieldMockBootInCharge 开机就在充电：第一帧就得让出，不能先画一帧再收回
func TestChargeYieldMockBootInCharge(t *testing.T) {
	cfg := defaultConfig()
	var ticks []fakeTick
	for sec := 0; sec < 10; sec++ {
		ticks = append(ticks, fakeTick{chargingBus(), -1, "开机即在充电"})
	}
	lay, trace := runTimeline(t, cfg, ticks)
	dumpTrace(t, "开机即在充电", trace, lay)
	if lay.switchN != 1 || strings.Join(lay.actions, ",") != "off@0s" {
		t.Fatalf("应立刻关层，实际: %v", lay.actions)
	}
	if lay.paintN != 0 {
		t.Fatalf("一帧都不该画，实际 %d", lay.paintN)
	}
}

// TestChargeYieldMockDeadBus 总线全挂 / 只有电流：宁可照旧画，也不凭空空关界面
func TestChargeYieldMockDeadBus(t *testing.T) {
	cfg := defaultConfig()

	// 全读不到 → 按未充电，继续画
	dead := make([]fakeTick, 5)
	for i := range dead {
		dead[i] = fakeTick{deadBus(), -1, "总线全挂"}
	}
	lay, trace := runTimeline(t, cfg, dead)
	dumpTrace(t, "总线全挂", trace, lay)
	if lay.switchN != 0 || lay.paintN != 5 {
		t.Fatalf("总线挂了应继续绘制，实际 %v paint=%d", lay.actions, lay.paintN)
	}

	// 两条状态量都读不到，只剩充电电流 → 兜底判充电
	onlyCur := mockChargeBus{cur: 12.5, okCur: true}
	var ticks []fakeTick
	for i := 0; i < 5; i++ {
		ticks = append(ticks, fakeTick{onlyCur, -1, "仅电流 12.5A"})
	}
	lay2, trace2 := runTimeline(t, cfg, ticks)
	dumpTrace(t, "仅充电电流", trace2, lay2)
	if strings.Join(lay2.actions, ",") != "off@0s" {
		t.Fatalf("仅电流也应让出，实际 %v", lay2.actions)
	}
}

// TestResolveHoldPriority charge.force / features.chargeYield 的优先级
func TestResolveHoldPriority(t *testing.T) {
	on := defaultConfig() // chargeYield 默认 true
	off := defaultConfig()
	off.Features.ChargeYield = false

	cases := []struct {
		name  string
		cfg   Config
		deb   bool
		force int
		want  bool
	}{
		{"充电 + 无 force", on, true, -1, true},
		{"未充电 + 无 force", on, false, -1, false},
		{"未充电 + force=1（强制让出）", on, false, 1, true},
		{"充电中 + force=0（强制不让出）", on, true, 0, false},
		{"充电中 + chargeYield=false", off, true, -1, false},
		{"充电中 + force=1 + chargeYield=false（总开关一票否决）", off, true, 1, false},
	}
	for _, c := range cases {
		if got := resolveHold(c.cfg, c.deb, c.force); got != c.want {
			t.Fatalf("%s: got %v want %v", c.name, got, c.want)
		}
	}
}

// TestChargeStatusDoc 让出期间 status.json 要能一眼看出来
func TestChargeStatusDoc(t *testing.T) {
	cfg := defaultConfig()
	s := snapshot{busOK: true, gear: 4, gearOK: true, frames: 123}
	doc := buildStatus(s, cfg, false, true, "mcu=2 gun=2 st=3 cur=12.5")
	if !doc.Charging {
		t.Fatal("charging 应为 true")
	}
	if doc.Screen != "off" {
		t.Fatalf("让出期间 screen 应为 off，实际 %q", doc.Screen)
	}
	if doc.Chg != "mcu=2 gun=2 st=3 cur=12.5" {
		t.Fatalf("chg 原始读数没写进去: %q", doc.Chg)
	}
	doc2 := buildStatus(s, cfg, true, false, "mcu=0 gun=0 st=15 cur=0.0")
	if doc2.Charging || doc2.Screen != "on" {
		t.Fatalf("未充电时应 charging=false screen=on，实际 %v/%q", doc2.Charging, doc2.Screen)
	}
}

// TestChargePollInterval 轮询间隔与去抖次数是配套的：2 次 × 1 秒 ≈ 2 秒响应
func TestChargePollInterval(t *testing.T) {
	if d := time.Duration(chargePollMS) * time.Millisecond; d != time.Second {
		t.Fatalf("轮询间隔 %v，去抖窗口假设是 1 秒", d)
	}
	if chargeDebounceN != 2 {
		t.Fatalf("去抖次数 %d，注释里的 2 秒要跟着改", chargeDebounceN)
	}
}
