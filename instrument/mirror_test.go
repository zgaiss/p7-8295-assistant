package main

// 打灯影像的纯逻辑单测：协议往返、尺寸校验、区域几何、脏矩形减法。
//
// 重点在 TestRectMinus*：影像区能"不花屏"的数学前提是
// 「boxBlit 减去影像区后，剩下的每一块都不与影像区相交」，
// 且减去前后面积守恒。这两条一旦破了，表现就是影像被擦掉或留残影，
// 而且很难在实车上定位，所以必须用测试钉死。

import (
	"bytes"
	"compress/zlib"
	"testing"

	"github.com/klauspost/compress/zstd"
)

func TestMirHeaderRoundTrip(t *testing.T) {
	in := mirHeader{
		Ver:   mirVersion,
		Codec: mirCodecZstd,
		Side:  mirSideRight,
		Flags: 0,
		Seq:   123456,
		W:     mirZoneW,
		H:     mirZoneH,
		CLen:  4096,
		ULen:  mirZoneW * mirZoneH * 2,
	}
	b := in.marshal()
	if len(b) != mirHdrLen {
		t.Fatalf("帧头长度 %d，期望 %d", len(b), mirHdrLen)
	}
	out, err := parseMirHeader(b)
	if err != nil {
		t.Fatalf("解析失败: %v", err)
	}
	if out != in {
		t.Fatalf("往返不一致\n in=%+v\nout=%+v", in, out)
	}
}

func TestMirHeaderRejects(t *testing.T) {
	good := mirHeader{
		Ver: mirVersion, Codec: mirCodecRaw, W: mirZoneW, H: mirZoneH,
		CLen: mirZoneW * mirZoneH * 2, ULen: mirZoneW * mirZoneH * 2,
	}
	cases := []struct {
		name string
		b    []byte
	}{
		{"太短", []byte("P7")},
		{"魔数错", func() []byte { b := good.marshal(); b[0] = 'X'; return b }()},
		{"版本高", func() []byte { b := good.marshal(); b[4] = 9; return b }()},
	}
	for _, c := range cases {
		if _, err := parseMirHeader(c.b); err == nil {
			t.Fatalf("%s：应当拒绝，却通过了", c.name)
		}
	}

	// 尺寸/长度校验
	bad := []mirHeader{
		{Ver: mirVersion, W: 320, H: 180, CLen: 100, ULen: 320 * 180 * 2},           // 不在白名单
		{Ver: mirVersion, W: mirZoneW, H: mirZoneH, CLen: 100, ULen: 100},           // ulen 不是 w*h*2
		{Ver: mirVersion, W: mirZoneW, H: mirZoneH, CLen: 0, ULen: mirZoneW * mirZoneH * 2},                 // clen=0
		{Ver: mirVersion, W: mirZoneW, H: mirZoneH, CLen: mirZoneW*mirZoneH*2 + 1, ULen: mirZoneW * mirZoneH * 2}, // clen 超过裸帧
		{Ver: mirVersion, W: mirSrcW, H: mirSrcH, CLen: 100, ULen: mirZoneW * mirZoneH * 2}, // 640×360 却按 960×540 报 ulen
	}
	for i, h := range bad {
		if err := h.ok(); err == nil {
			t.Fatalf("非法头 #%d 应当拒绝：%+v", i, h)
		}
	}
	if err := good.ok(); err != nil {
		t.Fatalf("合法头被拒: %v", err)
	}

	// 两种源尺寸都必须放行：960×540 走整行 memcpy，640×360 走放大。
	for _, wh := range [][2]int{{mirZoneW, mirZoneH}, {mirSrcW, mirSrcH}} {
		h := mirHeader{Ver: mirVersion, Codec: mirCodecRaw, W: uint16(wh[0]), H: uint16(wh[1])}
		h.ULen = uint32(wh[0] * wh[1] * 2)
		h.CLen = h.ULen
		if err := h.ok(); err != nil {
			t.Fatalf("%dx%d 应被接受: %v", wh[0], wh[1], err)
		}
	}
}

