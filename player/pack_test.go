package main

// PC 上校验 P7LZ 帧包：解析 → LZ4 解压 → 2 倍复制。
// 目的很具体：在把播放器推到仪表之前，先确认「解出来的像素顺序/颜色」是对的，
// 否则上屏就是花屏，而仪表那头排查成本极高。

import (
	"image"
	"image/color"
	"image/png"
	"os"
	"path/filepath"
	"testing"

	"github.com/pierrec/lz4/v4"
)

func TestPackDecode(t *testing.T) {
	const src = "../dist/charge_lz4.pack"
	if _, err := os.Stat(src); err != nil {
		t.Skip("帧包不存在，跳过")
	}
	p, err := loadPack(src)
	if err != nil {
		t.Fatalf("loadPack: %v", err)
	}
	if p.count != 193 || p.w != 960 || p.h != 360 {
		t.Fatalf("头部不符: %d 帧 %dx%d", p.count, p.w, p.h)
	}
	if p.rawLen != 960*360*2 {
		t.Fatalf("rawLen=%d", p.rawLen)
	}

	raw := make([]byte, p.rawLen)
	out := make([]byte, 1920*720*2)
	dir := t.TempDir()

	for _, idx := range []int{0, 96, 192} {
		s := p.data[p.off[idx] : p.off[idx]+p.clen[idx]]
		n, err := lz4.UncompressBlock(s, raw)
		if err != nil {
			t.Fatalf("frame %d 解压失败: %v", idx, err)
		}
		if n != p.rawLen {
			t.Fatalf("frame %d 长度 %d != %d", idx, n, p.rawLen)
		}

		// 565 → RGB 存图，肉眼核对构图与颜色
		img := image.NewRGBA(image.Rect(0, 0, p.w, p.h))
		for y := 0; y < p.h; y++ {
			for x := 0; x < p.w; x++ {
				i := (y*p.w + x) * 2
				v := uint16(raw[i]) | uint16(raw[i+1])<<8
				r := uint8((v>>11)&31) << 3
				g := uint8((v>>5)&63) << 2
				b := uint8(v&31) << 3
				img.Set(x, y, color.RGBA{r, g, b, 255})
			}
		}
		f, err := os.Create(filepath.Join(dir, "lz4_frame_"+itoa(idx)+".png"))
		if err != nil {
			t.Fatal(err)
		}
		if err := png.Encode(f, img); err != nil {
			t.Fatal(err)
		}
		f.Close()

		// 2 倍复制：抽四角与中心，确认放大是整数复制、没有错位
		render2x(raw, out, p.w, p.h, 1920)
		checks := [][2]int{{0, 0}, {1919, 0}, {0, 719}, {1919, 719}, {960, 360}, {959, 359}}
		for _, c := range checks {
			ox, oy := c[0], c[1]
			oi := (oy*1920 + ox) * 2
			si := ((oy / 2) * p.w + ox/2) * 2
			if out[oi] != raw[si] || out[oi+1] != raw[si+1] {
				t.Fatalf("2 倍复制错位 at (%d,%d)", ox, oy)
			}
		}
	}
}

func itoa(v int) string {
	if v == 0 {
		return "0"
	}
	var b []byte
	for v > 0 {
		b = append([]byte{byte('0' + v%10)}, b...)
		v /= 10
	}
	return string(b)
}
