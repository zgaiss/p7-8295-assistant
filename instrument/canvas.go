package main

// 画布与字体：整帧 RGBA（1920x720 绝对坐标，透明背景 = 硬件色键）
// 字体分两套：Lato（数字/拉丁，还原原厂观感）+ 普惠体子集（中文标签）
// 每次绘制都会把覆盖区域累加到 dirty，守护进程只把这块钱刷上屏。

import (
	_ "embed"
	"fmt"
	"image"
	"image/color"
	"image/draw"
	"math"
	"os"
	"path/filepath"

	xdraw "golang.org/x/image/draw"
	"golang.org/x/image/font"
	"golang.org/x/image/font/opentype"
	"golang.org/x/image/math/fixed"
)

//go:embed fonts/Lato-Bold.ttf
var latoBold []byte

//go:embed fonts/Lato-Regular.ttf
var latoReg []byte

//go:embed fonts/cjk.ttf
var cjkTTF []byte

type fontKind int

const (
	fLatoBold fontKind = iota
	fLatoReg
	fCJK
	fCJKFull // 完整中文字体（运行时从 fonts/ 目录加载，用于歌词等动态中文）
)

type canvas struct {
	img   *image.RGBA
	dirty rect
	// ox/oy 是临时绘制偏移，只给 paintLyric 左上角歌词块用（拖动位置）。
	// 默认 0，其它组件无感知；paintLyric 进入时设置、defer 清零。
	ox, oy float64
	// bg 是原厂层（fb0）只读采样源，只用于辨认现场用的是哪一套底图
	// （见 bgmap.go 的 bgVariantPick）。为 nil 时按默认套系取色。
	bg bgPix
}

type rect struct{ x0, y0, x1, y1 float64 }

func (r *rect) add(x0, y0, x1, y1 float64) {
	if x0 > x1 {
		x0, x1 = x1, x0
	}
	if y0 > y1 {
		y0, y1 = y1, y0
	}
	if r.x1 <= r.x0 && r.y1 <= r.y0 { // 空
		r.x0, r.y0, r.x1, r.y1 = x0, y0, x1, y1
		return
	}
	r.x0 = math.Min(r.x0, x0)
	r.y0 = math.Min(r.y0, y0)
	r.x1 = math.Max(r.x1, x1)
	r.y1 = math.Max(r.y1, y1)
}

func (r rect) empty() bool { return r.x1 <= r.x0 || r.y1 <= r.y0 }

func (r rect) union(o rect) rect {
	if r.empty() {
		return o
	}
	if o.empty() {
		return r
	}
	return rect{math.Min(r.x0, o.x0), math.Min(r.y0, o.y0), math.Max(r.x1, o.x1), math.Max(r.y1, o.y1)}
}

// clamp 裁到屏幕内，并按偶数对齐（RGB565 两字节一个像素，避免半像素错位）
func (r rect) clamp() rect {
	x0 := math.Floor(r.x0)
	y0 := math.Floor(r.y0)
	x1 := math.Ceil(r.x1)
	y1 := math.Ceil(r.y1)
	x0 = math.Max(0, x0)
	y0 = math.Max(0, y0)
	x1 = math.Min(frameW, x1)
	y1 = math.Min(frameH, y1)
	if x1 <= x0 || y1 <= y0 {
		return rect{}
	}
	ix0 := int(x0) &^ 1
	ix1 := int(x1)
	if ix1&1 == 1 {
		ix1++
	}
	if ix1 > frameW {
		ix1 = frameW
	}
	return rect{float64(ix0), y0, float64(ix1), y1}
}

func newCanvas() *canvas {
	return &canvas{img: image.NewRGBA(image.Rect(0, 0, frameW, frameH))}
}

// clearRect 只清上一帧画过的区域
// fillRowBuf 填充用的行缓冲（复用，避免每帧分配）
var fillRowBuf []byte

func (c *canvas) clearRect(r rect) {
	r = r.clamp()
	if r.empty() {
		return
	}
	// 用整行拷贝把区域清零：draw.Draw 的通用路径逐像素走接口，
	// 全屏一次要 200ms+（实测），这里 memmove 只要几毫秒。
	p := image.Rect(int(r.x0), int(r.y0), int(r.x1), int(r.y1))
	w := p.Dx() * 4
	if w <= 0 {
		return
	}
	if cap(fillRowBuf) < w {
		fillRowBuf = make([]byte, w)
	}
	row := fillRowBuf[:w]
	for i := range row {
		row[i] = 0
	}
	c.fillRows(p, row)
}

// fillRows 把一行像素模式铺到矩形区域（按行 memmove）
func (c *canvas) fillRows(p image.Rectangle, row []byte) {
	w := len(row)
	stride := c.img.Stride
	base := c.img.Rect.Min
	for y := p.Min.Y; y < p.Max.Y; y++ {
		off := (y-base.Y)*stride + (p.Min.X-base.X)*4
		if off < 0 || off+w > len(c.img.Pix) {
			continue
		}
		copy(c.img.Pix[off:off+w], row)
	}
}

func (c *canvas) clearAll() {
	p := c.img.Pix
	for i := range p {
		p[i] = 0
	}
	c.dirty = rect{}
}

// ---------- 字体 ----------

type faceKey struct {
	kind fontKind
	px2  int // 0.5px 量化
	// 变体：加粗（仅 CJK 需要，用描边模拟）
	bold bool
}

var (
	parsed = map[fontKind]*opentype.Font{}
	faces  = map[faceKey]font.Face{}
)

func parseFont(k fontKind) *opentype.Font {
	if f, ok := parsed[k]; ok {
		return f
	}
	var b []byte
	switch k {
	case fLatoBold:
		b = latoBold
	case fLatoReg:
		b = latoReg
	case fCJKFull:
		// 完整中文字体：运行时从 <appDir>/fonts/Alibaba-PuHuiTi-Regular.ttf 读取，
		// 用 os.ReadFile 而非 embed，避免把 8.6MB 字体塞进二进制、也避免内存常驻。
		// 注意：文件名沿用 Regular，实际部署的是 Medium 字重（小字号更扎实、不发虚）。
		b = loadCJKFull()
	case fCJK:
		// 小字号标签（电瓶/车内/里程/平均…）与歌词统一走完整字体。
		// 原来的 embed 子集只有十几个字的 Regular，22px 下笔画发虚，是"字很粗糙"的主因。
		// 字体缺失时 loadCJKFull 会回退 cjkTTF，行为仍然安全。
		return parseFont(fCJKFull)
	default:
		b = cjkTTF
	}
	f, err := opentype.Parse(b)
	if err != nil {
		panic(fmt.Sprintf("parse font %d: %v", k, err))
	}
	parsed[k] = f
	return f
}

