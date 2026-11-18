package main

import (
	"image/color"
	"testing"
)

// 歌词拖动位置：默认值、出界吸附、三种样式整体平移且不越界、不影响底部布局。
func TestLyricPosDefaultsAndClamp(t *testing.T) {
	c := defaultConfig()
	if c.LyricPos.DX != 0 || c.LyricPos.DY != 0 {
		t.Fatalf("默认歌词位置应为 0/0，实际 %+v", c.LyricPos)
	}

	cases := []struct {
		inX, inY     float64
		wantX, wantY float64
	}{
		{100, 200, 100, 200}, // 合法值原样保留
		{-30, -50, 0, 0},     // 负越界吸到左上
		{9999, 9999, frameW - lyricBoxW, frameH - lyricBoxH},                             // 正越界吸到右下
		{frameW - lyricBoxW, frameH - lyricBoxH, frameW - lyricBoxW, frameH - lyricBoxH}, // 边界内
	}
	for _, tc := range cases {
		c.LyricPos.DX, c.LyricPos.DY = tc.inX, tc.inY
		c.normalize()
		if c.LyricPos.DX != tc.wantX || c.LyricPos.DY != tc.wantY {
			t.Errorf("输入 (%g,%g) 期望 (%g,%g)，实际 (%g,%g)",
				tc.inX, tc.inY, tc.wantX, tc.wantY, c.LyricPos.DX, c.LyricPos.DY)
		}
	}
}

// 偏移只作用于左上角歌词块：设置偏移后，同帧其它组件（红绿灯/车模外的底栏）不受影响。
// 这里用一个最小 snapshot 验证 paintLyric 结束后 canvas 偏移已归零（defer 生效）。
func TestLyricPosOffsetIsScoped(t *testing.T) {
	cfg := defaultConfig()
	cfg.LyricPos.DX, cfg.LyricPos.DY = 300, 250
	s := snapshot{}
	s.lyricOK = true
	s.lyric.playing = true
	s.lyric.cur = "测试歌词"

	cv := newCanvas()
	paintLyric(cv, cfg, s, false, func(x, y float64) (float64, float64) { return x, y })
	if cv.ox != 0 || cv.oy != 0 {
		t.Fatalf("paintLyric 结束后偏移应归零，实际 ox=%g oy=%g", cv.ox, cv.oy)
	}
	// 归零后画一个矩形，位置不受歌词偏移影响
	cv.fillRoundRect(10, 10, 20, 20, 0, color.RGBA{255, 255, 255, 255})
	if cv.dirty.x0 != 10 || cv.dirty.y0 != 10 {
		t.Fatalf("歌词偏移泄漏到后续绘制，dirty=%+v", cv.dirty)
	}
}

// 底部布局不受歌词位置影响：LyricBottom 打开时 DX/DY 不改变绘制结果。
func TestLyricBottomIgnoresPos(t *testing.T) {
	cfg := defaultConfig()
	cfg.Features.LyricBottom = true
	s := snapshot{}
	s.lyricOK = true
	s.lyric.playing = true
	s.lyric.cur = "测试歌词"

	a := newCanvas()
	cfg.LyricPos.DX, cfg.LyricPos.DY = 0, 0
	paintLyric(a, cfg, s, false, func(x, y float64) (float64, float64) { return x, y })

	b := newCanvas()
	cfg.LyricPos.DX, cfg.LyricPos.DY = 400, 300
	paintLyric(b, cfg, s, false, func(x, y float64) (float64, float64) { return x, y })

	for i := range a.img.Pix {
		if a.img.Pix[i] != b.img.Pix[i] {
			t.Fatal("底部歌词布局不应受 LyricPos 影响")
		}
	}
}