func TestMirZoneGeometry(t *testing.T) {
	l := mirZoneRect(mirSideLeft)
	if l.x0 != 0 || l.x1 != 960 || l.y0 != 60 || l.y1 != 600 {
		t.Fatalf("左区几何不符: %+v", l)
	}
	r := mirZoneRect(mirSideRight)
	if r.x0 != frameW-960 || r.x1 != frameW || r.y0 != 60 || r.y1 != 600 {
		t.Fatalf("右区几何不符: %+v", r)
	}
	if !mirZoneRect(mirSideNone).empty() {
		t.Fatal("撤区应返回空矩形")
	}
	// 16:9 严格对齐，避免 AVM 画面被拉伸
	if float64(mirZoneH)*16 != float64(mirZoneW)*9 {
		t.Fatalf("区域不是 16:9：%dx%d", mirZoneW, mirZoneH)
	}
	// 顶部必须让出档位条，底部必须让出状态条
	for _, z := range []rect{l, r} {
		if z.y0 < 60 {
			t.Fatalf("影像区顶到档位条: y0=%.0f", z.y0)
		}
		if z.y1 > 600 {
			t.Fatalf("影像区压到底部状态条: y1=%.0f", z.y1)
		}
	}
}

// 减法的核心性质：结果各块互不相交、都不与 hole 相交、面积守恒。
func TestRectMinusProperties(t *testing.T) {
	holes := []rect{
		mirZoneRect(mirSideLeft),
		mirZoneRect(mirSideRight),
		{},
		{0, 0, 10, 10},                 // 左上角小片
		{1900, 700, 1920, 720},         // 右下角小片
		{900, 50, 1020, 610},           // 跨中线竖条
		{-20, -20, 40, 40},             // 出界
	}
	boxes := []rect{
		{0, 0, 1920, 720},              // 整屏
		{0, 0, 960, 720},               // 左半屏
		{960, 0, 1920, 720},            // 右半屏
		{870, 160, 1050, 280},          // 红绿灯卡
		{700, 100, 1200, 640},          // 中部跨中线
		{1160, 430, 1810, 630},         // 歌词块
		{1200, 500, 1300, 550},         // 完全落在影像区里
		{},
	}
	for _, box := range boxes {
		for _, hole := range holes {
			parts := box.minus(hole)
			// 面积守恒
			var got float64
			for _, p := range parts {
				if p.empty() {
					t.Fatalf("box=%+v hole=%+v：返回了空矩形", box, hole)
				}
				got += (p.x1 - p.x0) * (p.y1 - p.y0)
				if p.intersects(hole) {
					t.Fatalf("box=%+v hole=%+v：块 %+v 与影像区相交（会花屏）", box, hole, p)
				}
			}
			inter := 0.0
			if box.intersects(hole) {
				x0, x1 := maxf(box.x0, hole.x0), minf(box.x1, hole.x1)
				y0, y1 := maxf(box.y0, hole.y0), minf(box.y1, hole.y1)
				inter = (x1 - x0) * (y1 - y0)
			}
			want := (box.x1-box.x0)*(box.y1-box.y0) - inter
			if want < 0 {
				want = 0
			}
			if !almostEq(got, want) {
				t.Fatalf("box=%+v hole=%+v：面积不守恒 got=%.0f want=%.0f parts=%+v", box, hole, got, want, parts)
			}
			// 各块两两不重叠
			for i := 0; i < len(parts); i++ {
				for j := i + 1; j < len(parts); j++ {
					if parts[i].intersects(parts[j]) {
						t.Fatalf("box=%+v hole=%+v：块 %d 与 %d 重叠", box, hole, i, j)
					}
				}
			}
		}
	}
}

// 没有任何影像时，减法必须原样返回，保证"关掉影像后与改动前等价"。
func TestRectMinusNoOpWhenInactive(t *testing.T) {
	box := rect{100, 100, 1800, 700}
	parts := box.minus(rect{})
	if len(parts) != 1 || parts[0] != box {
		t.Fatalf("无影像时应原样返回，得到 %+v", parts)
	}
}

func maxf(a, b float64) float64 {
	if a > b {
		return a
	}
	return b
}

func minf(a, b float64) float64 {
	if a < b {
		return a
	}
	return b
}

func almostEq(a, b float64) bool {
	d := a - b
	if d < 0 {
		d = -d
	}
	return d < 0.5
}