// loadCJKFull 读完整中文字体；失败则回退到子集 cjk.ttf（此时歌词里的生僻字会显示为缺字）
func loadCJKFull() []byte {
	p := filepath.Join(appDir(), "fonts", "Alibaba-PuHuiTi-Regular.ttf")
	if b, err := os.ReadFile(p); err == nil && len(b) > 100000 {
		return b
	}
	return cjkTTF
}

func face(k fontKind, px float64) font.Face {
	key := faceKey{kind: k, px2: int(math.Round(px * 2))}
	if f, ok := faces[key]; ok {
		return f
	}
	f, err := opentype.NewFace(parseFont(k), &opentype.FaceOptions{
		Size: float64(key.px2) / 2, DPI: 72, Hinting: font.HintingFull,
	})
	if err != nil {
		panic(err)
	}
	faces[key] = f
	return f
}

func isCJK(r rune) bool {
	return r >= 0x2E80 || r == '℃' || r == '·'
}

// splitRuns 把字符串切成"拉丁段 / 中文段"，分别用不同字体绘制
func splitRuns(s string) []struct {
	text string
	kind fontKind
} {
	var out []struct {
		text string
		kind fontKind
	}
	var cur []rune
	var curCJK bool
	flush := func() {
		if len(cur) == 0 {
			return
		}
		k := fLatoReg
		if curCJK {
			k = fCJK
		}
		out = append(out, struct {
			text string
			kind fontKind
		}{string(cur), k})
		cur = cur[:0]
	}
	for _, r := range s {
		c := isCJK(r)
		if len(cur) > 0 && c != curCJK {
			flush()
		}
		curCJK = c
		cur = append(cur, r)
	}
	flush()
	return out
}

// measure 宽度（px）
func measureWith(s string, px float64, bold bool) float64 {
	var w float64
	for _, run := range splitRuns(s) {
		w += float64(font.MeasureString(faceFor(run.kind, px, bold), run.text)) / 64
	}
	return w
}

func measure(s string, px float64) float64 {
	return measureWith(s, px, false)
}

// faceFor 选字体：加粗的拉丁文直接用内置的 Lato-Bold（真字重，边缘干净）；
// 中文没有粗体子集，仍靠 drawText 里的"双绘"模拟。
func faceFor(k fontKind, px float64, bold bool) font.Face {
	if bold && k != fCJK {
		k = fLatoBold
	}
	return face(k, px)
}

// ascentOf 返回字体的 ascent（px，正数）
func ascentOf(k fontKind, px float64) float64 {
	m := face(k, px).Metrics()
	return float64(m.Ascent) / 64
}

func descentOf(k fontKind, px float64) float64 {
	m := face(k, px).Metrics()
	return float64(m.Descent) / 64
}

// drawRunAA 画一段同字体文本，返回宽度。
// 小字号（≤30px）单倍 AA 只有 1px 灰边、视觉发虚；这里用 2 倍超采样再缩回，
// 边缘灰阶翻倍，观感更接近车机原厂（Skia/FreeType）。大字号直接用单倍，省 CPU。
type maskKey struct {
	text string
	kind fontKind
	px2  int
	bold bool
}

// 文字 mask 缓存：仪表上大部分文字（车内/里程/平均…）内容不变，
// 每帧重画字形再缩放纯属浪费。缓存后每帧只做一次 alpha 合成。
var maskCache = map[maskKey]*image.Alpha{}

func (c *canvas) drawRunAA(run string, kind fontKind, px, x, baseline float64, col color.RGBA, bold bool) float64 {
	if run == "" {
		return 0
	}
	f1 := faceFor(kind, px, bold)
	w := float64(font.MeasureString(f1, run)) / 64
	if px > 30 {
		d := &font.Drawer{Dst: c.img, Src: image.NewUniform(col), Face: f1}
		d.Dot = fixed.Point26_6{X: fixed.I(int(math.Round(x))), Y: fixed.I(int(math.Round(baseline)))}
		d.DrawString(run)
		return w
	}
	f2 := faceFor(kind, px*2, bold)
	m := f2.Metrics()
	asc := float64(m.Ascent) / 64
	const pad = 4 // 2x 坐标下的边距，缩回 1x 正好 2px，取整不会偏
	key := maskKey{run, kind, int(math.Round(px * 2)), bold}
	mask1, ok := maskCache[key]
	if !ok {
		desc := float64(m.Descent) / 64
		mw := int(math.Ceil(w*2)) + pad*2
		mh := int(math.Ceil(asc+desc)) + pad*2 // asc/desc 已是 2x 像素，不要再乘 2
		mask2 := image.NewAlpha(image.Rect(0, 0, mw, mh))
		d2 := &font.Drawer{Dst: mask2, Src: image.NewUniform(color.Alpha{A: 255}), Face: f2}
		d2.Dot = fixed.P(pad, pad+int(math.Round(asc)))
		d2.DrawString(run)
		mask1 = image.NewAlpha(image.Rect(0, 0, mw/2, mh/2))
		xdraw.ApproxBiLinear.Scale(mask1, mask1.Bounds(), mask2, mask2.Bounds(), xdraw.Src, nil)
		if len(maskCache) > 800 { // 歌词不断变化，别让缓存无限涨
			maskCache = map[maskKey]*image.Alpha{}
		}
		maskCache[key] = mask1
	}
	// mask2 里基线在 pad+asc（2x 坐标），缩回 1x 后基线在 (pad+asc)/2；
	// 要让它在屏幕上正好落在 baseline，左上角必须回退 (pad+asc)/2。
	left := int(math.Round(x - float64(pad)/2))
	top := int(math.Round(baseline - (asc+float64(pad))/2))
	dr := image.Rect(left, top, left+mask1.Bounds().Dx(), top+mask1.Bounds().Dy())
	xdraw.DrawMask(c.img, dr, image.NewUniform(col), image.Point{}, mask1, image.Point{}, xdraw.Over)
	return w
}

// drawText 在 (x, baseline) 画字符串；align: 1=左对齐（x 为左边界）, 0=居中, -1=右对齐
func (c *canvas) drawText(s string, x, baseline, px float64, col color.RGBA, align int, bold bool) float64 {
	x += c.ox
	baseline += c.oy
	w := measureWith(s, px, bold)
	start := x
	switch align {
	case -1:
		start = x - w
	case 0:
		start = x - w/2
	}
	cur := start
	for _, run := range splitRuns(s) {
		rw := c.drawRunAA(run.text, run.kind, px, cur, baseline, col, bold)
		if bold && run.kind == fCJK {
			// 中文没有粗体字重，轻微横向错开再画一遍模拟加粗
			c.drawRunAA(run.text, run.kind, px, cur+px*0.03, baseline, col, bold)
		}
		cur += rw
	}
	c.dirty.add(start, baseline-px*1.25, start+w, baseline+px*0.35)
	return start + w
}

