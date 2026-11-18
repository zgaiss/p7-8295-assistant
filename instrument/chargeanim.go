package main

// 充电动画背景：从 /data/p7assist/charge.bin 循环播放 JPEG 帧序列。
//
// 文件格式（小端）：
//   "P7CG" | count(4) | w(4) | h(4) | fps(4) | repeat{ len(4) + jpeg }
//
// 121 帧 1470×310 约 5.2MB，整包读进内存（仪表内存宽裕），逐帧按需解码并缓存 1 帧。
// 1470×310 的 JPEG 在 ARM 上解码约 5~10ms，15fps 下 CPU 占用很低。

import (
	"bytes"
	"encoding/binary"
	"image"
	"image/jpeg"
	"os"
	"sync"
	"time"
)

type chargeAnim struct {
	mu       sync.Mutex
	loaded   bool
	w, h     int
	fps      int
	offsets  []int32
	sizes    []int32
	data     []byte
	cacheIdx int
	cacheImg image.Image
	start    time.Time
}

var chgAnim chargeAnim

// loadChargeAnim 载入帧包；失败时静默返回，上层回落到渐变背景
func loadChargeAnim(path string) bool {
	raw, err := os.ReadFile(path)
	if err != nil || len(raw) < 24 {
		return false
	}
	if !bytes.Equal(raw[0:4], []byte("P7CG")) {
		return false
	}
	n := int(binary.LittleEndian.Uint32(raw[4:8]))
	w := int(binary.LittleEndian.Uint32(raw[8:12]))
	h := int(binary.LittleEndian.Uint32(raw[12:16]))
	fps := int(binary.LittleEndian.Uint32(raw[16:20]))
	if n <= 0 || n > 2000 || w <= 0 || h <= 0 {
		return false
	}
	a := &chgAnim
	a.mu.Lock()
	defer a.mu.Unlock()
	a.w, a.h, a.fps = w, h, fps
	if a.fps <= 0 {
		a.fps = 15
	}
	a.offsets = make([]int32, 0, n)
	a.sizes = make([]int32, 0, n)
	off := 20
	for i := 0; i < n; i++ {
		if off+4 > len(raw) {
			return false
		}
		sz := int(binary.LittleEndian.Uint32(raw[off : off+4]))
		off += 4
		if sz <= 0 || off+sz > len(raw) {
			return false
		}
		a.offsets = append(a.offsets, int32(off))
		a.sizes = append(a.sizes, int32(sz))
		off += sz
	}
	a.data = raw
	a.loaded = true
	a.start = time.Now()
	a.cacheIdx = -1
	a.cacheImg = nil
	return true
}

// frame 取第 idx 帧（循环取模，带 1 帧缓存避免同一帧重复解码）
func (a *chargeAnim) frame(idx int) image.Image {
	a.mu.Lock()
	defer a.mu.Unlock()
	if !a.loaded || len(a.offsets) == 0 {
		return nil
	}
	idx = ((idx % len(a.offsets)) + len(a.offsets)) % len(a.offsets)
	if idx == a.cacheIdx && a.cacheImg != nil {
		return a.cacheImg
	}
	s := int(a.offsets[idx])
	e := s + int(a.sizes[idx])
	img, err := jpeg.Decode(bytes.NewReader(a.data[s:e]))
	if err != nil {
		return a.cacheImg
	}
	a.cacheIdx = idx
	a.cacheImg = img
	return img
}

// idxNow 按启动以来的时间算当前帧号（供画面签名用）
func (a *chargeAnim) idxNow() int {
	a.mu.Lock()
	loaded, fps, start, n := a.loaded, a.fps, a.start, len(a.offsets)
	a.mu.Unlock()
	if !loaded || n == 0 {
		return 0
	}
	if fps <= 0 {
		fps = 15
	}
	idx := int(time.Since(start).Seconds() * float64(fps))
	return ((idx % n) + n) % n
}

// current 按启动以来的时间算当前应显示的帧
func (a *chargeAnim) current() image.Image {
	a.mu.Lock()
	loaded, fps, start := a.loaded, a.fps, a.start
	a.mu.Unlock()
	if !loaded {
		return nil
	}
	if fps <= 0 {
		fps = 15
	}
	return a.frame(int(time.Since(start).Seconds() * float64(fps)))
}

// count 已载入的帧数（0 = 未载入）
func (a *chargeAnim) count() int {
	a.mu.Lock()
	defer a.mu.Unlock()
	if !a.loaded {
		return 0
	}
	return len(a.offsets)
}

// size 原始帧尺寸
func (a *chargeAnim) size() (int, int, bool) {
	a.mu.Lock()
	defer a.mu.Unlock()
	return a.w, a.h, a.loaded
}
