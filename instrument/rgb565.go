package main

import "image/color"

// fb1 是 16bpp RGB565，写进去的颜色只有 5/6 bit。这一层决定了"看起来颜色对不对"，
// 两件事必须在打包时处理掉，否则大面积的卡片底色会露馅：
//
// 1) 取码要用最近邻，不能截断。
//    显示端把码值展开回 8bit 用的是 MSB 复制（code<<3|code>>2，抓帧回读 shot.go 同理）。
//    若用 v>>3 截断，等于一路向下取整，且 R/B 丢 3bit、G 只丢 2bit：误差单向偏负、
//    三通道幅度还不等。实机实测歌词卡片对上原厂底图时截断得 R−7 G0 B−3 ——
//    夜间整块发暗、白天两翼发绿。换最近邻后最大误差 7→4、逐通道平均偏差≈0。
//
// 2) 平缓渐变要做空间抖动，不能只有两个码值。
//    原厂底色是横向渐变，夜间只有 11 级振幅，而 5bit 的 R/B 步长就是 8 级：
//    整条渐变只会落到两个相邻码值上，交界处是一道 8 级台阶（实机看到的就是"波纹"）。
//    这里用 8x8 Bayer 阈值，把"落在两个码值之间"的那部分按比例拆成两种像素，
//    空间平均回到原色值。全等于某个码值的平坦区域残差为 0，不抖动、不出颗粒。

var code5, code6 [256]uint16
var exp5 [32]uint8
var exp6 [64]uint8

const (
	step5 = 255.0 / 31 // 5bit 码值展开后的平均步长
	step6 = 255.0 / 63
)

func init() {
	for c := 0; c < 32; c++ {
		exp5[c] = uint8((c << 3) | (c >> 2))
	}
	for c := 0; c < 64; c++ {
		exp6[c] = uint8((c << 2) | (c >> 4))
	}
	for v := 0; v < 256; v++ {
		b5, b6 := 0, 0
		d5, d6 := 1<<30, 1<<30
		for c := 0; c < 32; c++ {
			if d := absDiff(int(exp5[c]), v); d < d5 {
				d5, b5 = d, c
			}
		}
		for c := 0; c < 64; c++ {
			if d := absDiff(int(exp6[c]), v); d < d6 {
				d6, b6 = d, c
			}
		}
		code5[v] = uint16(b5)
		code6[v] = uint16(b6)
	}
}

func absDiff(a, b int) int {
	if a > b {
		return a - b
	}
	return b - a
}

// pack565 8bit RGB → RGB565，取最近邻码（不是截断）。
func pack565(r, g, b uint8) uint16 {
	return code5[r]<<11 | code6[g]<<5 | code5[b]
}

// bayer8 是 8x8 有序抖动阈值矩阵（0..63）。
var bayer8 = [64]uint8{
	0, 32, 8, 40, 2, 34, 10, 42,
	48, 16, 56, 24, 50, 18, 58, 26,
	12, 44, 4, 36, 14, 46, 6, 38,
	60, 28, 52, 20, 62, 30, 54, 22,
	3, 35, 11, 43, 1, 33, 9, 41,
	51, 19, 59, 27, 49, 17, 57, 25,
	15, 47, 7, 39, 13, 45, 5, 37,
	63, 31, 55, 23, 61, 29, 53, 21,
}

func bayerT(x, y int) float64 {
	return (float64(bayer8[(y&7)*8+(x&7)]) + 0.5) / 64.0
}

// dither565 按 Bayer 阈值把 8bit 颜色拆到相邻两个 565 码值上。
// 返回值本身已经是"展开后的 8bit"，因此随后用 pack565 打包必然还原成同一个码值，
// 屏幕上相邻像素的混合结果就是原色值。
func dither565(c color.RGBA, x, y int) color.RGBA {
	t := bayerT(x, y)
	c.R = ditherChan(c.R, code5[:], exp5[:], step5, t)
	c.G = ditherChan(c.G, code6[:], exp6[:], step6, t)
	c.B = ditherChan(c.B, code5[:], exp5[:], step5, t)
	return c
}

func ditherChan(v uint8, code []uint16, exp []uint8, step, t float64) uint8 {
	ci := int(code[v])
	base := exp[ci]
	res := float64(v) - float64(base)
	if res == 0 {
		return base
	}
	// res/s ∈ (0,0.5]：向相邻码值偏移的比例，正好是"该用另一个码值的像素占比"
	p := res / step
	if p < 0 {
		p = -p
	}
	if t >= p {
		return base
	}
	if res > 0 {
		if ci+1 < len(exp) {
			return exp[ci+1]
		}
		return base
	}
	if ci > 0 {
		return exp[ci-1]
	}
	return base
}
