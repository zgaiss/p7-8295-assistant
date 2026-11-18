package main

// 充电动画帧的"投屏"接收端。
//
// 架构（2026-11-18 调整）：
//   · 车机连得上仪表时，由车机负责渲染动画，把每一帧写成 JPEG 推到
//     /data/p7assist/charge.frm（先 .tmp 再 rename，避免读到半截）。仪表只负责
//     解码 + 上屏，不再自己算动画——仪表这颗 CPU 每帧做全屏重绘要 400ms 以上，
//     自己播会一直卡。
//   · 车机断开（超过 2.5 秒没有新帧）→ 回落到内置静态图（charge.bin 的第 0 帧），
//     只画一次、之后不再重绘，保证画面不会卡死。
//   · 静态图也没有（charge.bin 缺失）→ 视为不可用，整块不画，原厂充电页露出来。
//
// 文件约定：charge.frm 是**单帧 JPEG**（不是 charge.bin 的帧包格式）。

import (
	"bytes"
	"image"
	"image/jpeg"
	"os"
	"sync"
	"time"
)

const chgFrameStaleMS = 2500 // 超过这个时间没有新帧就算车机断开

type chgFrameState struct {
	mu       sync.Mutex
	path     string
	lastMod  time.Time
	lastSize int64
	sig      uint64
	img      image.Image
	ok       bool
	readAt   time.Time
}

var chgFrame chgFrameState

// pollChgFrame 检查一次车机推到仪表的帧；有更新就解码并缓存。
// 由渲染循环每个 tick 调用（只做一次 stat，开销很小）。
func pollChgFrame(dir string) {
	chgFrame.mu.Lock()
	defer chgFrame.mu.Unlock()
	if chgFrame.path == "" {
		chgFrame.path = dir + "/charge.frm"
	}
	st, err := os.Stat(chgFrame.path)
	if err != nil || st.Size() < 1024 {
		chgFrame.ok = false
		return
	}
	if st.ModTime().Equal(chgFrame.lastMod) && st.Size() == chgFrame.lastSize {
		// 文件没变化：不能刷新 readAt。
		// 之前这里把 readAt 设成当前时间，结果文件再旧也算"新鲜"，
		// 车机断开后永远不会回落到自播 —— 充电动画会停在一帧上不动。
		return
	}
	raw, err := os.ReadFile(chgFrame.path)
	if err != nil || len(raw) < 1024 {
		chgFrame.ok = false
		return
	}
	img, err := jpeg.Decode(bytes.NewReader(raw))
	if err != nil {
		chgFrame.ok = false
		return
	}
	chgFrame.lastMod = st.ModTime()
	chgFrame.lastSize = st.Size()
	chgFrame.img = img
	chgFrame.readAt = time.Now()
	chgFrame.ok = true
	// 签名用 mtime 纳秒，帧一变就变，渲染循环据此判断要不要重绘
	chgFrame.sig = uint64(st.ModTime().UnixNano())
}

// frameFor 取当前应该显示的帧：
//
//	车机在投 +  新鲜 → 车机的帧
//	车机断开          → 内置静态图（charge.bin 第 0 帧）
//	都没有            → nil（调用方不画充电页，还原原厂画面）
func frameFor() (image.Image, bool) {
	chgFrame.mu.Lock()
	fresh := chgFrame.ok && chgFrame.img != nil && time.Since(chgFrame.readAt) < chgFrameStaleMS*time.Millisecond
	img := chgFrame.img
	chgFrame.mu.Unlock()
	if fresh {
		return img, false // false = 不是静态兜底
	}
	// 车机断开：用内置帧包自己播动画（车主接受"卡一点"，但要动）。
	// 帧率由 charge.bin 头的 fps 决定（现在是 3fps / 25 帧 / 8 秒一轮）。
	if chgAnim.idxNow() >= 0 {
		if st := chgAnim.current(); st != nil {
			return st, true
		}
	}
	if st := chgAnim.frame(0); st != nil {
		return st, true
	}
	return nil, true
}

// frameSig 当前帧标识（用于"画面没变就不重绘"的判断）。
// 车机投帧时用 mtime；静态兜底用固定值——这样静态图只画一次。
func frameSig() uint64 {
	chgFrame.mu.Lock()
	defer chgFrame.mu.Unlock()
	if chgFrame.ok && chgFrame.img != nil && time.Since(chgFrame.readAt) < chgFrameStaleMS*time.Millisecond {
		return chgFrame.sig
	}
	// 仪表自播兜底：用帧号做签名，换帧就触发重绘（不换帧就完全不动，省 CPU）
	return 0x10000000 | uint64(chgAnim.idxNow())
}

// chargeBgReady 有没有背景资源可用（没有就整块不画，把屏幕让回原厂）
func chargeBgReady() bool {
	if img, _ := frameFor(); img != nil {
		return true
	}
	return false
}

// chgFrameStatus 给 status.json 用的投屏状态：
//
//	screen   = 正在用"车机投过来的帧"（车机连接正常）
//	selfplay = 车机断开，仪表自己在播内置帧包
//	none     = 两样都没有，充电页不会接管（原厂画面）
//
// ageMs 是最近一次收到/解码帧距今的毫秒数（selfplay/none 时为 -1），
// kb 是帧文件大小（自播时为 0）。
func chgFrameStatus() (string, int, int) {
	// 本地播放器接管时明确报 vplay：之前这里继续报 selfplay（因为 charge.bin 还在），
	// 从车机看就像"仪表在自己播兜底帧包"，容易误判成两套动画在抢屏。
	if videoRunning() {
		return "vplay", -1, 0
	}
	chgFrame.mu.Lock()
	ok := chgFrame.ok && chgFrame.img != nil
	age := int(time.Since(chgFrame.readAt).Milliseconds())
	size := int(chgFrame.lastSize / 1024)
	chgFrame.mu.Unlock()
	if ok && age < chgFrameStaleMS {
		return "screen", age, size
	}
	if chgAnim.count() > 0 {
		return "selfplay", -1, 0
	}
	return "none", -1, 0
}