// 序列化后的帧头必须能被打包成"车机端同款"的字节流：
// 小端、字段偏移固定。这个测试防止有人顺手改成 BigEndian。
func TestMirHeaderByteLayout(t *testing.T) {
	h := mirHeader{Ver: 1, Codec: 1, Side: 2, Flags: 0, Seq: 0x01020304,
		W: 960, H: 540, CLen: 0x0000ABCD, ULen: 0x00112233}
	b := h.marshal()
	want := []byte{
		'P', '7', 'V', 'D', 1, 1, 2, 0,
		0x04, 0x03, 0x02, 0x01, // seq LE
		0xC0, 0x03, // 960 LE
		0x1C, 0x02, // 540 LE
		0xCD, 0xAB, 0x00, 0x00,
		0x33, 0x22, 0x11, 0x00,
	}
	if !bytes.Equal(b, want) {
		t.Fatalf("字节布局不符\n got=% x\nwant=% x", b, want)
	}
}

// TestMirDecodeAllCodecs 三种编码必须解出逐字节一致的像素。
//
// 为什么值得单独测：实车联调时最先踩的就是"编解码对不上但没人报错"——
// 帧率正常、连接正常、画面不出。这里把车机端会用的 Deflater(zlib) 和
// 主机联调用的 raw/zstd 都跑一遍，任何一条路径改坏都会在 CI 就暴露。
func TestMirDecodeAllCodecs(t *testing.T) {
	n := mirZoneW * mirZoneH * 2
	pix := make([]byte, n)
	for i := range pix {
		pix[i] = byte(i*7 + i/251)
	}
	v := newMirServer(9002, func(string, ...any) {})
	if v == nil {
		t.Skip("zstd 解码器不可用")
	}
	defer v.close()

	// raw
	raw := make([]byte, n)
	copy(raw, pix)
	if err := v.decode(mirCodecRaw, raw, make([]byte, n)); err != nil {
		t.Fatalf("raw: %v", err)
	}

	// zstd
	var zbuf bytes.Buffer
	zw, _ := zstd.NewWriter(&zbuf)
	zw.Write(pix)
	zw.Close()
	out := make([]byte, n)
	if err := v.decode(mirCodecZstd, zbuf.Bytes(), out); err != nil {
		t.Fatalf("zstd: %v", err)
	}
	if !bytes.Equal(out, pix) {
		t.Fatal("zstd 解压结果不一致")
	}

	// deflate（车机端 Deflater 输出的就是这种 zlib 流）
	var dbuf bytes.Buffer
	dw := zlib.NewWriter(&dbuf)
	dw.Write(pix)
	dw.Close()
	out2 := make([]byte, n)
	if err := v.decode(mirCodecDeflate, dbuf.Bytes(), out2); err != nil {
		t.Fatalf("deflate: %v", err)
	}
	if !bytes.Equal(out2, pix) {
		t.Fatal("deflate 解压结果不一致")
	}
	t.Logf("压缩率 raw=%d zstd=%d deflate=%d", len(raw), zbuf.Len(), dbuf.Len())
}

// TestMirDecodeRejects 坏数据必须返回错误而不是写出半帧。
func TestMirDecodeRejects(t *testing.T) {
	n := mirZoneW * mirZoneH * 2
	v := newMirServer(9002, func(string, ...any) {})
	if v == nil {
		t.Skip("zstd 解码器不可用")
	}
	defer v.close()

	if err := v.decode(mirCodecRaw, make([]byte, n-1), make([]byte, n)); err == nil {
		t.Fatal("raw 偏短应当报错")
	}
	if err := v.decode(mirCodecDeflate, []byte{1, 2, 3, 4}, make([]byte, n)); err == nil {
		t.Fatal("deflate 垃圾数据应当报错")
	}
	if err := v.decode(99, make([]byte, 16), make([]byte, n)); err == nil {
		t.Fatal("未知 codec 应当报错")
	}
}

// fakeWriter 记录直写通道实际写了哪块矩形，用来验证授权语义。
type fakeWriter struct {
	zone   rect
	n      int
	scaled bool
	srcW   int
	srcH   int
}

func (f *fakeWriter) write565Rect(b rect, pix []byte, srcW, srcH int) int {
	f.zone = b
	f.n++
	f.srcW, f.srcH = srcW, srcH
	return int(b.y1 - b.y0)
}

func (f *fakeWriter) write565Scaled(b rect, pix []byte, srcW, srcH int) int {
	f.zone = b
	f.n++
	f.scaled = true
	f.srcW, f.srcH = srcW, srcH
	return int(b.y1 - b.y0)
}