func round(v float64) int { return int(math.Round(v)) }

// drawLyricText 用完整中文字体画歌词（fCJKFull），支持中文/拉丁混排。
// 返回实际宽度；maxW>0 时超宽自动缩字号，保证不溢出（居中需调用方自行调整起点）。
func (c *canvas) drawLyricText(s string, x, baseline, px float64, col color.RGBA, align int, maxW float64) float64 {
	if s == "" {
		return 0
	}
	x += c.ox
	baseline += c.oy
	f := face(fCJKFull, px)
	w := float64(font.MeasureString(f, s)) / 64
	scale := 1.0
	if maxW > 0 && w > maxW {
		scale = maxW / w
		if scale < 0.5 {
			scale = 0.5
		}
		f = face(fCJKFull, px*scale)
		w = float64(font.MeasureString(f, s)) / 64
	}
	start := x
	switch align {
	case -1:
		start = x - w
	case 0:
		start = x - w/2
	}
	// 小字号歌词自动走 2x 超采样，大字号单倍（drawRunAA 内部按 px 判断）
	c.drawRunAA(s, fCJKFull, px*scale, start, baseline, col, false)
	c.dirty.add(start, baseline-px*1.3, start+w, baseline+px*0.4)
	return start + w
}

// wrapLyric 把歌词按 maxW 折成多行，严格保证每行宽度 ≤ maxW。
// 断行：优先整词换行（英文单词不切断）；单个词本身超宽时按字符硬切。
func wrapLyric(s string, px float64, maxW float64) []string {
	if s == "" {
		return nil
	}
	f := face(fCJKFull, px)
	mw := func(rs []rune) float64 {
		return float64(font.MeasureString(f, string(rs))) / 64
	}
	spaceW := mw([]rune{' '})

	var lines []string
	// 先按空格/换行切成"词"（词 = 连续非空白字符）
	type tok struct {
		text []rune
		nl   bool // 换行标记
	}
	var toks []tok
	runes := []rune(s)
	var wd []rune
	emit := func() {
		if len(wd) > 0 {
			toks = append(toks, tok{text: wd})
			wd = nil
		}
	}
	for _, r := range runes {
		if r == '\n' {
			emit()
			toks = append(toks, tok{nl: true})
		} else if r == ' ' {
			emit()
		} else {
			wd = append(wd, r)
		}
	}
	emit()

	var cur []rune
	curW := 0.0
	flush := func() {
		if len(cur) > 0 {
			lines = append(lines, string(cur))
			cur = cur[:0]
			curW = 0
		}
	}
	for _, tk := range toks {
		if tk.nl {
			flush()
			continue
		}
		wlen := mw(tk.text)
		addWord := func() {
			// 行内已有内容则补空格
			if len(cur) > 0 {
				cur = append(cur, ' ')
				curW += spaceW
			}
			// 词本身超宽 → 按字符硬切
			if wlen > maxW {
				for _, ch := range tk.text {
					cw := mw([]rune{ch})
					if curW+cw > maxW && len(cur) > 0 {
						flush()
					}
					cur = append(cur, ch)
					curW += cw
				}
			} else {
				cur = append(cur, tk.text...)
				curW += wlen
			}
		}
		needSpace := 0.0
		if len(cur) > 0 {
			needSpace = spaceW
		}
		if curW+needSpace+wlen <= maxW {
			addWord()
		} else {
			flush()
			addWord()
		}
	}
	flush()
	return lines
}

// drawLyricWrapped 在限定宽度 maxW 内折行绘制歌词，返回实际绘制的行数。
// 每行行高 lineH；align: 1=左对齐，0=居中，-1=右对齐；maxLines>0 时最多画这么多行（超出截断）。
func (c *canvas) drawLyricWrapped(s string, x, baseline, px, lineH, maxW float64, col color.RGBA, align int, maxLines int) int {
	lines := wrapLyric(s, px, maxW)
	if len(lines) == 0 {
		return 0
	}
	if maxLines > 0 && len(lines) > maxLines {
		lines = lines[:maxLines]
	}
	// 临时绘制偏移（左上角歌词块拖动位置）：drawRunAA 本身不带偏移，
	// 而 drawLyricText 已经在入口加过一次，所以这里只能加在本函数入口，
	// 否则「拖动位置」对换行后的歌词完全不生效。
	x += c.ox
	baseline += c.oy
	f := face(fCJKFull, px)
	for i, ln := range lines {
		w := float64(font.MeasureString(f, ln)) / 64
		start := x
		switch align {
		case -1:
			start = x - w
		case 0:
			start = x - w/2
		}
		c.drawRunAA(ln, fCJKFull, px, start, baseline+float64(i)*lineH, col, false)
		c.dirty.add(start, baseline+float64(i)*lineH-px*1.3, start+w, baseline+float64(i)*lineH+px*0.4)
	}
	return len(lines)
}

// drawBottom 按"文字底部"对齐（等价原厂 drawAtBottom），返回右边界
func (c *canvas) drawBottom(s string, x, bottom, px float64, col color.RGBA, align int, bold bool) float64 {
	k := fLatoReg
	for _, r := range s {
		if isCJK(r) {
			k = fCJK
			break
		}
	}
	return c.drawText(s, x, bottom-descentOf(k, px), px, col, align, bold)
}

// hline 1px 横线
func (c *canvas) hline(x0, x1, y float64, col color.RGBA) {
	if x0 > x1 {
		x0, x1 = x1, x0
	}
	r := image.Rect(round(x0), round(y), round(x1), round(y)+1).Intersect(c.img.Bounds())
	draw.Draw(c.img, r, image.NewUniform(col), image.Point{}, draw.Src)
	c.dirty.add(x0, y, x1, y+1)
}

// premul 把非预乘色转成画布要求的预乘 RGBA。
// image/draw 在 src 是 color.RGBA 时会**原样**拷贝四个字节，不做预乘换算，
// 所以半透明色必须自己先乘好 alpha，否则 R>A 会溢出、合成出黑块。
func premul(c color.RGBA) color.RGBA {
	if c.A == 0xFF {
		return c
	}
	a := int(c.A)
	return color.RGBA{
		uint8(int(c.R) * a / 255),
		uint8(int(c.G) * a / 255),
		uint8(int(c.B) * a / 255),
		c.A,
	}
}

// fillRect 实心矩形
func (c *canvas) fillRect(x0, y0, x1, y1 float64, col color.RGBA) {
	col = premul(col)
	r := image.Rect(round(x0), round(y0), round(x1), round(y1)).Intersect(c.img.Bounds())
	c.dirty.add(x0, y0, x1, y1)
	if r.Empty() {
		return
	}
	// 先造一行像素，再整行 memmove。原来用 draw.Draw + image.Uniform 会走
	// 通用路径（逐像素接口调用），全屏填充实测 288ms，把充电动画拖到 2fps。
	w := r.Dx() * 4
	if cap(fillRowBuf) < w {
		fillRowBuf = make([]byte, w)
	}
	row := fillRowBuf[:w]
	for i := 0; i < w; i += 4 {
		row[i] = col.R
		row[i+1] = col.G
		row[i+2] = col.B
		row[i+3] = col.A
	}
	c.fillRows(r, row)
}

