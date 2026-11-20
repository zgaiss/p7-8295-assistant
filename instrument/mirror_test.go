package main

// 打灯影像的纯逻辑单测：协议往返、尺寸校验、区域几何、脏矩形减法。
//
// 重点在 TestRectMinus*：影像区能"不花屏"的数学前提是
// 「boxBlit 减去影像区后，剩下的每一块都不与影像区相交」，
// 且减去前后面积守恒。这两条一旦破了，表现就是影像被擦掉或留残影，
// 而且很难在实车上定位，所以必须用测试钉死。

import (
	"bytes"
	"testing"
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
		{Ver: mirVersion, W: 640, H: 360, CLen: 100, ULen: 640 * 360 * 2},           // 尺寸不是 960×540
		{Ver: mirVersion, W: mirZoneW, H: mirZoneH, CLen: 100, ULen: 100},           // ulen 不是 w*h*2
		{Ver: mirVersion, W: mirZoneW, H: mirZoneH, CLen: 0, ULen: mirZoneW * mirZoneH * 2},                 // clen=0
		{Ver: mirVersion, W: mirZoneW, H: mirZoneH, CLen: mirZoneW*mirZoneH*2 + 1, ULen: mirZoneW * mirZoneH * 2}, // clen 超过裸帧
	}
	for i, h := range bad {
		if err := h.ok(); err == nil {
			t.Fatalf("非法头 #%d 应当拒绝：%+v", i, h)
		}
	}
	if err := good.ok(); err != nil {
		t.Fatalf("合法头被拒: %v", err)
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
