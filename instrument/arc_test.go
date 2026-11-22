package main

import (
	"image"
	"image/color"
	"testing"
)

// 盲区弧必须和字体/圆角卡片一样是抗锯齿的：
// 旧实现用 24 段折线 + thickLine(fillRect)，边缘只有"有/无"两级，
// 5px 宽的弧在 1920×720 上台阶非常明显。
func TestStrokeArcAA(t *testing.T) {
	const W, H = 200, 120
	img := image.NewRGBA(image.Rect(0, 0, W, H))
	c := &canvas{img: img}
	red := color.RGBA{0xE5, 0x30, 0x30, 0xFF}

	// 一左一右，和 paintBSD 的调用完全一致（右弧 rx 取负做镜像）
	c.strokeArc(50, 60, 35, 23, 85, 225, 5, red)
	c.strokeArc(150, 60, -35, 23, 85, 225, 5, red)

	// 边缘必须出现大量部分覆盖像素（0 < alpha < 255）
	var partialL, partialR, opaqueL, opaqueR int
	for y := 0; y < H; y++ {
		for x := 0; x < W; x++ {
			a := int(img.Pix[img.PixOffset(x, y)+3])
			if a == 0 {
				continue
			}
			if x < W/2 {
				if a < 255 {
					partialL++
				} else {
					opaqueL++
				}
			} else {
				if a < 255 {
					partialR++
				} else {
					opaqueR++
				}
			}
		}
	}
	if partialL < 40 || partialR < 40 {
		t.Fatalf("抗锯齿灰阶不足：左 %d / 右 %d 个半透明像素", partialL, partialR)
	}
	if opaqueL < 100 || opaqueR < 100 {
		t.Fatalf("弧带内部未实心填充：左 %d / 右 %d 个不透明像素", opaqueL, opaqueR)
	}
	// rx 取负的镜像弧：两侧覆盖面积应基本一致（端点裁剪符号错了会差很多）
	ratio := float64(partialL) / float64(partialR)
	if ratio < 0.8 || ratio > 1.25 {
		t.Fatalf("左右弧抗锯齿不对称：左 %d / 右 %d", partialL, partialR)
	}
	t.Logf("左 %d 实心 + %d 边缘灰阶；右 %d 实心 + %d 边缘灰阶", opaqueL, partialL, opaqueR, partialR)
}
