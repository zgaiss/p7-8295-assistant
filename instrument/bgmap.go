package main

// 歌词卡片的底色来源：仪表原厂底图色值表。
//
// 为什么不能用固定色值：原厂那一层不是纯色，是**横向渐变**
//
//	白天 bg_cluster_2: #E7EDF8 → #DFE8F5（亮度 234.1 → 231.0）
//	夜间 bg_cluster_2: #17191C → #090B0F（亮度 22.2 → 10.9）
//
// 平涂一个色值只能对上渐变上的某一个 x：实机测得夜间右边缘台阶 +13.1、
// 白天左边缘 −9.7，卡片就是一块贴上去的矩形，观感割裂。
//
// 这里把 main.kzb 里的四张底图（bg_cluster_{1,2}_{day,night}.png）压成
// 96×36 的色值表（bgmap_data.go，由 tools/gen_bgmap.py 生成），绘制卡片时
// 按屏幕坐标双线性取值。这样卡片底色与原厂底色逐点一致，且是不透明的——
// 不会像半透明方案那样把底下内容透上来叠加。

import (
	"encoding/base64"
	"image/color"
	"math"
	"sync"
)

// bgPix 原厂层采样源（*bgDev 满足此接口，fb.go 只在 linux 编译，
// 这里用接口是为了让 canvas 在测试主机上也能编译）。
// 未接上时为 nil 或返回 ok=false。
type bgPix interface {
	at(x, y int) (uint8, uint8, uint8, bool)
}

// bgGrid 一张底图的降采样色值表，每格 RGB 三字节。
type bgGrid struct {
	w, h int
	rgb  []byte
}

var (
	bgGridOnce sync.Once
	bgGrids    [2][2]*bgGrid
)

// bgVariantDefault：识别不出来时用 cluster_2（当前固件实机在用的那套）。
const bgVariantDefault = 1

func bgNightIdx(night bool) int {
	if night {
		return 1
	}
	return 0
}

// bgGridFor 取指定套系/昼夜的色值表，首次调用时解码。
func bgGridFor(variant int, night bool) *bgGrid {
	if variant < 0 || variant > 1 {
		variant = bgVariantDefault
	}
	ni := bgNightIdx(night)
	bgGridOnce.Do(func() {
		for v := 0; v < 2; v++ {
			for n := 0; n < 2; n++ {
				raw, err := base64.StdEncoding.DecodeString(bgGridData[v][n])
				if err != nil || len(raw) != bgGridW*bgGridH*3 {
					continue
				}
				bgGrids[v][n] = &bgGrid{w: bgGridW, h: bgGridH, rgb: raw}
			}
		}
	})
	return bgGrids[variant][ni]
}

// at 按屏幕坐标双线性取色；坐标超出画面时钳到边缘。
func (g *bgGrid) at(x, y float64) (uint8, uint8, uint8, bool) {
	if g == nil || len(g.rgb) == 0 {
		return 0, 0, 0, false
	}
	fx := x*float64(g.w)/float64(frameW) - 0.5
	fy := y*float64(g.h)/float64(frameH) - 0.5
	if fx < 0 {
		fx = 0
	} else if fx > float64(g.w-1) {
		fx = float64(g.w - 1)
	}
	if fy < 0 {
		fy = 0
	} else if fy > float64(g.h-1) {
		fy = float64(g.h - 1)
	}
	x0 := int(fx)
	y0 := int(fy)
	tx := fx - float64(x0)
	ty := fy - float64(y0)
	x1, y1 := x0+1, y0+1
	if x1 > g.w-1 {
		x1 = g.w - 1
	}
	if y1 > g.h-1 {
		y1 = g.h - 1
	}
	o00 := (y0*g.w + x0) * 3
	o10 := (y0*g.w + x1) * 3
	o01 := (y1*g.w + x0) * 3
	o11 := (y1*g.w + x1) * 3
	var out [3]uint8
	for c := 0; c < 3; c++ {
		a := float64(g.rgb[o00+c])*(1-tx) + float64(g.rgb[o10+c])*tx
		b := float64(g.rgb[o01+c])*(1-tx) + float64(g.rgb[o11+c])*tx
		out[c] = uint8(a*(1-ty) + b*ty + 0.5)
	}
	return out[0], out[1], out[2], true
}

func absInt(v int) int {
	if v < 0 {
		return -v
	}
	return v
}

// bgVariantPick 判断现场用的是哪一套底图。
//
// 做法：取卡片左右两侧（我们没画任何东西的地方）的原厂像素，和两套色值表
// 的预测值比对。单点可能落在原厂文字/图标上，所以丢掉误差最大的一个点，
// 用剩下的求和——比直接求和抗干扰。
// 识别不出来（含 bg 不可用）时返回 bgVariantDefault。
func bgVariantPick(bg bgPix, x0, x1, ymid int, night bool) int {
	if bg == nil {
		return bgVariantDefault
	}
	best, bestErr := bgVariantDefault, 1<<30
	for v := 0; v < 2; v++ {
		g := bgGridFor(v, night)
		if g == nil {
			continue
		}
		errs := make([]int, 0, 4)
		for _, px := range [4]int{x0 - 36, x0 - 14, x1 + 14, x1 + 36} {
			if px < 0 || px >= frameW || ymid < 0 || ymid >= frameH {
				continue
			}
			br, bgc, bb, ok := bg.at(px, ymid)
			if !ok {
				continue
			}
			gr, gg, gb, ok2 := g.at(float64(px)+0.5, float64(ymid)+0.5)
			if !ok2 {
				continue
			}
			errs = append(errs, absInt(int(br)-int(gr))+absInt(int(bgc)-int(gg))+absInt(int(bb)-int(gb)))
		}
		if len(errs) == 0 {
			continue
		}
		worst, sum := -1, 0
		for _, e := range errs {
			sum += e
			if e > worst {
				worst = e
			}
		}
		if len(errs) > 1 {
			sum -= worst // 丢掉误差最大的点
		}
		if sum < bestErr {
			bestErr, best = sum, v
		}
	}
	return best
}