// fillRoundRect 圆角矩形填充
func (c *canvas) fillRoundRect(x0, y0, x1, y1, rad float64, col color.RGBA) {
	x0, y0, x1, y1 = x0+c.ox, y0+c.oy, x1+c.ox, y1+c.oy
	if rad <= 0 {
		c.fillRect(x0, y0, x1, y1, col)
		return
	}
	col = premul(col)
	for y := int(math.Floor(y0)); y < int(math.Ceil(y1)); y++ {
		cy := float64(y) + 0.5
		inset := 0.0
		switch {
		case cy < y0+rad:
			dy := y0 + rad - cy
			inset = rad - math.Sqrt(math.Max(0, rad*rad-dy*dy))
		case cy > y1-rad:
			dy := cy - (y1 - rad)
			inset = rad - math.Sqrt(math.Max(0, rad*rad-dy*dy))
		}
		xa := x0 + inset
		xb := x1 - inset
		if xb <= xa {
			continue
		}
		r := image.Rect(round(xa), y, round(xb), y+1).Intersect(c.img.Bounds())
		draw.Draw(c.img, r, image.NewUniform(col), image.Point{}, draw.Src)
	}
	c.dirty.add(x0, y0, x1, y1)
}

// strokeRoundRect 圆角矩形描边
func (c *canvas) strokeRoundRect(x0, y0, x1, y1, rad, w float64, col color.RGBA) {
	x0, y0, x1, y1 = x0+c.ox, y0+c.oy, x1+c.ox, y1+c.oy
	steps := 12
	// 四条边
	c.fillRect(x0+rad, y0, x1-rad, y0+w, col)
	c.fillRect(x0+rad, y1-w, x1-rad, y1, col)
	c.fillRect(x0, y0+rad, x0+w, y1-rad, col)
	c.fillRect(x1-w, y0+rad, x1, y1-rad, col)
	// 四个角：用小段折线近似
	arc := func(cx, cy, a0, a1 float64) {
		px, py := 0.0, 0.0
		for i := 0; i <= steps; i++ {
			a := (a0 + (a1-a0)*float64(i)/float64(steps)) * math.Pi / 180
			x := cx + rad*math.Cos(a)
			y := cy + rad*math.Sin(a)
			if i > 0 {
				c.thickLine(px, py, x, y, w, col)
			}
			px, py = x, y
		}
	}
	arc(x0+rad, y0+rad, 180, 270)
	arc(x1-rad, y0+rad, 270, 360)
	arc(x0+rad, y1-rad, 90, 180)
	arc(x1-rad, y1-rad, 0, 90)
}

// thickLine 粗线段（横竖优先，斜线用小方块铺）
func (c *canvas) thickLine(x0, y0, x1, y1, w float64, col color.RGBA) {
	dx, dy := x1-x0, y1-y0
	n := int(math.Max(math.Abs(dx), math.Abs(dy)))
	if n <= 0 {
		c.fillRect(x0-w/2, y0-w/2, x0+w/2, y0+w/2, col)
		return
	}
	for i := 0; i <= n; i++ {
		t := float64(i) / float64(n)
		x := x0 + dx*t
		y := y0 + dy*t
		c.fillRect(x-w/2, y-w/2, x+w/2, y+w/2, col)
	}
}

// fillGradientBox 横向渐变蒙版（左 a0 浓 → 右 a1 淡），用于让歌词自然融进背景。
// 直接写预乘 RGBA，alpha=0 的像素保持透明（色键）。
func (c *canvas) fillGradientBox(x0, y0, x1, y1 float64, col color.RGBA, a0, a1 float64) {
	x0, y0, x1, y1 = x0+c.ox, y0+c.oy, x1+c.ox, y1+c.oy
	b := image.Rect(round(x0), round(y0), round(x1), round(y1)).Intersect(c.img.Bounds())
	if b.Empty() {
		return
	}
	w := float64(b.Dx())
	if w <= 1 {
		return
	}
	bounds := c.img.Bounds()
	stride := bounds.Dx()
	h := float64(b.Dy())
	for y := b.Min.Y; y < b.Max.Y; y++ {
		// 上下边缘各留 26% 做淡出，避免出现一条硬邦邦的矩形边界
		fy := 1.0
		if h > 0 {
			ty := (float64(y-b.Min.Y) + 0.5) / h
			const k = 0.26
			if ty < k {
				fy = ty / k
			} else if ty > 1-k {
				fy = (1 - ty) / k
			}
		}
		row := (y - bounds.Min.Y) * stride
		for x := b.Min.X; x < b.Max.X; x++ {
			t := float64(x-b.Min.X) / w
			a := (a0 + (a1-a0)*t) * fy
			if a <= 0 {
				continue
			}
			if a > 1 {
				a = 1
			}
			al := int(a*255 + 0.5)
			o := (row + (x - bounds.Min.X)) * 4
			c.img.Pix[o] = uint8(int(col.R) * al / 255)
			c.img.Pix[o+1] = uint8(int(col.G) * al / 255)
			c.img.Pix[o+2] = uint8(int(col.B) * al / 255)
			c.img.Pix[o+3] = uint8(al)
		}
	}
	c.dirty.add(x0, y0, x1, y1)
}

// shadeVGrad 竖直方向的"压暗"：把已有像素按 f0→f1 的比例做乘法变暗。
//
// 为什么不直接用半透明黑覆盖（fillGradientBox）：那个函数是 Src 语义，
// 会用半透明色**替换**像素；前景层是 16bpp + 色键、没有 alpha 通道，
// alpha 变 0 就等于把这块挖成透明，原厂画面会从压暗过渡带里透出来。
// 这里只乘 RGB、不动 alpha，画面越压越暗但始终不透明。
func (c *canvas) shadeVGrad(x0, y0, x1, y1 float64, f0, f1 float64) {
	x0, y0, x1, y1 = x0+c.ox, y0+c.oy, x1+c.ox, y1+c.oy
	b := image.Rect(round(x0), round(y0), round(x1), round(y1)).Intersect(c.img.Bounds())
	if b.Empty() {
		return
	}
	stride := c.img.Bounds().Dx()
	h := float64(b.Dy())
	for y := b.Min.Y; y < b.Max.Y; y++ {
		ty := 0.0
		if h > 0 {
			ty = (float64(y-b.Min.Y) + 0.5) / h
		}
		f := f0 + (f1-f0)*ty
		if f >= 1 {
			continue
		}
		if f < 0 {
			f = 0
		}
		row := (y - b.Min.Y) * stride
		for x := b.Min.X; x < b.Max.X; x++ {
			o := (row + (x - b.Min.X)) * 4
			if c.img.Pix[o+3] == 0 {
				continue
			}
			c.img.Pix[o] = uint8(float64(c.img.Pix[o]) * f)
			c.img.Pix[o+1] = uint8(float64(c.img.Pix[o+1]) * f)
			c.img.Pix[o+2] = uint8(float64(c.img.Pix[o+2]) * f)
		}
	}
	c.dirty.add(x0, y0, x1, y1)
}

