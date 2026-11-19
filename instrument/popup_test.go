package main

import (
	"encoding/json"
	"testing"
	"time"
)

// TestPopupFromExt 剩余毫秒 → 本地截止时刻：必须扣掉链路延迟，不能白吃 200ms
func TestPopupFromExt(t *testing.T) {
	now := int64(100000)
	p := &ExtPopup{L: 1, R: 0, Why: "key", MsL: 1800}
	g := popupFromExt(p, 100*time.Millisecond, now)

	// 观测时刻 = now - age = 99900；截止 = 99900 + 1800 = 101700
	if g.lUntilMs != 101700 {
		t.Fatalf("lUntil=%d want 101700", g.lUntilMs)
	}
	if !g.leftAt(now) {
		t.Fatal("左侧应处于弹窗中")
	}
	if g.leftAt(101700) {
		t.Fatal("到点必须解除（fail-open）")
	}
	if g.rightAt(now) {
		t.Fatal("右侧没有弹窗")
	}
	if g.msLeft(now) != 1700 {
		t.Fatalf("msLeft=%d want 1700", g.msLeft(now))
	}
	if g.why != "key" {
		t.Fatalf("why=%q want key", g.why)
	}
}

// TestPopupFromExtDefensive 车机推来离谱/残缺数据时不能把卡片永久藏掉
func TestPopupFromExtDefensive(t *testing.T) {
	now := int64(1000)
	// 字段缺失 = 没有弹窗
	if g := popupFromExt(nil, 0, now); g.activeAt(now) {
		t.Fatal("nil popup 应为无弹窗")
	}
	// 只有 L 没有剩余毫秒：视为无效，不猜
	if g := popupFromExt(&ExtPopup{L: 1, MsL: 0}, 0, now); g.leftAt(now) {
		t.Fatal("msL=0 不应触发隐藏")
	}
	// 离谱的大值必须被 clamp 到 10s，不能变成永久隐藏
	g := popupFromExt(&ExtPopup{R: 1, MsR: 9999999}, 0, now)
	if g.msRight(now) != popupMaxHoldMs {
		t.Fatalf("msRight=%d want %d", g.msRight(now), popupMaxHoldMs)
	}
	// 负数同样无效
	if g := popupFromExt(&ExtPopup{L: 1, MsL: -500}, 0, now); g.leftAt(now) {
		t.Fatal("负数剩余时间不应触发隐藏")
	}
}

// TestPopupGateExpiry 弹窗自然结束：时间一走，两侧都自己回来
func TestPopupGateExpiry(t *testing.T) {
	now := int64(200000)
	g := popupFromExt(&ExtPopup{L: 1, R: 1, MsL: 2200, MsR: 3200}, 0, now)
	if !g.activeAt(now) {
		t.Fatal("两侧都应有弹窗")
	}
	if g.leftAt(now + 2201) {
		t.Fatal("左侧 2.2s 后应解除")
	}
	if !g.rightAt(now + 2201) {
		t.Fatal("右侧 3.2s 内应仍然有效")
	}
	if g.activeAt(now + 3201) {
		t.Fatal("两侧都过期后必须全部解除")
	}
	// 归零后诊断值也要是 0，不能留负数
	if g.msLeft(now+9000) != 0 || g.msRight(now+9000) != 0 {
		t.Fatal("过期后剩余毫秒应为 0")
	}
}

// TestLyricOnLeft 歌词卡按实际拖动位置判侧（含缩放）
func TestLyricOnLeft(t *testing.T) {
	cfg := defaultConfig()
	if !lyricOnLeft(cfg) {
		t.Fatal("默认位置（左上角）应在左半边")
	}
	cfg.LyricPos = LyricPosCfg{DX: 1200, DY: 0, Scale: 1}
	if lyricOnLeft(cfg) {
		t.Fatal("拖到右半边后应判为右侧")
	}
	// 缩放会改中心点：1280 宽的包围盒放大后中心更靠右
	cfg.LyricPos = LyricPosCfg{DX: 700, DY: 0, Scale: 1.8}
	if lyricOnLeft(cfg) {
		t.Fatalf("DX=700 scale=1.8 中心=%.0f 应判为右侧", cfg.LyricPos.DX+lyricBoxW*cfg.LyricPos.Scale/2)
	}
}

