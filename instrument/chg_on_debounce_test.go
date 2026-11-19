package main

// Issue #7：控制视频模式进出的充电判定去抖（chgOnDebounce）单测。
// 核心是"进快出慢 + 数据不可信时保持"，防止 chg.on 抖动误退视频模式
// 造成近 6 秒渲染阻塞 + 黑闪。纯逻辑，不碰 DBus / fb1。

import "testing"

// feed 连喂 n 次同一个 raw，返回最后一次结果
func feed(d *chgOnDebounce, raw bool, n int) bool {
	v := false
	for i := 0; i < n; i++ {
		v = d.step(raw, chgOnUpN, chgOnDownN)
	}
	return v
}

func TestChgOnDebounceEnterNeedsTwo(t *testing.T) {
	var d chgOnDebounce
	if d.step(true, chgOnUpN, chgOnDownN) {
		t.Fatal("第 1 轮 true 不应进入（需连续 2 轮）")
	}
	if !d.step(true, chgOnUpN, chgOnDownN) {
		t.Fatal("第 2 轮 true 应进入")
	}
}

func TestChgOnDebounceExitNeedsTen(t *testing.T) {
	var d chgOnDebounce
	feed(&d, true, chgOnUpN) // 先进入
	// step 返回"是否仍在充电中"：true=保持（未退出），false=已退出
	for i := 1; i < chgOnDownN; i++ {
		if got := d.step(false, chgOnUpN, chgOnDownN); !got {
			t.Fatalf("第 %d 轮 false 不应退出（需连续 %d 轮）", i, chgOnDownN)
		}
	}
	// 第 chgOnDownN 轮 false 才退出
	if d.step(false, chgOnUpN, chgOnDownN) {
		t.Fatalf("第 %d 轮 false 应退出", chgOnDownN)
	}
}

func TestChgOnDebounceSingleJitterNoChange(t *testing.T) {
	var d chgOnDebounce
	feed(&d, true, chgOnUpN) // 稳定在充电中
	// 单次抖动：1 轮 false 不足以退出，紧接 true 应清零计数
	d.step(false, chgOnUpN, chgOnDownN)
	if !d.step(true, chgOnUpN, chgOnDownN) {
		t.Fatal("单次抖动后应仍在充电中")
	}
	// 再来几次间歇 false，都不该退出
	for i := 0; i < 5; i++ {
		d.step(false, chgOnUpN, chgOnDownN)
		d.step(true, chgOnUpN, chgOnDownN)
	}
	if !d.step(true, chgOnUpN, chgOnDownN) {
		t.Fatal("间歇抖动累积不应误退出")
	}
}

func TestChgOnDebounceUntrustedKeepsLast(t *testing.T) {
	var d chgOnDebounce
	feed(&d, true, chgOnUpN) // 进入充电中
	// up==down==0 代表双路 DBus 都读不到：无论喂多少次都应保持上次值
	for i := 0; i < 50; i++ {
		if got := d.step(false, 0, 0); !got {
			t.Fatalf("数据不可信时第 %d 次不应翻转到未充电", i+1)
		}
	}
	d.reset()
	if d.on {
		t.Fatal("reset 后应为未充电")
	}
}

func TestChgOnDebounceAsymmetric(t *testing.T) {
	if chgOnUpN >= chgOnDownN {
		t.Fatalf("必须进快出慢：up=%d down=%d", chgOnUpN, chgOnDownN)
	}
	if chgOnDownN < 5 {
		t.Fatalf("退出阈值 %d 太小，扛不住 DBus 偶发超时连读 false", chgOnDownN)
	}
}