// shadeVProfile 一次遍历完成"上暗—中间—下暗"的分区压暗。
//
// 三次 shadeVGrad 要扫三遍全屏（每次 138 万像素），仪表上没必要。
// 这里用三段锚点（0~18% 从 topF 过渡到 midF，18%~70% 保持 midF，
// 70%~100% 过渡到 botF），一次扫完。
func (c *canvas) shadeVProfile(x0, y0, x1, y1 float64, topF, midF, botF float64) {
	x0, y0, x1, y1 = x0+c.ox, y0+c.oy, x1+c.ox, y1+c.oy
	b := image.Rect(round(x0), round(y0), round(x1), round(y1)).Intersect(c.img.Bounds())
	if b.Empty() {
		return
	}
	stride := c.img.Bounds().Dx()
	h := float64(b.Dy())
	for y := b.Min.Y; y < b.Max.Y; y++ {
		t := 0.0
		if h > 0 {
			t = (float64(y-b.Min.Y) + 0.5) / h
		}
		var f float64
		switch {
		case t < 0.18:
			f = topF + (midF-topF)*(t/0.18)
		case t > 0.70:
			f = midF + (botF-midF)*((t-0.70)/0.30)
		default:
			f = midF
		}
		if f >= 1 {
			continue
		}
		if f < 0 {
			f = 0
		}
		row := (y - b.Min.Y) * stride
		for x := b.Min.X; x < b.Max.X; x++ {
			o := (row + (x - b.Min.X)) * 4
			if c.img.Pix[o+3] == 0 {
				continue
			}
			c.img.Pix[o] = uint8(float64(c.img.Pix[o]) * f)
			c.img.Pix[o+1] = uint8(float64(c.img.Pix[o+1]) * f)
			c.img.Pix[o+2] = uint8(float64(c.img.Pix[o+2]) * f)
		}
	}
	c.dirty.add(x0, y0, x1, y1)
}

// shadeHGrad 水平方向的"压暗"（左边深、右边浅），用法同 shadeVGrad。
// 充电页左侧要放大数字和状态文字，动画亮部会顶掉文字，所以先横向压一道。
func (c *canvas) shadeHGrad(x0, y0, x1, y1 float64, f0, f1 float64) {
	x0, y0, x1, y1 = x0+c.ox, y0+c.oy, x1+c.ox, y1+c.oy
	b := image.Rect(round(x0), round(y0), round(x1), round(y1)).Intersect(c.img.Bounds())
	if b.Empty() {
		return
	}
	stride := c.img.Bounds().Dx()
	w := float64(b.Dx())
	for y := b.Min.Y; y < b.Max.Y; y++ {
		row := (y - b.Min.Y) * stride
		for x := b.Min.X; x < b.Max.X; x++ {
			tx := 0.0
			if w > 0 {
				tx = (float64(x-b.Min.X) + 0.5) / w
			}
			f := f0 + (f1-f0)*tx
			if f >= 1 {
				continue
			}
			if f < 0 {
				f = 0
			}
			o := (row + (x - b.Min.X)) * 4
			if c.img.Pix[o+3] == 0 {
				continue
			}
			c.img.Pix[o] = uint8(float64(c.img.Pix[o]) * f)
			c.img.Pix[o+1] = uint8(float64(c.img.Pix[o+1]) * f)
			c.img.Pix[o+2] = uint8(float64(c.img.Pix[o+2]) * f)
		}
	}
	c.dirty.add(x0, y0, x1, y1)
}

// drawImgSub 把源的子矩形缩放到目标矩形（drawImg 只能整图缩放，
// 做"等比铺满 + 居中裁切"需要能指定源裁切区）。
func (c *canvas) drawImgSub(src image.Image, sx, sy, sw, sh, dx, dy, dw, dh float64) {
	if src == nil || sw <= 0 || sh <= 0 || dw <= 0 || dh <= 0 {
		return
	}
	sr := image.Rect(round(sx), round(sy), round(sx+sw), round(sy+sh)).Intersect(src.Bounds())
	dr := image.Rect(round(dx), round(dy), round(dx+dw), round(dy+dh)).Intersect(c.img.Bounds())
	if sr.Empty() || dr.Empty() {
		return
	}
	xdraw.CatmullRom.Scale(c.img, dr, src, sr, draw.Over, nil)
	c.dirty.add(dx, dy, dx+dw, dy+dh)
}

// floorKey 把"会被硬件色键抠掉"的像素抬到刚好不被抠掉的下限。
//
// 前景层是 16bpp（RGB565）+ 色键，Key=0 表示「纯黑即透明」，原厂画面从这些
// 像素透出来。全屏页面必须保证区域内没有量化后为 0 的像素，否则极光动画的
// 夜空（纯黑）会把下面的车模、时速、状态栏全漏出来。
//
// 只处理 alpha=255 的像素（抗锯齿边缘不动），把 R<8 / G<4 / B<8 的抬到 8/8/8：
// RGB565 里就是 (1,2,1)，肉眼和纯黑没有区别，但不会被抠掉。
func (c *canvas) floorKey(x0, y0, x1, y1 float64) {
	b := image.Rect(round(x0), round(y0), round(x1), round(y1)).Intersect(c.img.Bounds())
	if b.Empty() {
		return
	}
	bounds := c.img.Bounds()
	stride := bounds.Dx()
	for y := b.Min.Y; y < b.Max.Y; y++ {
		row := (y - bounds.Min.Y) * stride
		for x := b.Min.X; x < b.Max.X; x++ {
			o := (row + (x - bounds.Min.X)) * 4
			if c.img.Pix[o+3] != 0xff {
				continue
			}
			if c.img.Pix[o] < 8 && c.img.Pix[o+1] < 4 && c.img.Pix[o+2] < 8 {
				c.img.Pix[o], c.img.Pix[o+1], c.img.Pix[o+2] = 8, 8, 8
			}
		}
	}
	c.dirty.add(x0, y0, x1, y1)
}

