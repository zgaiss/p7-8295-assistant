//go:build linux

package main

// 按需抓帧：把「原厂画面 fb0 + 前景层 fb1」按色键规则合成成一张 PNG。
//
// 用途：仪表上没法截屏，PC 又连不到仪表的屏幕通路，所以由守护进程自己抓。
// 触发方式：往 appDir 里放一个空的 shot.req 文件，下一帧就会写出 shot.png
// 并删掉 shot.req。整个过程不影响绘制节奏（一张 1920x720 的合成约几十毫秒）。
//
// 合成规则与硬件一致：fb1 的像素值 0 视为透明，透出 fb0；其它值直接覆盖。

import (
	"encoding/binary"
	"image"
	"image/color"
	"image/png"
	"os"
)

// captureShot 合成一帧并写成 PNG。fb 为当前前景层，bg 为原厂层。
// onlyBg=true 时只导出原厂画面（做预览底图用，不含我们画的内容）。
func captureShot(fb *fbDev, out string, onlyBg bool) error {
	if fb == nil || fb.mem == nil {
		return os.ErrInvalid
	}
	w, h := fb.w, fb.h
	img := image.NewRGBA(image.Rect(0, 0, w, h))

	for y := 0; y < h; y++ {
		row := y * fb.lineLen
		for x := 0; x < w; x++ {
			if !onlyBg {
				i := row + x*2
				if i+2 > len(fb.mem) {
					continue
				}
				if v := binary.LittleEndian.Uint16(fb.mem[i:]); v != 0 {
					// RGB565 → RGB888
					r5 := uint8(v>>11) & 0x1F
					g6 := uint8(v>>5) & 0x3F
					b5 := uint8(v) & 0x1F
					img.SetRGBA(x, y, color.RGBA{r5<<3 | r5>>2, g6<<2 | g6>>4, b5<<3 | b5>>2, 0xFF})
					continue
				}
			}
			// 色键 / 纯背景模式：取原厂画面
			if fb.bg == nil {
				continue
			}
			br, bgc, bb, ok := fb.bg.at(x, y)
			if !ok {
				continue
			}
			img.SetRGBA(x, y, color.RGBA{br, bgc, bb, 0xFF})
		}
	}

	tmp := out + ".tmp"
	f, err := os.Create(tmp)
	if err != nil {
		return err
	}
	if err := png.Encode(f, img); err != nil {
		f.Close()
		os.Remove(tmp)
		return err
	}
	f.Close()
	return os.Rename(tmp, out)
}
