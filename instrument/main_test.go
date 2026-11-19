package main

import (
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestStepT(t *testing.T) {
	// 首帧直接对齐
	if got := stepT(-1, 1, 16, 4000); got != 1 {
		t.Fatalf("init -> %v, want 1", got)
	}
	// 4000ms 从 0 到 1
	v := 0.0
	for i := 0; i < 250; i++ {
		v = stepT(v, 1, 16, 4000)
	}
	if v != 1 {
		t.Fatalf("after 4s -> %v, want 1", v)
	}
	// 回程吸附
	v = 1
	for i := 0; i < 250; i++ {
		v = stepT(v, 0, 16, 4000)
	}
	if v != 0 {
		t.Fatalf("back -> %v, want 0", v)
	}
	// 挂起后不要一跳到底
	if got := stepT(0.9, 0, 60000, 4000); got < 0.6 {
		t.Fatalf("long dt clamped? got %v", got)
	}
}

func TestConfigMask(t *testing.T) {
	c := defaultConfig()
	// 默认开启：胎压显示/精度、盲区、导航、歌词（不含底部歌词）、转向灯、行程四项、红绿灯、
	// 弹窗避让（2097152）
	want := 2 + 4 + 32 + 64 + 128 + 256 + 512 + 1024 + 2048 + 8192 + 2097152
	if c.mask() != want {
		t.Fatalf("mask=%d want %d", c.mask(), want)
	}
	c.Features.Volt = true
	c.Features.Cabin = true
	c.Features.Debug = true
	if c.mask()&1 == 0 || c.mask()&16 == 0 || c.mask()&8 == 0 {
		t.Fatalf("mask bits missing: %d", c.mask())
	}
}

func TestExtTTL(t *testing.T) {
	dir := t.TempDir()
	p := filepath.Join(dir, "ext.json")
	volt := 12.4
	writeExt(t, p, `{"ts":0,"volt":12.4,"night":1,"nav":{"distM":1200,"timeS":60,"arrive":"09:10"}}`)
	e, _ := loadExt(p, 30*time.Second)
	if e.Volt == nil || *e.Volt != volt {
		t.Fatalf("volt=%v", e.Volt)
	}
	if e.Nav == nil || e.Nav.DistM != 1200 {
		t.Fatalf("nav=%+v", e.Nav)
	}
	// 把 mtime 拨到 1 分钟前 -> 过期，只保留日夜
	old := time.Now().Add(-time.Minute)
	os.Chtimes(p, old, old)
	e2, age := loadExt(p, 30*time.Second)
	if age < 50*time.Second {
		t.Fatalf("age=%v", age)
	}
	if e2.Volt != nil || e2.Nav != nil {
		t.Fatalf("stale should be dropped: %+v", e2)
	}
	if e2.Night == nil || *e2.Night != 1 {
		t.Fatalf("night should survive: %v", e2.Night)
	}
}

func writeExt(t *testing.T, p, s string) {
	t.Helper()
	if err := os.WriteFile(p, []byte(s), 0644); err != nil {
		t.Fatal(err)
	}
}

func TestResolveNight(t *testing.T) {
	c := defaultConfig()
	one := 1
	zero := 0
	// 后两个参数是「屏幕亮度采样结果」：传 false 表示采样无效，走 ext 回落链
	if !resolveNight(c, ExtData{Night: &one}, false, false) {
		t.Fatal("ext night=1 should be night")
	}
	if resolveNight(c, ExtData{Night: &zero}, false, false) {
		t.Fatal("ext night=0 should be day")
	}
	// 仪表采样有效时优先级最高：原厂画面是深底就是夜间，不受车机 ext 影响
	if !resolveNight(c, ExtData{Night: &zero}, true, true) {
		t.Fatal("screen night should win over ext=0")
	}
	if resolveNight(c, ExtData{Night: &one}, false, true) {
		t.Fatal("screen day should win over ext=1")
	}
	c.DayNight = "day"
	if resolveNight(c, ExtData{Night: &one}, false, false) {
		t.Fatal("force day")
	}
	c.DayNight = "night"
	if !resolveNight(c, ExtData{Night: &zero}, false, false) {
		t.Fatal("force night")
	}
}

func TestLayoutNoPanic(t *testing.T) {
	for _, c := range []carRect{carPark, carNormal, carCompact} {
		pos := layoutWheels(c, c == carPark)
		for i, p := range pos {
			if p.fs <= 0 || p.lineLen <= 0 {
				t.Fatalf("wheel %d bad pos %+v", i, p)
			}
		}
	}
}

// TestPaintAllModes 把所有绘制分支都跑一遍，确保不会 panic、脏区不越界
func TestPaintAllModes(t *testing.T) {
	cfg := defaultConfig()
	cfg.Features.Volt = true
	cfg.Features.Cabin = true
	cfg.Features.Debug = true
	cfg.Features.Nav = true

	s := sampleSnapshot()
	empt := snapshot{busOK: true, gear: 4, gearOK: true}

	for _, data := range []snapshot{s, empt} {
		for _, park := range []bool{true, false} {
			for _, night := range []bool{true, false} {
				for _, ta := range []float64{0, 0.37, 1} {
					cv := newCanvas()
					paintFrame(cv, cfg, data, park, ta, night)
					d := cv.dirty.clamp()
					if data.anyBar() && (d.empty() || d.x1 > frameW || d.y1 > frameH) {
						t.Fatalf("dirty out of bounds: %+v", d)
					}
				}
			}
		}
	}
	// 缩放 + 偏移也不能越界
	cfg.Layout = LayoutCfg{DX: 120, DY: -40, Scale: 1.15}
	cv := newCanvas()
	paintFrame(cv, cfg, s, true, 0, false)
	if d := cv.dirty.clamp(); d.x1 > frameW || d.y1 > frameH {
		t.Fatalf("dirty out of bounds with layout: %+v", d)
	}
}

// TestTLFromExt 红绿灯倒计时本地走秒 + 跨相位 + 熄灭
func TestTLFromExt(t *testing.T) {
	// 直行绿灯还剩 20s，下一相位黄 3s
	e := &ExtTL{Ret: 0, DistM: 68, Phases: []ExtTLPhase{
		{Dir: tlDirStraight, Cur: tlGreen, RemainMs: 20000, Nxt: tlYellow, NxtMs: 3000},
	}}

	// 刚收到：20s
	got, ok := tlFromExt(e, 0)
	if !ok {
		t.Fatal("should be lit")
	}
	if l := got.lights[1]; l.cur != tlGreen || l.sec != 20 {
		t.Fatalf("t=0 -> %+v, want green 20", l)
	}
	if l := got.lights[0]; l.cur != 0 {
		t.Fatalf("slot0 should be off, got %+v", l)
	}

	// 过了 5s：剩 15s（向下取整，不能用四舍五入，否则会多走一秒）
	if got, _ := tlFromExt(e, 5*time.Second); got.lights[1].sec != 15 {
		t.Fatalf("t=5s -> %v, want 15", got.lights[1].sec)
	}
	// 剩 15.4s 应显示 15
	e2 := &ExtTL{Phases: []ExtTLPhase{{Dir: tlDirStraight, Cur: tlGreen, RemainMs: 15400}}}
	if got, _ := tlFromExt(e2, 0); got.lights[1].sec != 15 {
		t.Fatalf("floor -> %v, want 15", got.lights[1].sec)
	}

	// 绿灯走完（21s > 20s）：用 nxt 顶上，黄灯还剩 2s，不能先熄灭
	got, ok = tlFromExt(e, 21*time.Second)
	if !ok {
		t.Fatal("should still be lit via nxt")
	}
	if l := got.lights[1]; l.cur != tlYellow || l.sec != 2 {
		t.Fatalf("t=21s -> %+v, want yellow 2", l)
	}

	// 全走完（24s）：熄灭整块
	if _, ok := tlFromExt(e, 24*time.Second); ok {
		t.Fatal("all phases expired -> should be hidden")
	}
	// 没有 nxt 时，cur 一过就熄灭，绝不显示负数
	e3 := &ExtTL{Phases: []ExtTLPhase{{Dir: tlDirStraight, Cur: tlRed, RemainMs: 3000}}}
	if _, ok := tlFromExt(e3, 4*time.Second); ok {
		t.Fatal("no nxt -> should hide after expiry")
	}

	// 无路口 / 无数据
	if _, ok := tlFromExt(nil, 0); ok {
		t.Fatal("nil ext")
	}
	if _, ok := tlFromExt(&ExtTL{Ret: 2, Phases: []ExtTLPhase{{Dir: tlDirStraight, Cur: tlGreen, RemainMs: 9000}}}, 0); ok {
		t.Fatal("ret=2 means no data")
	}
	if _, ok := tlFromExt(&ExtTL{Ret: 0}, 0); ok {
		t.Fatal("empty phases")
	}

	// 双方向：左转红 45s + 直行绿 20s，分别落到 0 / 1 位
	e4 := &ExtTL{Phases: []ExtTLPhase{
		{Dir: tlDirLeft, Cur: tlRed, RemainMs: 45000},
		{Dir: tlDirStraight, Cur: tlGreen, RemainMs: 20000},
	}}
	got, _ = tlFromExt(e4, 0)
	if got.lights[0].cur != tlRed || got.lights[0].sec != 45 {
		t.Fatalf("left -> %+v", got.lights[0])
	}
	if got.lights[1].cur != tlGreen || got.lights[1].sec != 20 {
		t.Fatalf("straight -> %+v", got.lights[1])
	}
	if got.lights[2].cur != 0 {
		t.Fatalf("right should be off: %+v", got.lights[2])
	}
	if got.distM != 0 && got.distM != 68 {
		t.Fatalf("dist=%v", got.distM)
	}

	// 未知方向码忽略，不占位
	e5 := &ExtTL{Phases: []ExtTLPhase{{Dir: 9, Cur: tlRed, RemainMs: 9000}}}
	if _, ok := tlFromExt(e5, 0); ok {
		t.Fatal("unknown dir should be ignored")
	}
}

// TestTLPaint 两种样式都要能画出来，且脏区落在卡片内（含投影的外扩）
func TestTLPaint(t *testing.T) {
	for _, style := range []string{"native", "icon"} {
		cfg := defaultConfig()
		cfg.Features.TL = true
		cfg.Features.TLStyle = style
		s := snapshot{tlOK: true}
		s.tl.lights[0] = tlLight{dir: tlDirLeft, cur: tlRed, sec: 45}
		s.tl.lights[1] = tlLight{dir: tlDirStraight, cur: tlGreen, sec: 20}
		cv := newCanvas()
		paintTrafficLight(cv, cfg, s, false, true, func(x, y float64) (float64, float64) { return x, y })
		d := cv.dirty.clamp()
		if d.empty() {
			t.Fatalf("%s: nothing painted", style)
		}
		// 投影向下偏 2px、向右偏 1px，给 4px 余量
		if d.x0 < tlCardX-4 || d.x1 > tlCardX+tlCardW+4 ||
			d.y0 < tlCardY-4 || d.y1 > tlCardY+tlCardH+4 {
			t.Fatalf("%s: dirty escaped card: %+v", style, d)
		}
		// 纵向要真的用上扩展后的高度（两行），不能被压回原胶囊的 64px
		if h := d.y1 - d.y0; h < tlCardH-2 {
			t.Fatalf("%s: card too short: %.1f", style, h)
		}
	}
}

// TestTLSinglePhase 只有一个相位时：灯居中、卡片尺寸照旧、另一侧不留灰盘
func TestTLSinglePhase(t *testing.T) {
	cfg := defaultConfig()
	cfg.Features.TL = true
	s := snapshot{tlOK: true}
	s.tl.lights[1] = tlLight{dir: tlDirStraight, cur: tlGreen, sec: 20}
	cv := newCanvas()
	paintTrafficLight(cv, cfg, s, false, true, func(x, y float64) (float64, float64) { return x, y })
	d := cv.dirty.clamp()
	if d.empty() {
		t.Fatal("nothing painted")
	}
	// 卡片本身仍按全宽、全高画：单个相位不缩卡片
	if w := d.x1 - d.x0; w < tlCardW-2 {
		t.Fatalf("card shrank: %.1f < %.1f", w, tlCardW)
	}
	if h := d.y1 - d.y0; h < tlCardH-2 {
		t.Fatalf("card too short: %.1f < %.1f", h, tlCardH)
	}
	// 灯盘那一行扫白色箭头：单相位时只应出现在中心盘范围内。
	// （不能用脏区判断灯的位置——卡片底色本身就把整卡标脏了。）
	mid := tlCardX + tlCardW/2
	discR := tlDiscD / 2
	y := int(tlCardY + tlPad + tlDiscD/2)
	lo, hi := 9999, -1
	for x := int(tlCardX); x < int(tlCardX+tlCardW); x++ {
		r, g, b, _ := cv.img.At(x, y).RGBA()
		if r>>8 > 170 && g>>8 > 170 && b>>8 > 170 {
			if x < lo {
				lo = x
			}
			if x > hi {
				hi = x
			}
		}
	}
	if hi < 0 {
		t.Fatal("no arrow painted")
	}
	if float64(lo) < mid-discR || float64(hi) > mid+discR {
		t.Fatalf("arrow not centered: x[%d,%d], mid %.0f", lo, hi, mid)
	}
}

// TestChargeDetect 充电判定：任一条已标定信号命中即算充电；全都读不到按未充电
func TestChargeDetect(t *testing.T) {
	cases := []struct {
		name string
		p    chargeProbe
		want bool
	}{
		{"mcu=2", chargeProbe{mcu: 2, okMcu: true}, true},
		{"gun=2", chargeProbe{gun: 2, okGun: true}, true},
		{"两条都=0", chargeProbe{mcu: 0, gun: 0, okMcu: true, okGun: true}, false},
		{"mcu=15(未充)", chargeProbe{mcu: 15, gun: 0, okMcu: true, okGun: true}, false},
		{"全都读不到", chargeProbe{}, false},
		// 两条状态量都读不到时才用充电电流兜底
		{"无状态量+有电流", chargeProbe{cur: 12.5, okCur: true}, true},
		{"无状态量+无电流", chargeProbe{cur: 0, okCur: true}, false},
		// 状态量能读到时电流不参与，避免行驶中误让出
		{"状态量=0+有电流", chargeProbe{mcu: 0, okMcu: true, cur: 12.5, okCur: true}, false},
	}
	for _, c := range cases {
		if got := c.p.charging(); got != c.want {
			t.Fatalf("%s: charging=%v want %v (%s)", c.name, got, c.want, c.p)
		}
	}
	// String() 读不到的字段要显示 ?，便于日志里一眼看出总线没数据
	if s := (chargeProbe{mcu: 2, okMcu: true}).String(); !strings.Contains(s, "mcu=2") || !strings.Contains(s, "gun=?") {
		t.Fatalf("String()=%q", s)
	}
}

// TestChargeDebounce 去抖：首次直接采信，之后连续 need 次一致才切换
func TestChargeDebounce(t *testing.T) {
	// 开机就在充电：必须立刻让出，不能先画一帧把原厂充电页顶掉
	var d chgDebounce
	if !d.step(true, chargeDebounceN) {
		t.Fatal("首个采样为充电中，应立即采信")
	}

	var e chgDebounce
	if e.step(false, chargeDebounceN) {
		t.Fatal("首个采样未充电")
	}
	if e.step(true, chargeDebounceN) {
		t.Fatal("单次命中不该立刻切换（插枪瞬间会抖）")
	}
	if !e.step(true, chargeDebounceN) {
		t.Fatal("连续 2 次命中应切换为充电中")
	}
	if !e.step(false, chargeDebounceN) {
		t.Fatal("单次未命中不该立刻恢复")
	}
	if e.step(false, chargeDebounceN) {
		t.Fatal("连续 2 次未命中应恢复绘制")
	}

	// 中间夹一次抖动要把计数清零，不能凑满次数
	var f chgDebounce
	f.step(false, chargeDebounceN)
	f.step(true, chargeDebounceN)
	f.step(false, chargeDebounceN)
	if f.step(true, chargeDebounceN) {
		t.Fatal("抖动后计数应清零")
	}
}

// TestReadChargeForce charge.force 调试开关
func TestReadChargeForce(t *testing.T) {
	dir := t.TempDir()
	if got := readChargeForce(dir); got != -1 {
		t.Fatalf("没有文件时应为 -1，got %d", got)
	}
	p := filepath.Join(dir, "charge.force")
	for _, c := range []struct {
		body string
		want int
	}{{"1", 1}, {"0", 0}, {"on\n", 1}, {"off", 0}, {"garbage", -1}} {
		if err := os.WriteFile(p, []byte(c.body), 0644); err != nil {
			t.Fatal(err)
		}
		if got := readChargeForce(dir); got != c.want {
			t.Fatalf("%q -> %d want %d", c.body, got, c.want)
		}
	}
}

// TestChargeYieldConfig 默认开启；旧配置没有该字段时仍是开；显式 false 生效
func TestChargeYieldConfig(t *testing.T) {
	if !defaultConfig().Features.ChargeYield {
		t.Fatal("默认应开启充电让出")
	}
	p := filepath.Join(t.TempDir(), "config.json")
	if err := os.WriteFile(p, []byte(`{"version":1,"enabled":true,"features":{"tpmsP":true}}`), 0644); err != nil {
		t.Fatal(err)
	}
	c, _, err := loadConfig(p)
	if err != nil {
		t.Fatal(err)
	}
	if !c.Features.ChargeYield {
		t.Fatal("旧配置缺 chargeYield 时应保持默认开启")
	}
	if err := os.WriteFile(p, []byte(`{"features":{"chargeYield":false}}`), 0644); err != nil {
		t.Fatal(err)
	}
	c2, _, _ := loadConfig(p)
	if c2.Features.ChargeYield {
		t.Fatal("显式 false 应生效")
	}
}

// TestTLNoPhase 一个相位都没有时不画（数据层的 lit() 已经不返回 ok，这里兜一道）
func TestTLNoPhase(t *testing.T) {
	cfg := defaultConfig()
	s := snapshot{tlOK: true}
	cv := newCanvas()
	paintTrafficLight(cv, cfg, s, false, true, func(x, y float64) (float64, float64) { return x, y })
	if !cv.dirty.clamp().empty() {
		t.Fatal("no phase should paint nothing")
	}
}

// TestTLDayNightPalette 白天必须是浅底深字，夜间仍是最初的深色玻璃卡片。
// 回归点：旧版 paintTrafficLight 没有 night 入参，白天也照画黑底白字。
func TestTLDayNightPalette(t *testing.T) {
	cfg := defaultConfig()
	cfg.Features.TL = true
	s := snapshot{tlOK: true}
	s.tl.lights[0] = tlLight{dir: tlDirLeft, cur: tlRed, sec: 45}
	s.tl.lights[1] = tlLight{dir: tlDirStraight, cur: tlGreen, sec: 20}
	af := func(x, y float64) (float64, float64) { return x, y }

	shot := func(night bool) *canvas {
		cv := newCanvas()
		paintTrafficLight(cv, cfg, s, false, night, af)
		return cv
	}
	lum := func(cv *canvas, x, y int) int {
		r, g, b, _ := cv.img.At(x, y).RGBA()
		return int((r+g+b)/3) >> 8
	}
	day, night := shot(false), shot(true)

	// 卡片顶部内侧：避开描边、内高光、灯盘、秒数，采到的是纯底色
	px, py := int(tlCardX+tlCardW/2), int(tlCardY+6)
	ld, ln := lum(day, px, py), lum(night, px, py)
	if ld < 200 {
		t.Fatalf("白天卡片底仍是暗色：亮度 %d（夜间 %d）", ld, ln)
	}
	if ln > 60 {
		t.Fatalf("夜间卡片底被改亮了：亮度 %d（白天 %d）", ln, ld)
	}

	// 秒数在白天必须换成深色，否则浅底白字等于看不见
	numCY := int(tlCardY + tlPad + tlDiscD + tlNumGap + tlNumFS/2)
	dark := 0
	for y := numCY - 12; y <= numCY+12; y++ {
		for x := int(tlCardX + 8); x < int(tlCardX+tlCardW-8); x++ {
			if lum(day, x, y) < 100 {
				dark++
			}
		}
	}
	if dark < 20 {
		t.Fatalf("白天秒数没换成深色：暗像素仅 %d", dark)
	}
}

// TestUpgradeStable 待升级文件必须连续 upgradeHoldS 秒指纹不变才采纳
func TestUpgradeStable(t *testing.T) {
	t0 := time.Unix(1000, 0)
	sig := "4718754/123"
	// 首次看到：只记录，不采纳
	if upgradeStable(sig, "", t0, t0) {
		t.Fatal("首次看到不该采纳")
	}
	// 稳定期内：不采纳
	if upgradeStable(sig, sig, t0, t0.Add(1*time.Second)) {
		t.Fatal("1 秒时不该采纳")
	}
	// 满 2 秒：采纳
	if !upgradeStable(sig, sig, t0, t0.Add(2*time.Second)) {
		t.Fatal("稳定 2 秒应采纳")
	}
	// 传输中大小又变了（指纹变）：回到观察期，绝不采纳
	if upgradeStable("4718754/999", sig, t0, t0.Add(5*time.Second)) {
		t.Fatal("指纹变化后不该采纳")
	}
	// 空指纹不采纳
	if upgradeStable("", "", t0, t0.Add(9*time.Second)) {
		t.Fatal("空指纹不该采纳")
	}
}

// ---------- 歌词播放态：暂停隐藏 ----------

// canvasNonEmpty 统计画布上非透明像素数，用来判断歌词到底有没有被画出来
func canvasNonEmpty(cv *canvas) int {
	n := 0
	b := cv.img.Bounds()
	for y := b.Min.Y; y < b.Max.Y; y++ {
		for x := b.Min.X; x < b.Max.X; x++ {
			if cv.img.RGBAAt(x, y).A > 0 {
				n++
			}
		}
	}
	return n
}

func TestLyricPausedHidden(t *testing.T) {
	cfg := defaultConfig()
	cfg.Features.Lyric = true
	// 隐藏由 LyricHideOnPause 控制，默认关（暂停后停在最后一句），
	// 见 config.go 中该字段的注释；本用例只验证开关打开后的行为。
	cfg.Features.LyricHideOnPause = true
	s := sampleSnapshot()
	s.lyric = lyricSample()
	s.lyricOK = true

	// 播放中：应该有内容
	s.lyric.playing = true
	cvPlaying := newCanvas()
	paintLyric(cvPlaying, cfg, s, true, nil)
	if canvasNonEmpty(cvPlaying) == 0 {
		t.Fatal("播放中应绘制歌词")
	}

	// 暂停：整块一个像素都不画
	s.lyric.playing = false
	cvPaused := newCanvas()
	paintLyric(cvPaused, cfg, s, true, nil)
	if n := canvasNonEmpty(cvPaused); n != 0 {
		t.Fatalf("暂停时不应绘制歌词，实际非透明像素 %d", n)
	}

	// 「歌词显示在下方」走同一入口，也必须一起隐藏
	cfg.Features.LyricBottom = true
	cvBottom := newCanvas()
	paintLyric(cvBottom, cfg, s, true, nil)
	if n := canvasNonEmpty(cvBottom); n != 0 {
		t.Fatalf("暂停时底部歌词也应隐藏，实际非透明像素 %d", n)
	}
}

// TestLyricPausedKeepsLastLine 覆盖默认行为：开关关时暂停仍停在最后一句。
// 这是 config.go 中 LyricHideOnPause 注释声明的默认语义，必须与实现一致。
func TestLyricPausedKeepsLastLine(t *testing.T) {
	cfg := defaultConfig()
	cfg.Features.Lyric = true
	if cfg.Features.LyricHideOnPause {
		t.Fatal("LyricHideOnPause 默认应为关")
	}
	s := sampleSnapshot()
	s.lyric = lyricSample()
	s.lyricOK = true
	s.lyric.playing = false

	cv := newCanvas()
	paintLyric(cv, cfg, s, true, nil)
	if canvasNonEmpty(cv) == 0 {
		t.Fatal("开关关闭时，暂停后应保留最后一句歌词")
	}
}

func TestExtLyricPlayCompat(t *testing.T) {
	// 新版车机：明确 play:0 = 暂停
	var e ExtData
	if err := json.Unmarshal([]byte(`{"lyric":{"cur":"A","play":0}}`), &e); err != nil {
		t.Fatal(err)
	}
	if e.Lyric == nil || e.Lyric.Play == nil || *e.Lyric.Play != 0 {
		t.Fatal("play:0 应解析成明确的 0")
	}
	if e.Lyric.Play == nil || *e.Lyric.Play != 0 {
		t.Fatal("play:0 不该被当成播放中")
	}

	// 旧版车机 APK 不上报 play：字段为 nil，按播放中处理，歌词不能整块消失
	var old ExtData
	if err := json.Unmarshal([]byte(`{"lyric":{"cur":"A"}}`), &old); err != nil {
		t.Fatal(err)
	}
	if old.Lyric.Play != nil {
		t.Fatal("缺 play 字段应为 nil")
	}
	playing := old.Lyric.Play == nil || *old.Lyric.Play != 0
	if !playing {
		t.Fatal("缺字段时应兼容为播放中")
	}

	// play:1 正常
	var on ExtData
	if err := json.Unmarshal([]byte(`{"lyric":{"cur":"A","play":1}}`), &on); err != nil {
		t.Fatal(err)
	}
	if on.Lyric.Play == nil || *on.Lyric.Play != 1 {
		t.Fatal("play:1 解析失败")
	}
}
