package main

// 原厂弹窗避让（仪表侧）
//
// 车机从 HAL 日志推断「仪表左/右当前是否有原厂弹窗」，随 ext.json 推来：
//
//	"popup":{"L":1,"R":0,"why":"key","msL":1800,"msR":0}
//
// 关键设计：**只传剩余毫秒，不传绝对时刻**。仪表 RTC 停在 2019、两边时钟差 8 小时，
// 任何跨进程的时间戳比对都会错（红绿灯读秒已经踩过同一个坑，见 config.go 的 ExtTLPhase 注释）。
// 这里用 ext.json 的 mtime 当观测时刻，把剩余毫秒换算成本地截止时刻，之后纯本地倒计时：
//
//   - 车机停推 / adb 掉线 / APK 被杀 → 到点自动解除，卡片自己回来（fail-open）
//   - 车机侧窗口只延长不缩短（同一集合并），所以这里的截止时刻只会被推后
//   - 不需要额外的 TTL 机制，也不受 ext.json 那个 30s/120s 大 TTL 牵连

import "time"

// popupMaxHoldMs 单个窗口的防御上限：车机推来个离谱的 msL 也不能让卡片永久消失。
// 实车最长窗口是 ACC 车速/车距 3.2s + 保持 0.9s ≈ 4.1s，10s 已经是两倍余量。
const popupMaxHoldMs int64 = 10000

// popupGate 左右两侧弹窗的本地截止时刻（unix 毫秒）。0 或已过期 = 该侧没有弹窗。
type popupGate struct {
	lUntilMs int64
	rUntilMs int64
	why      string
}

// leftAt 指定时刻左侧是否有弹窗。
func (g popupGate) leftAt(nowMs int64) bool {
	return g.lUntilMs > 0 && nowMs < g.lUntilMs
}

// rightAt 指定时刻右侧是否有弹窗。
func (g popupGate) rightAt(nowMs int64) bool {
	return g.rUntilMs > 0 && nowMs < g.rUntilMs
}

// activeAt 任意一侧是否有弹窗。
func (g popupGate) activeAt(nowMs int64) bool {
	return g.leftAt(nowMs) || g.rightAt(nowMs)
}

// msLeft 该侧剩余毫秒（诊断用，已过期返回 0）。
func (g popupGate) msLeft(nowMs int64) int64 {
	if g.lUntilMs <= nowMs {
		return 0
	}
	return g.lUntilMs - nowMs
}

// msRight 该侧剩余毫秒（诊断用）。
func (g popupGate) msRight(nowMs int64) int64 {
	if g.rUntilMs <= nowMs {
		return 0
	}
	return g.rUntilMs - nowMs
}

// popupFromExt 把 ext.json 里的 popup 字段折成本地截止时刻。
//
// age 是 ext.json 的 mtime 到现在的时长（链路延迟：FTP 写盘 + 100ms 轮询）。用
// now-age 当观测时刻，剩余毫秒就不会被这段延迟白吃——弹窗窗口只有 2s，200ms 的误差也值得扣掉。
func popupFromExt(p *ExtPopup, age time.Duration, nowMs int64) popupGate {
	if p == nil {
		return popupGate{}
	}
	obs := nowMs - age.Milliseconds()
	clamp := func(ms int64) int64 {
		if ms <= 0 {
			return 0
		}
		if ms > popupMaxHoldMs {
			return popupMaxHoldMs
		}
		return ms
	}
	var g popupGate
	if p.L > 0 {
		if ms := clamp(p.MsL); ms > 0 {
			g.lUntilMs = obs + ms
		}
	}
	if p.R > 0 {
		if ms := clamp(p.MsR); ms > 0 {
			g.rUntilMs = obs + ms
		}
	}
	g.why = p.Why
	return g
}

// lyricOnLeft 歌词块是否位于屏幕左半边（按拖动位置 + 缩放后的实际中心算）。
//
// 弹窗避让必须跟着歌词实际位置走：用户把歌词拖到右边，右侧弹窗就该收它。
// 「歌词显示在下方」时是底部居中，不参与避让（见 paintFrame），这里返回 false 无意义也无人用。
func lyricOnLeft(cfg Config) bool {
	centerX := cfg.LyricPos.DX + lyricBoxW*cfg.LyricPos.Scale/2
	return centerX < frameW/2
}