// TestPopupHideSwitchDefault 开关语义：字段缺失按开（旧 APK 写的 config.json 没有这个键）
func TestPopupHideSwitchDefault(t *testing.T) {
	var c Config
	if err := json.Unmarshal([]byte(`{"version":1}`), &c); err != nil {
		t.Fatal(err)
	}
	c.normalize()
	if !c.Features.popupHide() {
		t.Fatal("config.json 缺 popupHide 字段时应默认开启")
	}
	c2 := defaultConfig()
	if !c2.Features.popupHide() {
		t.Fatal("默认配置应开启弹窗避让")
	}
	c2.Features.PopupHide = boolPtr(false)
	if c2.Features.popupHide() {
		t.Fatal("显式 false 必须关掉")
	}
	if c2.mask()&2097152 != 0 {
		t.Fatal("关掉后 mask 不应带 2097152 位")
	}
}

// TestPaintPopupGate 门控落点：左侧弹窗只收歌词卡、右侧弹窗只收导航卡，且能自动恢复
func TestPaintPopupGate(t *testing.T) {
	// 只留一张卡，避免其它组件的像素干扰断言
	lyricCfg := defaultConfig()
	lyricCfg.Features = FeatureCfg{Lyric: true, PopupHide: boolPtr(true)}
	s := sampleSnapshot()
	s.lyric = lyricSample()
	s.lyricOK = true
	s.lyric.playing = true
	s.pop = popupGate{}

	base := newCanvas()
	paintFrame(base, lyricCfg, s, true, 0, false)
	if canvasNonEmpty(base) == 0 {
		t.Fatal("无弹窗时歌词应正常绘制")
	}

	now := time.Now().UnixMilli()
	s.pop = popupGate{lUntilMs: now + 2000}
	cvLeft := newCanvas()
	paintFrame(cvLeft, lyricCfg, s, true, 0, false)
	if n := canvasNonEmpty(cvLeft); n != 0 {
		t.Fatalf("左侧弹窗时必须收起歌词卡，实际非透明像素 %d", n)
	}

	// 右侧弹窗不影响左上角的歌词
	s.pop = popupGate{rUntilMs: now + 2000}
	cvRight := newCanvas()
	paintFrame(cvRight, lyricCfg, s, true, 0, false)
	if canvasNonEmpty(cvRight) == 0 {
		t.Fatal("右侧弹窗不应收起左侧歌词卡")
	}

	// 开关关掉：即使有弹窗也不隐藏
	offCfg := lyricCfg
	offCfg.Features = FeatureCfg{Lyric: true, PopupHide: boolPtr(false)}
	s.pop = popupGate{lUntilMs: now + 2000}
	cvOff := newCanvas()
	paintFrame(cvOff, offCfg, s, true, 0, false)
	if canvasNonEmpty(cvOff) == 0 {
		t.Fatal("关掉弹窗避让后不应隐藏")
	}

	// 导航卡：右侧弹窗收起，左侧弹窗保留
	navCfg := defaultConfig()
	navCfg.Features = FeatureCfg{Nav: true, PopupHide: boolPtr(true)}
	s2 := sampleSnapshot()
	s2.navOK = true

	s2.pop = popupGate{}
	cvNavBase := newCanvas()
	paintFrame(cvNavBase, navCfg, s2, true, 0, false)
	if canvasNonEmpty(cvNavBase) == 0 {
		t.Fatal("无弹窗时导航卡应正常绘制")
	}

	s2.pop = popupGate{rUntilMs: now + 2000}
	cvNavRight := newCanvas()
	paintFrame(cvNavRight, navCfg, s2, true, 0, false)
	if n := canvasNonEmpty(cvNavRight); n != 0 {
		t.Fatalf("右侧弹窗时必须收起导航卡，实际非透明像素 %d", n)
	}

	s2.pop = popupGate{lUntilMs: now + 2000}
	cvNavLeft := newCanvas()
	paintFrame(cvNavLeft, navCfg, s2, true, 0, false)
	if canvasNonEmpty(cvNavLeft) == 0 {
		t.Fatal("左侧弹窗不应收起右侧导航卡")
	}
}

// TestExtPopupDecode ext.json 里的 popup 字段要能按车机推来的键名解出来
func TestExtPopupDecode(t *testing.T) {
	var e ExtData
	raw := `{"ts":1,"popup":{"L":1,"R":0,"why":"door","msL":1500,"msR":0}}`
	if err := json.Unmarshal([]byte(raw), &e); err != nil {
		t.Fatal(err)
	}
	if e.Popup == nil {
		t.Fatal("popup 字段未解出")
	}
	if e.Popup.L != 1 || e.Popup.MsL != 1500 || e.Popup.Why != "door" {
		t.Fatalf("解出内容不对: %+v", *e.Popup)
	}
	g := popupFromExt(e.Popup, 0, 1000)
	if !g.leftAt(1000) {
		t.Fatal("应判为左侧弹窗")
	}
}