// fillRoundRectBgAA 用底图色值表填充圆角矩形（不透明），边缘按 SDF 算覆盖率。
// 返回 false 表示拿不到色值表，调用方应退回固定色。
func (c *canvas) fillRoundRectBgAA(x0, y0, x1, y1, r float64, g *bgGrid) bool {
	if g == nil || x1 <= x0 || y1 <= y0 {
		return false
	}
	ix0, iy0 := int(math.Floor(x0-1)), int(math.Floor(y0-1))
	ix1, iy1 := int(math.Ceil(x1+1)), int(math.Ceil(y1+1))
	b := c.img.Bounds()
	ix0 = maxInt(ix0, b.Min.X)
	iy0 = maxInt(iy0, b.Min.Y)
	ix1 = minInt(ix1, b.Max.X-1)
	iy1 = minInt(iy1, b.Max.Y-1)
	if ix1 < ix0 || iy1 < iy0 {
		return false
	}
	// 横向的插值位置与行无关，先算好，内层循环只剩两次线性插值
	n := ix1 - ix0 + 1
	col := make([]color.RGBA, n)
	gw := float64(g.w) / float64(frameW)
	gh := float64(g.h) / float64(frameH)
	for i := 0; i < n; i++ {
		col[i].A = 0
	}
	for y := iy0; y <= iy1; y++ {
		// 先按行算好这一行的纵向混合权重
		fy := (float64(y)+0.5)*gh - 0.5
		if fy < 0 {
			fy = 0
		} else if fy > float64(g.h-1) {
			fy = float64(g.h - 1)
		}
		gy0 := int(fy)
		ty := fy - float64(gy0)
		gy1 := gy0 + 1
		if gy1 > g.h-1 {
			gy1 = g.h - 1
		}
		for i := 0; i < n; i++ {
			fx := (float64(ix0+i)+0.5)*gw - 0.5
			if fx < 0 {
				fx = 0
			} else if fx > float64(g.w-1) {
				fx = float64(g.w - 1)
			}
			gx0 := int(fx)
			tx := fx - float64(gx0)
			gx1 := gx0 + 1
			if gx1 > g.w-1 {
				gx1 = g.w - 1
			}
			o00 := (gy0*g.w + gx0) * 3
			o10 := (gy0*g.w + gx1) * 3
			o01 := (gy1*g.w + gx0) * 3
			o11 := (gy1*g.w + gx1) * 3
			for c := 0; c < 3; c++ {
				a := float64(g.rgb[o00+c])*(1-tx) + float64(g.rgb[o10+c])*tx
				bb := float64(g.rgb[o01+c])*(1-tx) + float64(g.rgb[o11+c])*tx
				v := a*(1-ty) + bb*ty
				switch c {
				case 0:
					col[i].R = uint8(v + 0.5)
				case 1:
					col[i].G = uint8(v + 0.5)
				default:
					col[i].B = uint8(v + 0.5)
				}
			}
			col[i].A = 0xFF
		}
		for x := ix0; x <= ix1; x++ {
			d := rrSDF(float64(x)+0.5, float64(y)+0.5, x0, y0, x1, y1, r)
			cov := 0.5 - d
			if cov <= 0 {
				continue
			}
			if cov > 1 {
				cov = 1
			}
			px := col[x-ix0]
			// 内部像素走抖动：把"落在两个 565 码值之间"的那部分按 Bayer 比例
			// 拆成两种像素，空间平均回到原色值。否则 5bit 的 8 级步长会在平缓
			// 渐变上留下一道道色阶带（夜间底图只有 11 级振幅，正好跨一道台阶）。
			// 边缘像素（cov<1）保持平滑的原色值，交给抗锯齿混合，不抖动。
			if cov >= 0.999 {
				px = dither565(px, x, y)
			}
			c.blendPx(x, y, px, int(cov*255))
		}
	}
	c.dirty.add(x0-1, y0-1, x1+1, y1+1)
	return true
}

func maxInt(a, b int) int {	if a > b {
		return a
	}
	return b
}
func minInt(a, b int) int {
	if a < b {
		return a
	}
	return b
}

// fillLyricBg 用原厂底图给歌词卡片上色（不透明）。
// 返回 false 表示色值表不可用，调用方应退回固定色平涂。
func (c *canvas) fillLyricBg(x0, y0, x1, y1, r float64, night bool) bool {
	v := bgVariantPick(c.bg, int(x0), int(x1), int((y0+y1)/2), night)
	return c.fillRoundRectBgAA(x0, y0, x1, y1, r, bgGridFor(v, night))
}