// strokeArc 抗锯齿椭圆弧描边（用于盲区提示），角度制（0=右，顺时针向下为正）。
//
// 原来是 24 段折线 + thickLine(fillRect) 硬画：5px 宽的弧在 1920×720 上
// 边缘全是台阶，和字体/圆角卡片的平滑边缘放一起很突兀。
// 现在和 strokeRoundRectAA 走同一套：SDF 算环带覆盖率 → blendPx 逐像素混合。
// 环带两侧各留 0.5px 渐变，两个端点也按到端点射线的有符号距离做 0.5px 渐变。
func (c *canvas) strokeArc(cx, cy, rx, ry, a0, a1, w float64, col color.RGBA) {
	arx, ary := math.Abs(rx), math.Abs(ry)
	if arx < 0.5 || ary < 0.5 || w <= 0 || a1 <= a0 {
		return
	}
	// 端点射线方向（椭圆参数角对应的点），归一化后叉积就是到射线的垂直距离。
	// 这里必须用**带符号的 rx**：rx<0 是右弧的左右镜像，端点方向也要跟着镜像，
	// 否则叉积会把可见侧判反（右弧被裁掉半截）。
	s0, s1 := a0*math.Pi/180, a1*math.Pi/180
	e0x, e0y := rx*math.Cos(s0), ry*math.Sin(s0)
	e1x, e1y := rx*math.Cos(s1), ry*math.Sin(s1)
	l0, l1 := math.Hypot(e0x, e0y), math.Hypot(e1x, e1y)
	if l0 == 0 || l1 == 0 {
		return
	}
	e0x, e0y = e0x/l0, e0y/l0
	e1x, e1y = e1x/l1, e1y/l1
	// 镜像后「角度增大」对应的叉积符号也翻转。
	sign := 1.0
	if rx < 0 {
		sign = -1
	}
	rmin := math.Min(arx, ary)
	m := w/2 + 1
	b := c.img.Bounds()
	ca := int(col.A)
	for y := int(math.Floor(cy - ary - m)); y <= int(math.Ceil(cy+ary+m)); y++ {
		if y < b.Min.Y || y >= b.Max.Y {
			continue
		}
		for x := int(math.Floor(cx - arx - m)); x <= int(math.Ceil(cx+arx+m)); x++ {
			if x < b.Min.X || x >= b.Max.X {
				continue
			}
			dx, dy := float64(x)+0.5-cx, float64(y)+0.5-cy
			// 椭圆归一化距离 → 近似像素距离（长短轴接近时足够准）
			q := math.Sqrt((dx/arx)*(dx/arx) + (dy/ary)*(dy/ary))
			d := (q - 1) * rmin
			cov := w/2 - math.Abs(d) + 0.5 // 环带 |d|<=w/2，两侧 0.5px 渐变
			if cov <= 0 {
				continue
			}
			// 起点/终点各留 0.5px 渐变，端点不是硬切
			cov0 := 0.5 + sign*(e0x*dy-e0y*dx)
			cov1 := 0.5 - sign*(e1x*dy-e1y*dx)
			cov = math.Min(cov, math.Min(cov0, cov1))
			if cov <= 0 {
				continue
			}
			if cov > 1 {
				cov = 1
			}
			c.blendPx(x, y, col, int(cov*float64(ca)))
		}
	}
	c.dirty.add(cx-arx-m, cy-ary-m, cx+arx+m, cy+ary+m)
}

// blendPanel 调试面板的深色底
func (c *canvas) blendPanel(x0, y0, x1, y1 float64, fill color.RGBA) {
	c.fillRoundRect(x0, y0, x1, y1, 8, fill)
}

// ---------- 抗锯齿圆角矩形 / 圆 ----------
//
// 原来的 fillRoundRect / strokeRoundRect 是按扫描行算 inset、再用 12 段折线
// 近似圆角，画 Ø48 的灯盘和 r32 的胶囊时边缘会出现肉眼可见的多边形台阶和
// 断口。这两个用有符号距离场（SDF）算覆盖率，边缘是逐像素渐变的，48px 下
// 也能画干净。

// blendPx 按覆盖率 a（0..255）把 col 叠到画布已有像素上
func (c *canvas) blendPx(x, y int, col color.RGBA, a int) {
	if a <= 0 {
		return
	}
	if a > 255 {
		a = 255
	}
	o := c.img.PixOffset(x, y)
	pr := int(col.R) * a / 255
	pg := int(col.G) * a / 255
	pb := int(col.B) * a / 255
	ia := 255 - a
	c.img.Pix[o] = uint8(pr + int(c.img.Pix[o])*ia/255)
	c.img.Pix[o+1] = uint8(pg + int(c.img.Pix[o+1])*ia/255)
	c.img.Pix[o+2] = uint8(pb + int(c.img.Pix[o+2])*ia/255)
	c.img.Pix[o+3] = uint8(a + int(c.img.Pix[o+3])*ia/255)
}

// rrSDF 圆角矩形有符号距离：内部为负、边界为 0、外部为正
func rrSDF(px, py, x0, y0, x1, y1, r float64) float64 {
	if r > (x1-x0)/2 {
		r = (x1 - x0) / 2
	}
	if r > (y1-y0)/2 {
		r = (y1 - y0) / 2
	}
	cx := math.Min(math.Max(px, x0+r), x1-r)
	cy := math.Min(math.Max(py, y0+r), y1-r)
	dx, dy := px-cx, py-cy
	return math.Sqrt(dx*dx+dy*dy) - r
}

// fillRoundRectAA 抗锯齿填充圆角矩形（r = 宽/2 时就是一个圆）
func (c *canvas) fillRoundRectAA(x0, y0, x1, y1, r float64, col color.RGBA) {
	if x1 <= x0 || y1 <= y0 {
		return
	}
	ix0, iy0 := int(math.Floor(x0-1)), int(math.Floor(y0-1))
	ix1, iy1 := int(math.Ceil(x1+1)), int(math.Ceil(y1+1))
	b := c.img.Bounds()
	ca := int(col.A)
	for y := iy0; y <= iy1; y++ {
		if y < b.Min.Y || y >= b.Max.Y {
			continue
		}
		for x := ix0; x <= ix1; x++ {
			if x < b.Min.X || x >= b.Max.X {
				continue
			}
			d := rrSDF(float64(x)+0.5, float64(y)+0.5, x0, y0, x1, y1, r)
			cov := 0.5 - d // d<=-0.5 全覆盖，d>=0.5 全透明
			if cov <= 0 {
				continue
			}
			if cov > 1 {
				cov = 1
			}
			c.blendPx(x, y, col, int(cov*float64(ca)))
		}
	}
	c.dirty.add(x0-1, y0-1, x1+1, y1+1)
}

