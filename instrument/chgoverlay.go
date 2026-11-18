package main

// 充电页文字层（给 p7videoplay 的 --overlay 用）。
//
// 播放器约定：读一张灰度 JPEG，把每个像素的亮度当 alpha（<12 跳过），
// 叠加在画面左上角，最大生效区域 760×540。
//
// 所以这里把充电数据画到画布的左上角（坐标与整屏自绘完全一致：标题 110、
// 公里 229、标签 312、数值 382、底行 442），全在 540 以内，直接喂给播放器即可。
// 黑底 → alpha 0（透出动画），白字 → alpha 255。

import (
	"bytes"
	"image"
	"image/jpeg"
	"os"
	"path/filepath"
)

const (
	overlayW = 760
	overlayH = 540
)

var overlayCV *canvas

// writeChargeOverlay 生成 /data/p7assist/chgoverlay.jpg（原子替换）
func writeChargeOverlay(dir string, cfg Config, s snapshot, tAnim float64) error {
	if overlayCV == nil {
		overlayCV = newCanvas()
	}
	cv := overlayCV
	cv.clearAll()
	// 画布清成"不透明黑"：播放器按亮度取 alpha，黑 = 透明底
	pix := cv.img.Pix
	for i := 3; i < len(pix); i += 4 {
		pix[i] = 255
	}

	st, ch := chargeView(cfg, s, tAnim)
	// 左对齐 + 宽度收到 700：播放器的文字层只有左上 760×540 生效
	paintChargeWarnsBox(cv, ch.warns, true, 700)
	paintChargeBlock(cv, st, ch, cfg.Features.ChargeDebug, chargeVyOff)

	// RGBA → 灰度（只取播放器真正会用到的左上角区域）
	g := image.NewGray(image.Rect(0, 0, overlayW, overlayH))
	src := cv.img
	for y := 0; y < overlayH; y++ {
		so := y * src.Stride
		do := y * overlayW
		for x := 0; x < overlayW; x++ {
			p := src.Pix[so+x*4:]
			// 0.299R + 0.587G + 0.114B，定点整数
			g.Pix[do+x] = uint8((299*int(p[0]) + 587*int(p[1]) + 114*int(p[2])) / 1000)
		}
	}

	var buf bytes.Buffer
	if err := jpeg.Encode(&buf, g, &jpeg.Options{Quality: 88}); err != nil {
		return err
	}
	dst := filepath.Join(dir, videoOvName)
	tmp := dst + ".tmp"
	if err := os.WriteFile(tmp, buf.Bytes(), 0644); err != nil {
		return err
	}
	return os.Rename(tmp, dst)
}