// TestMirDirectWriteAuth 钉死直写通道的授权语义。
//
// 这套语义是"不花屏"的新前提：主循环只有 4.7fps，影像改由解码线程直写，
// 因此"谁有权写这块矩形"必须由授权精确控制——未授权绝不能写（会把刚清掉的
// 区域又写回来，留下残影），方向不匹配也不能写（换侧时会画到对面半屏）。
func TestMirDirectWriteAuth(t *testing.T) {
	v := &mirServer{}
	pix := make([]byte, mirZoneW*mirZoneH*2)

	if v.flushTo(mirSideLeft, pix, mirZoneW, mirZoneH) {
		t.Fatal("未授权时不应写入")
	}

	w := &fakeWriter{}
	left := mirZoneRect(mirSideLeft)
	v.grant(w, left, mirSideLeft)
	if !v.flushTo(mirSideLeft, pix, mirZoneW, mirZoneH) {
		t.Fatal("授权后应写入")
	}
	if v.flushTo(mirSideRight, pix, mirZoneW, mirZoneH) {
		t.Fatal("方向与授权不符时不应写入")
	}
	if v.flushTo(mirSideNone, pix, mirZoneW, mirZoneH) {
		t.Fatal("撤区帧不应写入")
	}
	if w.n != 1 || w.zone != left {
		t.Fatalf("写入次数或矩形不对: n=%d zone=%v", w.n, w.zone)
	}

	v.revoke()
	if v.flushTo(mirSideLeft, pix, mirZoneW, mirZoneH) {
		t.Fatal("revoke 之后不应再写入")
	}
	if w.n != 1 {
		t.Fatalf("revoke 之后发生了多余写入: n=%d", w.n)
	}
}

// TestMirDirectWriteRetarget 换侧后必须立刻改写到新矩形，旧方向立即失效。
func TestMirDirectWriteRetarget(t *testing.T) {
	v := &mirServer{}
	w := &fakeWriter{}
	pix := make([]byte, mirZoneW*mirZoneH*2)

	v.grant(w, mirZoneRect(mirSideLeft), mirSideLeft)
	if !v.flushTo(mirSideLeft, pix, mirZoneW, mirZoneH) {
		t.Fatal("左侧授权后应写入")
	}

	right := mirZoneRect(mirSideRight)
	v.grant(w, right, mirSideRight)
	if v.flushTo(mirSideLeft, pix, mirZoneW, mirZoneH) {
		t.Fatal("换侧后旧方向不应再写入")
	}
	if !v.flushTo(mirSideRight, pix, mirZoneW, mirZoneH) {
		t.Fatal("换侧后新方向应写入")
	}
	if w.zone != right {
		t.Fatalf("应写到右侧矩形，实际 %v", w.zone)
	}
	if left := mirZoneRect(mirSideLeft); left.x1 != right.x0 {
		t.Fatalf("左右矩形应恰好拼接: left.x1=%.0f right.x0=%.0f", left.x1, right.x0)
	}
}

// TestMirDispatchBySrcGeom 钉死"按帧头尺寸选通道"的语义。
//
// 这两条路的代价差一个量级：960×540 是整行 memcpy，640×360 要放大 51.8 万像素。
// 选错了不会崩，只会慢——正是这种"不崩的错"最容易被忽略，所以必须测。
func TestMirDispatchBySrcGeom(t *testing.T) {
	v := &mirServer{}
	w := &fakeWriter{}
	v.grant(w, mirZoneRect(mirSideLeft), mirSideLeft)

	if !v.flushTo(mirSideLeft, make([]byte, mirZoneW*mirZoneH*2), mirZoneW, mirZoneH) {
		t.Fatal("960×540 应写入")
	}
	if w.scaled {
		t.Fatal("960×540 不应走放大通道")
	}

	if !v.flushTo(mirSideLeft, make([]byte, mirSrcW*mirSrcH*2), mirSrcW, mirSrcH) {
		t.Fatal("640×360 应写入")
	}
	if !w.scaled {
		t.Fatal("640×360 必须走放大通道")
	}
	if w.srcW != mirSrcW || w.srcH != mirSrcH {
		t.Fatalf("源尺寸没传对: %dx%d", w.srcW, w.srcH)
	}
}