// fillRoundRectVGrad 纵向渐变填充圆角矩形（上 top → 下 bottom）。
//
// 和 fillGradientBox 的区别：那个是横向渐变 + 上下淡出，给歌词融边用的；
// 这个是实心卡片底，只在纵向插值颜色，边缘同样用 SDF 算覆盖率抗锯齿。
func (c *canvas) fillRoundRectVGrad(x0, y0, x1, y1, r float64, top, bottom color.RGBA) {
	if x1 <= x0 || y1 <= y0 {
		return
	}
	ix0, iy0 := int(math.Floor(x0-1)), int(math.Floor(y0-1))
	ix1, iy1 := int(math.Ceil(x1+1)), int(math.Ceil(y1+1))
	b := c.img.Bounds()
	h := y1 - y0
	for y := iy0; y <= iy1; y++ {
		if y < b.Min.Y || y >= b.Max.Y {
			continue
		}
		t := (float64(y) + 0.5 - y0) / h
		if t < 0 {
			t = 0
		} else if t > 1 {
			t = 1
		}
		col := color.RGBA{
			uint8(float64(top.R) + (float64(bottom.R)-float64(top.R))*t),
			uint8(float64(top.G) + (float64(bottom.G)-float64(top.G))*t),
			uint8(float64(top.B) + (float64(bottom.B)-float64(top.B))*t),
			uint8(float64(top.A) + (float64(bottom.A)-float64(top.A))*t),
		}
		ca := int(col.A)
		for x := ix0; x <= ix1; x++ {
			if x < b.Min.X || x >= b.Max.X {
				continue
			}
			d := rrSDF(float64(x)+0.5, float64(y)+0.5, x0, y0, x1, y1, r)
			cov := 0.5 - d
			if cov <= 0 {
				continue
			}
			if cov > 1 {
				cov = 1
			}
			c.blendPx(x, y, col, int(cov*float64(ca)))
		}
	}
	c.dirty.add(x0-1, y0-1, x1+1, y1+1)
}

// strokeRoundRectAA 抗锯齿描边（描边朝内，宽度 w）
func (c *canvas) strokeRoundRectAA(x0, y0, x1, y1, r, w float64, col color.RGBA) {
	if x1 <= x0 || y1 <= y0 || w <= 0 {
		return
	}
	ix0, iy0 := int(math.Floor(x0-1)), int(math.Floor(y0-1))
	ix1, iy1 := int(math.Ceil(x1+1)), int(math.Ceil(y1+1))
	b := c.img.Bounds()
	ca := int(col.A)
	for y := iy0; y <= iy1; y++ {
		if y < b.Min.Y || y >= b.Max.Y {
			continue
		}
		for x := ix0; x <= ix1; x++ {
			if x < b.Min.X || x >= b.Max.X {
				continue
			}
			d := rrSDF(float64(x)+0.5, float64(y)+0.5, x0, y0, x1, y1, r)
			// 环带：-w <= d <= 0；两侧各留 0.5px 渐变
			cov := math.Min(-d, d+w) + 0.5
			if cov <= 0 {
				continue
			}
			if cov > 1 {
				cov = 1
			}
			c.blendPx(x, y, col, int(cov*float64(ca)))
		}
	}
	c.dirty.add(x0-1, y0-1, x1+1, y1+1)
}

// ---------- 位图贴图 ----------

// drawImg 把 src 缩放到目标矩形上叠加（Alpha 混合，不覆盖已有内容）。
//
// 用 CatmullRom 而不是 ApproxBiLinear：红绿灯素材是 200px 径向渐变圆盘，
// 要缩到 48px，双线性会在盘缘留下台阶，CatmullRom 边缘干净得多。
// 亮度/色度查表：YCbCr → RGB 的定点表，避免每像素做浮点运算。
var (
	ycbcrY  [256]int32
	ycbcrR  [256]int32
	ycbcrG1 [256]int32
	ycbcrG2 [256]int32
	ycbcrB  [256]int32
	ycbcrOK bool
)

func initYCbCrTables() {
	if ycbcrOK {
		return
	}
	for i := 0; i < 256; i++ {
		ycbcrY[i] = int32(i) << 8
		cb := int32(i) - 128
		ycbcrR[i] = 91881 * cb >> 8   // 1.402
		ycbcrG1[i] = -22554 * cb >> 8 // -0.344
		ycbcrG2[i] = -46802 * cb >> 8 // -0.714
		ycbcrB[i] = 116130 * cb >> 8  // 1.772
	}
	ycbcrOK = true
}

// drawYCbCrOpaque 把解码后的 YCbCr 图像 1:1 画到画布（不缩放、不混合），
// 顺手把会硬件色键抠掉的纯黑抬到 (8,8,8)。
//
// 为什么不用 draw.Draw：那条路径每像素走 image.Image 接口 + 颜色模型转换，
// 1920×720 一帧要 380ms（实测），充电动画直接卡到 2fps。这里直接按下标算，
// 一帧降到几十毫秒。
func (c *canvas) drawYCbCrOpaque(src *image.YCbCr, x0, y0 int) {
	initYCbCrTables()
	dst := c.img
	db := dst.Bounds()
	W := db.Dx()
	sb := src.Bounds()
	sw, sh := sb.Dx(), sb.Dy()

	for sy := 0; sy < sh; sy++ {
		dy := y0 + sy
		if dy < db.Min.Y || dy >= db.Max.Y {
			continue
		}
		yRow := (sb.Min.Y + sy) * src.YStride
		cRow := ((sb.Min.Y + sy) / 2) * src.CStride
		o := (dy*W + (x0 - db.Min.X)) * 4
		for sx := 0; sx < sw; sx++ {
			if x0+sx >= db.Max.X {
				break
			}
			yi := int32(src.Y[yRow+sb.Min.X+sx])
			ci := cRow + (sb.Min.X+sx)/2
			cb := int32(src.Cb[ci])
			cr := int32(src.Cr[ci])

			yy := ycbcrY[yi]
			r := (yy + ycbcrR[cr]) >> 8
			g := (yy + ycbcrG1[cb] + ycbcrG2[cr]) >> 8
			b := (yy + ycbcrB[cb]) >> 8
			if r < 0 {
				r = 0
			} else if r > 255 {
				r = 255
			}
			if g < 0 {
				g = 0
			} else if g > 255 {
				g = 255
			}
			if b < 0 {
				b = 0
			} else if b > 255 {
				b = 255
			}
			// 黑地板：RGB565 里全 0 会被硬件色键抠成透明
			if r < 8 && g < 4 && b < 8 {
				r, g, b = 8, 8, 8
			}
			dst.Pix[o] = uint8(r)
			dst.Pix[o+1] = uint8(g)
			dst.Pix[o+2] = uint8(b)
			dst.Pix[o+3] = 0xFF
			o += 4
		}
	}
	c.dirty.add(float64(x0), float64(y0), float64(x0+sw), float64(y0+sh))
}

