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
//	车机在投 + 新鲜 → 车机的帧
//	车机断开/没帧   → nil（不画充电页，还原原厂画面）
//
// 2026-11-19 按用户要求去掉"内置帧包自播"兜底：车机断了就老老实实回到原厂仪表，
// 不再用 charge.bin 播一个卡顿的替代动画（那会让用户以为仪表还在工作）。
func frameFor() (image.Image, bool) {
	chgFrame.mu.Lock()
	fresh := chgFrame.ok && chgFrame.img != nil && time.Since(chgFrame.readAt) < chgFrameStaleMS*time.Millisecond
	img := chgFrame.img
	chgFrame.mu.Unlock()
	if fresh {
		return img, false
	}
	return nil, false
}

// frameSig 当前帧标识（用于"画面没变就不重绘"的判断）。
// 车机投帧时用 mtime；没有新鲜帧时返回 0，调用方据此不接管屏幕。
func frameSig() uint64 {
	chgFrame.mu.Lock()
	defer chgFrame.mu.Unlock()
	if chgFrame.ok && chgFrame.img != nil && time.Since(chgFrame.readAt) < chgFrameStaleMS*time.Millisecond {
		return chgFrame.sig
	}
	return 0
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
//	screen = 正在用"车机投过来的帧"（车机连接正常）
//	vplay  = 本地播放器接管
//	none   = 没有可用帧，充电页不接管（原厂画面）
//
// ageMs 是最近一次收到/解码帧距今的毫秒数（vplay/none 时为 -1），
// kb 是帧文件大小（vplay/none 时为 0）。
func chgFrameStatus() (string, int, int) {
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
	return "none", -1, 0
}
