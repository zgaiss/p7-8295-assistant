package main

// 车机端 ↔ 仪表端的红绿灯字段契约测试：
// 把车机端 TlSource/PushService 实际写出的 ext.json 片段喂给仪表端，
// 验证字段名、nxtMs 语义（下一相位的时长，不是结束时刻）与 tlFromExt 的换算都对得上。
// 期望值来自 analysis/tl_app/TlCheck.java 对真实日志的解析结果。

import (
	"os"
	"path/filepath"
	"testing"
	"time"
)

func TestTLJsonContract(t *testing.T) {
	// 来自 analysis/tl_app/TlCheck.java 对真实/夹具日志的输出
	payload := `{"ts":1794065847100,"tl":{"ret":0,"distM":90,"lim":40,"phases":[` +
		`{"dir":1,"cur":3,"remainMs":7400,"nxt":2,"nxtMs":20000},` +
		`{"dir":2,"cur":2,"remainMs":19861,"nxt":0,"nxtMs":0}]}}`
	dir := t.TempDir()
	p := filepath.Join(dir, "ext.json")
	if err := os.WriteFile(p, []byte(payload), 0644); err != nil {
		t.Fatal(err)
	}
	e, age := loadExt(p, 120*time.Second)
	if e.TL == nil {
		t.Fatal("ext.tl 没解析出来（字段名对不上）")
	}
	if e.TL.Ret != 0 || len(e.TL.Phases) != 2 || e.TL.DistM != 90 || e.TL.Lim != 40 {
		t.Fatalf("tl 头字段不对: %+v", *e.TL)
	}
	tl, ok := tlFromExt(e.TL, age)
	if !ok {
		t.Fatal("tlFromExt 判为无相位")
	}
	if got := tl.lights[0]; got.cur != tlYellow || got.dir != tlDirLeft || got.sec != 7 {
		t.Fatalf("左转应黄 7s，实际 %+v", got)
	}
	if got := tl.lights[1]; got.cur != tlGreen || got.dir != tlDirStraight || got.sec != 19 {
		t.Fatalf("直行应绿 19s，实际 %+v", got)
	}

	// 当前相位走完 1 秒后：应自动切到 nxt，且用 nxtMs 的“时长”语义续上
	tl2, ok := tlFromExt(e.TL, age+1200*time.Millisecond)
	if !ok {
		t.Fatal("跨相位时被判成无相位")
	}
	if got := tl2.lights[1]; got.cur != tlGreen || got.sec != 18 {
		t.Fatalf("直行绿灯应继续走秒到 18s，实际 %+v", got)
	}

	// 全清空包（车机失联 / 停车）：必须判为不画
	var empty ExtData
	os.WriteFile(p, []byte(`{"ts":1,"tl":{"ret":2,"distM":0,"lim":0,"phases":[]}}`), 0644)
	empty, _ = loadExt(p, 120*time.Second)
	if _, ok := tlFromExt(empty.TL, 0); ok {
		t.Fatal("ret=2 时应判为无相位")
	}
}