// drawYCbCrFit 把 YCbCr 图按"拉伸铺满"画到整块画布（车机投来 960×360 的帧，
// 这里 2 倍放大到 1920×720）。同样是定点运算 + 逐像素算黑地板，避免 draw.Draw
// 的接口开销。
func (c *canvas) drawYCbCrFit(src *image.YCbCr, dw, dh int) {
	initYCbCrTables()
	dst := c.img
	db := dst.Bounds()
	W := db.Dx()
	sb := src.Bounds()
	sw, sh := sb.Dx(), sb.Dy()
	if sw <= 0 || sh <= 0 {
		return
	}
	stepX := (int64(sw) << 16) / int64(dw)
	rows := make([]int32, dh) // 目标行 → 源行
	for y := 0; y < dh; y++ {
		rows[y] = int32(int64(y) * int64(sh) / int64(dh))
	}
	for y := 0; y < dh; y++ {
		dy := y - db.Min.Y
		if dy < 0 || dy >= dh {
			continue
		}
		syy := int(rows[y])
		yRow := (sb.Min.Y + syy) * src.YStride
		cRow := ((sb.Min.Y + syy) / 2) * src.CStride
		o := dy * W * 4
		acc := int64(0)
		for x := 0; x < dw; x++ {
			sxx := int(acc >> 16)
			acc += stepX
			if x >= W {
				break
			}
			yi := int32(src.Y[yRow+sb.Min.X+sxx])
			ci := cRow + (sb.Min.X+sxx)/2
			cb := int32(src.Cb[ci])
			cr := int32(src.Cr[ci])

			yy := ycbcrY[yi]
			r := (yy + ycbcrR[cr]) >> 8
			g := (yy + ycbcrG1[cb] + ycbcrG2[cr]) >> 8
			b := (yy + ycbcrB[cb]) >> 8
			if r < 0 {
				r = 0
			} else if r > 255 {
				r = 255
			}
			if g < 0 {
				g = 0
			} else if g > 255 {
				g = 255
			}
			if b < 0 {
				b = 0
			} else if b > 255 {
				b = 255
			}
			if r < 8 && g < 4 && b < 8 {
				r, g, b = 8, 8, 8
			}
			dst.Pix[o] = uint8(r)
			dst.Pix[o+1] = uint8(g)
			dst.Pix[o+2] = uint8(b)
			dst.Pix[o+3] = 0xFF
			o += 4
		}
	}
	c.dirty.add(0, 0, float64(dw), float64(dh))
}

// drawImgFit 等比拉伸铺满画布；YCbCr 走快速通道，其余类型回落通用缩放
func (c *canvas) drawImgFit(src image.Image, dw, dh int) {
	if src == nil || dw <= 0 || dh <= 0 {
		return
	}
	if yc, ok := src.(*image.YCbCr); ok {
		c.drawYCbCrFit(yc, dw, dh)
		return
	}
	b := src.Bounds()
	c.drawImgSub(src, float64(b.Min.X), float64(b.Min.Y),
		float64(b.Dx()), float64(b.Dy()), 0, 0, float64(dw), float64(dh))
}

func (c *canvas) drawImg(src image.Image, x, y, w, h float64) {
	if src == nil || w <= 0 || h <= 0 {
		return
	}
	dr := image.Rect(round(x), round(y), round(x+w), round(y+h)).Intersect(c.img.Bounds())
	if dr.Empty() {
		return
	}
	b := src.Bounds()
	if b.Dx() == dr.Dx() && b.Dy() == dr.Dy() {
		// 尺寸一致：YCbCr 走定点快速通道（快 10 倍），其它类型直接拷贝。
		if yc, ok := src.(*image.YCbCr); ok {
			c.drawYCbCrOpaque(yc, dr.Min.X, dr.Min.Y)
			return
		}
		// 仪表上全屏（1920×720）跑 CatmullRom 每帧要重采样 138 万像素，
		// 实测会把守护进程拖死，所以充电动画改成离线预渲染成整屏，这里只拷贝。
		draw.Draw(c.img, dr, src, b.Min, draw.Over)
	} else {
		xdraw.CatmullRom.Scale(c.img, dr, src, b, draw.Over, nil)
	}
	c.dirty.add(x, y, x+w, y+h)
}

// drawImgArrow 只把 src 里的「白色箭头」剪影涂成 col，彩色圆盘部分不参与。
//
// 用途：熄灭/无数据的灯位要画「灰盘 + 更暗的箭头」，而素材包只有点亮的
// 彩色盘（盘和箭头在同一张图里，alpha 都是不透明）。判据取三通道最小值：
// 素材的箭头是纯白 #FFFFFF，而红/黄/绿盘的圆盘至少有一个通道很低
// （红 G/B 低、黄 B 低、绿 R/B 低），所以 min(r,g,b) 高的一定是箭头。
func (c *canvas) drawImgArrow(src image.Image, x, y, w, h float64, col color.RGBA) {
	if src == nil || w <= 0 || h <= 0 {
		return
	}
	dr := image.Rect(round(x), round(y), round(x+w), round(y+h)).Intersect(c.img.Bounds())
	if dr.Empty() {
		return
	}
	b := src.Bounds()
	sw, sh := float64(b.Dx()), float64(b.Dy())
	dw, dh := float64(dr.Dx()), float64(dr.Dy())
	ca := int(col.A)
	const whiteCut = 0x90 // 三通道最小值的下限（8bit）
	for py := 0; py < dr.Dy(); py++ {
		sy := b.Min.Y + int((float64(py)+0.5)*sh/dh)
		for px := 0; px < dr.Dx(); px++ {
			sx := b.Min.X + int((float64(px)+0.5)*sw/dw)
			sr, sg, sb, sa := src.At(sx, sy).RGBA()
			if sa == 0 {
				continue
			}
			m := sr
			if sg < m {
				m = sg
			}
			if sb < m {
				m = sb
			}
			if m>>8 < whiteCut {
				continue // 彩色盘体，跳过
			}
			// 箭头边缘有抗锯齿，用「超过阈值的程度」当覆盖率，避免锯齿台阶
			cov := int((m>>8 - whiteCut) * 255 / (255 - whiteCut))
			a := cov * int(sa>>8) / 255 * ca / 255
			if a <= 0 {
				continue
			}
			o := c.img.PixOffset(dr.Min.X+px, dr.Min.Y+py)
			pr := int(col.R) * a / 255
			pg := int(col.G) * a / 255
			pb := int(col.B) * a / 255
			ia := 255 - a
			c.img.Pix[o] = uint8(pr + int(c.img.Pix[o])*ia/255)
			c.img.Pix[o+1] = uint8(pg + int(c.img.Pix[o+1])*ia/255)
			c.img.Pix[o+2] = uint8(pb + int(c.img.Pix[o+2])*ia/255)
			c.img.Pix[o+3] = uint8(a + int(c.img.Pix[o+3])*ia/255)
		}
	}
	c.dirty.add(x, y, x+w, y+h)
}