// TestScale565NN 验证最近邻映射的边界与内容。
//
// 1.5× 是非整数比，最容易出的错是"最后一列/最后一行没写到"或者
// "行末少写 2 字节留残影"，所以这里既查内容也查末像素。
func TestScale565NN(t *testing.T) {
	// 2×2 源，每像素 2 字节，值就是它的序号
	src := []byte{0x00, 0x00, 0x01, 0x00, 0x02, 0x00, 0x03, 0x00}
	dw, dh, stride := 4, 4, 4
	dst := make([]byte, dw*dh*2)
	scale565NN(dst, stride, dw, dh, src, 2, 2, make([]byte, dw*2))

	want := []byte{
		0, 0, 0, 0, 1, 0, 1, 0,
		0, 0, 0, 0, 1, 0, 1, 0,
		2, 0, 2, 0, 3, 0, 3, 0,
		2, 0, 2, 0, 3, 0, 3, 0,
	}
	if string(dst) != string(want) {
		t.Fatalf("2×2→4×4 映射不符\n got=%v\nwant=%v", dst, want)
	}

	// 640×360 → 960×540：末行末列必须被写到（否则右下角留黑边）
	sw, sh := mirSrcW, mirSrcH
	s := make([]byte, sw*sh*2)
	for i := 0; i < sw*sh; i++ {
		s[i*2] = byte(i)
	}
	d := make([]byte, mirZoneW*mirZoneH*2)
	scale565NN(d, mirZoneW, mirZoneW, mirZoneH, s, sw, sh, make([]byte, mirZoneW*2))
	last := (mirZoneH-1)*mirZoneW*2 + (mirZoneW-1)*2
	if d[last] != s[(sh-1)*sw*2+(sw-1)*2] {
		t.Fatalf("右下角像素未映射: got=%d want=%d", d[last], s[(sh-1)*sw*2+(sw-1)*2])
	}

	// 源长度不足必须整帧不写，不能写半块
	d2 := make([]byte, mirZoneW*mirZoneH*2)
	scale565NN(d2, mirZoneW, mirZoneW, mirZoneH, s[:100], sw, sh, make([]byte, mirZoneW*2))
	for i, b := range d2 {
		if b != 0 {
			t.Fatalf("源不足时不应写入，偏移 %d 被写成 %d", i, b)
		}
	}
}

// TestScale565NN32_EveryPixel 逐像素校验 3:2 快路径。
//
// 快路径用 uint16 视图直接搬像素：映射错了不会崩，只会整体错位一格或者
// 高低字节互换，而后者在深色画面上几乎看不出来。所以这里既比对像素值，
// 也用 >255 的值把字节序问题顶出来。
func TestScale565NN32_EveryPixel(t *testing.T) {
	const sw, sh = 640, 360
	src := make([]byte, sw*sh*2)
	for i := 0; i < sw*sh; i++ {
		v := uint16(i*7 + 3)
		src[i*2] = byte(v)
		src[i*2+1] = byte(v >> 8)
	}
	dw, dh := 960, 540
	dst := make([]byte, dw*dh*2)
	row := make([]byte, dw*2)
	scale565NN(dst, dw, dw, dh, src, sw, sh, row)

	for y := 0; y < dh; y++ {
		sy := y * sh / dh
		for x := 0; x < dw; x++ {
			sx := x * sw / dw
			got := uint16(dst[(y*dw+x)*2]) | uint16(dst[(y*dw+x)*2+1])<<8
			want := uint16(sy*sw+sx)*7 + 3
			if got != want {
				t.Fatalf("(%d,%d) 期望 %d 实际 %d（源列 %d）", x, y, want, got, sx)
			}
		}
	}
}

// TestScale565NN32_NotAppliedWhenRatioOff 保证比例不是 3:2 时不走快路径也不会错。
func TestScale565NN32_NotAppliedWhenRatioOff(t *testing.T) {
	// 500×300 → 960×540 不是 3:2，走通用路径
	src := make([]byte, 500*300*2)
	for i := 0; i < 500*300; i++ {
		src[i*2] = byte(i)
		src[i*2+1] = byte(i >> 8)
	}
	dst := make([]byte, 960*540*2)
	row := make([]byte, 960*2)
	scale565NN(dst, 960, 960, 540, src, 500, 300, row)
	if dst[0] != src[0] || dst[1] != src[1] {
		t.Fatalf("左上角应映射到源首像素")
	}
	last := (540-1)*960*2 + (960-1)*2
	if dst[last] != src[(299*500+499)*2] {
		t.Fatalf("右下角映射错误")
	}
}
