package main

// P7 仪表助手 · 原生版 —— 直接跑在仪表上的前景层小程序
//
//	p7assist daemon              常驻：DBus 采集 + 绘制 + 写 fb1
//	p7assist render <p|d0|d1> [night] [out.png]   本地渲染预览图（PC 上可直接跑）
//	p7assist dump                打印当前 DBus 读到的值
//	p7assist status              打印 status.json
//	p7assist version

import (
	"fmt"
	"image"
	"image/color"
	"image/draw"
	"image/png"
	"os"
	"path/filepath"
	"runtime/debug"
	"strings"
	"time"

	xdraw "golang.org/x/image/draw"

	"github.com/godbus/dbus/v5"
)

func main() {
	if len(os.Args) < 2 {
		usage()
		return
	}
	switch os.Args[1] {
	case "daemon":
		daemonMainSafe()
	case "render":
		mode := "p"
		if len(os.Args) > 2 {
			mode = os.Args[2]
		}
		night := false
		out := "preview.png"
		for _, a := range os.Args[3:] {
			if a == "night" || a == "day" {
				night = a == "night"
			} else if strings.HasSuffix(a, ".png") {
				out = a
			}
		}
		renderPreview(mode, night, out)
	case "cleanup":
		cleanupCmd()
	case "compose":
		// compose <底图.png> <输出.png> [1|2|3] [day|night]：本地预览歌词样式
		if len(os.Args) < 4 {
			fmt.Println("用法: p7assist compose <底图.png> <输出.png> [样式1-3] [day|night]")
			return
		}
		style := 2
		night := true
		for _, a := range os.Args[4:] {
			if a == "day" {
				night = false
			} else if a == "night" {
				night = true
			} else if len(a) == 1 && a[0] >= '1' && a[0] <= '3' {
				style = int(a[0] - '0')
			}
		}
		lyricStyleMode = style
		composePreview(os.Args[2], os.Args[3], night)
	case "tl":
		// tl [输出.png] [native|icon] [day|night]：预览红绿灯小件
		out := "tl-preview.png"
		style := ""
		night := true
		for _, a := range os.Args[2:] {
			switch {
			case a == "day":
				night = false
			case a == "night":
				night = true
			case a == "native" || a == "icon":
				style = a
			case strings.HasSuffix(a, ".png"):
				out = a
			}
		}
		tlPreview(out, style, night)
	case "compose-tl":
		// compose-tl <底图.png> <输出.png> [native|icon] [day|night]
		if len(os.Args) < 4 {
			fmt.Println("用法: p7assist compose-tl <底图.png> <输出.png> [native|icon] [day|night]")
			return
		}
		style := ""
		night := true
		for _, a := range os.Args[4:] {
			switch a {
			case "day":
				night = false
			case "night":
				night = true
			case "native", "icon":
				style = a
			}
		}
		composeTLPreview(os.Args[2], os.Args[3], style, night)
	case "tl-strip":
		// tl-strip [输出.png] [native|icon] [day|night]：时间序列对照
		out := "tl-strip.png"
		style := ""
		night := true
		for _, a := range os.Args[2:] {
			switch {
			case a == "day":
				night = false
			case a == "night":
				night = true
			case a == "native" || a == "icon":
				style = a
			case strings.HasSuffix(a, ".png"):
				out = a
			}
		}
		tlStrip(out, style, night)
	case "dump":
		dumpCmd()
	case "status":
		b, err := os.ReadFile(statusPathForPrint())
		if err != nil {
			fmt.Println("读取失败:", err)
			return
		}
		fmt.Println(string(b))
	case "version", "-v", "--version":
		fmt.Printf("p7assist %s (%s)\n", appVersion, buildTime)
	default:
		usage()
	}
}

func usage() {
	fmt.Println(`p7assist —— P7 仪表助手·原生版
  daemon                          常驻：采集 + 绘制 + 写 fb1
  render <p|d0|d1> [night] [png]  本地渲染预览（p=驻车, d0=舒展, d1=紧凑）
  tl [png] [native|icon] [day|night]
                                  红绿灯预览：上半整屏看位置，下半 4 倍放大看细节
  tl-strip [png] [native|icon]    红绿灯时间序列（走秒 / 绿转黄 / 撤掉）
  compose <底图.png> <输出.png> [1|2|3] [day|night]
                                  把歌词叠到真实底图上比对样式
  compose-tl <底图.png> <输出.png> [native|icon] [day|night]
                                  把红绿灯叠到真实底图上
  dump                            打印 DBus 当前值
  cleanup                         清屏并关闭前景层（看门狗兜底用）
  status                          打印 status.json
  version                         版本`)
}

func statusPathForPrint() string {
	appDir()
	return statusPath
}

// sampleSnapshot 预览用示例数据（与实车无关）
func sampleSnapshot() snapshot {
	var s snapshot
	bars := [4]float64{2.45, 2.44, 2.38, 2.40}
	temps := [4]float64{23, 24, 22, 22}
	for i := 0; i < 4; i++ {
		s.wheels[i] = wheelData{bar: bars[i], barOK: true, temp: temps[i], tempOK: true}
	}
	s.gear, s.gearOK = 4, true
	s.speed, s.speedOK = 0, true
	s.cabin, s.cabinOK = 23, true
	s.tripKm, s.tripMin, s.tripAvg, s.tripKwh, s.tripOK = 128.4, 96, 32, 13.5, true
	s.avgOK = true
	s.bsdL, s.bsdR = true, false
	s.volt, s.voltOK = 12.4, true
	s.nav = navInfo{distM: 12800, timeS: 840, arrive: "09:10"}
	s.navOK = true
	s.busOK = true
	return s
}

func renderPreview(mode string, night bool, out string) {
	cfg := defaultConfig()
	cfg.Features.Volt = true
	cfg.Features.Cabin = false
	cfg.Features.Debug = mode == "debug"
	s := sampleSnapshot()
	park := true
	t := 0.0
	switch mode {
	case "p":
		park, t = true, 0
	case "d0":
		park, t = false, 0
	case "d1":
		park, t = false, 1
	case "d5":
		park, t = false, 0.5
	case "debug":
		park, t = true, 0
	}
	if !park {
		s.gear, s.speed = 1, 80
	}
	switch mode {
	case "debug":
		s.navAgeMs = 1200
		s.extAgeMs = 900
	case "empty":
		s = snapshot{busOK: true, gear: 4, gearOK: true}
		s.voltOK, s.navOK, s.tripOK = false, false, false
		for i := 0; i < 4; i++ {
			s.wheels[i] = wheelData{}
		}
	case "partial":
		s.wheels[2] = wheelData{}
		s.wheels[3] = wheelData{}
		s.navOK = false
		s.tripMin = 0
	case "wide":
		s.tripKm, s.tripMin, s.tripAvg, s.tripKwh = 1234.5, 600, 120, 25.5
		s.nav = navInfo{distM: 1999000, timeS: 86000, arrive: "23:59"}
		s.volt = 14.2
	}
	cv := newCanvas()
	paintFrame(cv, cfg, s, park, t, night)

	bg := color.RGBA{0x14, 0x18, 0x1D, 0xFF}
	if !night {
		bg = color.RGBA{0xE9, 0xED, 0xF2, 0xFF}
	}
	dst := image.NewRGBA(cv.img.Bounds())
	draw.Draw(dst, dst.Bounds(), image.NewUniform(bg), image.Point{}, draw.Src)
	draw.Draw(dst, dst.Bounds(), cv.img, image.Point{}, draw.Over)

	f, err := os.Create(out)
	if err != nil {
		fmt.Println("创建失败:", err)
		return
	}
	defer f.Close()
	if err := png.Encode(f, dst); err != nil {
		fmt.Println("编码失败:", err)
		return
	}
	d := cv.dirty.clamp()
	fmt.Printf("已生成 %s  尺寸=%dx%d  dirty=(%.0f,%.0f)-(%.0f,%.0f) %.0fx%.0f\n",
		out, frameW, frameH, d.x0, d.y0, d.x1, d.y1, d.x1-d.x0, d.y1-d.y0)
}

// lyricSample 预览用的示例歌词（取自实车播放的一首歌）
func lyricSample() lyricInfo {
	li := lyricInfo{
		cur:     "铜镜映无邪 扎马尾",
		next:    "你若撒野 今生我把酒奉陪",
		title:   "发如雪",
		artist:  "周杰伦",
		playing: true,
	}
	// 预览时可用环境变量替换，方便验证超长歌词/缺字段等边界情况
	if v := os.Getenv("P7_LYRIC_CUR"); v != "" {
		li.cur = v
	}
	if v, ok := os.LookupEnv("P7_LYRIC_NEXT"); ok {
		li.next = v
	}
	if v, ok := os.LookupEnv("P7_LYRIC_TITLE"); ok {
		li.title = v
	}
	if v, ok := os.LookupEnv("P7_LYRIC_ARTIST"); ok {
		li.artist = v
	}
	// 预览暂停态：P7_LYRIC_PLAY=0 时歌词应一个像素都不画
	if os.Getenv("P7_LYRIC_PLAY") == "0" {
		li.playing = false
	}
	return li
}

// composePreview 把歌词块叠到一张真实底图上，用来比对样式效果。
// 只画歌词，不画胎压/导航等其它元素，避免干扰判断。
func composePreview(bgPath, out string, night bool) {
	f, err := os.Open(bgPath)
	if err != nil {
		fmt.Println("打开底图失败:", err)
		return
	}
	defer f.Close()
	bg, err := png.Decode(f)
	if err != nil {
		fmt.Println("解码底图失败:", err)
		return
	}

	cfg := defaultConfig()
	cfg.Features.Lyric = true
	s := sampleSnapshot()
	s.lyric = lyricSample()
	s.lyricOK = true

	cv := newCanvas()
	paintLyric(cv, cfg, s, night, nil)

	dst := image.NewRGBA(bg.Bounds())
	draw.Draw(dst, dst.Bounds(), bg, image.Point{}, draw.Src)
	draw.Draw(dst, dst.Bounds(), cv.img, image.Point{}, draw.Over)

	of, err := os.Create(out)
	if err != nil {
		fmt.Println("创建失败:", err)
		return
	}
	defer of.Close()
	if err := png.Encode(of, dst); err != nil {
		fmt.Println("编码失败:", err)
		return
	}
	fmt.Printf("已生成 %s  样式=%d 夜间=%v\n", out, lyricStyleMode, night)
}

// ---------- 红绿灯预览 ----------

// tlDemo 预览用示例灯态：左转红灯 45s、直行绿灯 20s。
// 可用环境变量覆盖，方便验证三位数、黄灯、以及只有一个相位（另一个设 off）：
//
//	P7_TL_LEFT=r45  P7_TL_STRAIGHT=g8  P7_TL_LEFT=off
func tlDemo() tlInfo {
	var t tlInfo
	t.distM = 68
	t.lights[0] = tlLight{dir: tlDirLeft, cur: tlRed, sec: 45}
	t.lights[1] = tlLight{dir: tlDirStraight, cur: tlGreen, sec: 20}
	if v := os.Getenv("P7_TL_LEFT"); v != "" {
		t.lights[0] = parseTLLight(tlDirLeft, v)
	}
	if v := os.Getenv("P7_TL_STRAIGHT"); v != "" {
		t.lights[1] = parseTLLight(tlDirStraight, v)
	}
	return t
}

// parseTLLight 解析 "r45" / "y3" / "g8" / "off"
func parseTLLight(dir int, v string) tlLight {
	v = strings.TrimSpace(strings.ToLower(v))
	if v == "" || v == "off" {
		return tlLight{dir: dir}
	}
	cur := 0
	switch v[0] {
	case 'r':
		cur = tlRed
	case 'y':
		cur = tlYellow
	case 'g':
		cur = tlGreen
	}
	sec := 0.0
	if len(v) > 1 {
		fmt.Sscanf(v[1:], "%f", &sec)
	}
	return tlLight{dir: dir, cur: cur, sec: sec}
}

func previewBG(night bool) color.RGBA {
	if night {
		return color.RGBA{0x14, 0x18, 0x1D, 0xFF}
	}
	return color.RGBA{0xE9, 0xED, 0xF2, 0xFF}
}

// tlPreview 生成一张对照图：上半是 1920×720 整屏（看位置），
// 下半是卡片区域放大（看细节）。
func tlPreview(out, style string, night bool) {
	cfg := defaultConfig()
	cfg.Features.TL = true
	if style != "" {
		cfg.Features.TLStyle = style
	}
	s := snapshot{}
	s.tl = tlDemo()
	s.tlOK = s.tl.lit()

	cv := newCanvas()
	paintTrafficLight(cv, cfg, s, false, night, func(x, y float64) (float64, float64) { return x, y })

	bg := previewBG(night)
	screen := image.NewRGBA(image.Rect(0, 0, frameW, frameH))
	draw.Draw(screen, screen.Bounds(), image.NewUniform(bg), image.Point{}, draw.Src)
	draw.Draw(screen, screen.Bounds(), cv.img, image.Point{}, draw.Over)

	// 放大区
	const zoom = 4
	zw, zh := int(tlCardW)*zoom, int(tlCardH)*zoom
	srcRect := image.Rect(int(tlCardX), int(tlCardY),
		int(tlCardX+tlCardW), int(tlCardY+tlCardH)).Intersect(cv.img.Bounds())
	zoomBG := image.NewRGBA(image.Rect(0, 0, zw, zh))
	draw.Draw(zoomBG, zoomBG.Bounds(), image.NewUniform(bg), image.Point{}, draw.Src)
	xdraw.CatmullRom.Scale(zoomBG, zoomBG.Bounds(), cv.img, srcRect, draw.Over, nil)

	const gap = 20
	W, H := frameW, frameH+gap+zh+gap
	dst := image.NewRGBA(image.Rect(0, 0, W, H))
	draw.Draw(dst, dst.Bounds(), image.NewUniform(color.RGBA{0x0A, 0x0C, 0x10, 0xFF}), image.Point{}, draw.Src)
	draw.Draw(dst, image.Rect(0, 0, frameW, frameH), screen, image.Point{}, draw.Src)
	ox := (frameW - zw) / 2
	draw.Draw(dst, image.Rect(ox, frameH+gap, ox+zw, frameH+gap+zh), zoomBG, image.Point{}, draw.Src)

	if err := writePNG(out, dst); err != nil {
		fmt.Println("生成失败:", err)
		return
	}
	fmt.Printf("已生成 %s  样式=%s 夜间=%v  卡片 %.0f×%.0f @ (%.0f,%.0f)\n",
		out, cfg.Features.TLStyle, night, tlCardW, tlCardH, tlCardX, tlCardY)
}

// composeTLPreview 把红绿灯叠到一张真实仪表底图上，只看它压在原厂画面上的效果。
func composeTLPreview(bgPath, out, style string, night bool) {
	f, err := os.Open(bgPath)
	if err != nil {
		fmt.Println("打开底图失败:", err)
		return
	}
	bg, err := png.Decode(f)
	f.Close()
	if err != nil {
		fmt.Println("解码底图失败:", err)
		return
	}
	cfg := defaultConfig()
	cfg.Features.TL = true
	if style != "" {
		cfg.Features.TLStyle = style
	}
	s := snapshot{}
	s.tl = tlDemo()
	s.tlOK = s.tl.lit()

	cv := newCanvas()
	paintTrafficLight(cv, cfg, s, false, night, func(x, y float64) (float64, float64) { return x, y })

	dst := image.NewRGBA(bg.Bounds())
	draw.Draw(dst, dst.Bounds(), bg, image.Point{}, draw.Src)
	draw.Draw(dst, dst.Bounds(), cv.img, image.Point{}, draw.Over)

	if err := writePNG(out, dst); err != nil {
		fmt.Println("生成失败:", err)
		return
	}
	fmt.Printf("已生成 %s  样式=%s 底图=%s\n", out, cfg.Features.TLStyle, bgPath)
}

func writePNG(path string, img image.Image) error {
	f, err := os.Create(path)
	if err != nil {
		return err
	}
	defer f.Close()
	return png.Encode(f, img)
}

// tlStrip 生成一条时间序列，验证「动」的三件事：
// 倒计时本地走秒、绿灯走完自动接黄灯、全部走完整块撤掉。
func tlStrip(out, style string, night bool) {
	// 模拟车机只推这一包：左转红 45s、直行绿 20s → 之后黄 3s
	e := &ExtTL{Ret: 0, DistM: 68, Phases: []ExtTLPhase{
		{Dir: tlDirLeft, Cur: tlRed, RemainMs: 45000},
		{Dir: tlDirStraight, Cur: tlGreen, RemainMs: 20000, Nxt: tlYellow, NxtMs: 3000},
	}}
	cfg := defaultConfig()
	cfg.Features.TL = true
	if style != "" {
		cfg.Features.TLStyle = style
	}

	offsets := []int{0, 10, 19, 20, 22, 24}
	const zoom = 2
	const gap = 14
	fw, fh := int(tlCardW)*zoom, int(tlCardH)*zoom
	cols, rows := 2, 3
	W := cols*fw + (cols-1)*gap + 2*gap
	H := rows*fh + (rows-1)*gap + 2*gap
	dst := image.NewRGBA(image.Rect(0, 0, W, H))
	draw.Draw(dst, dst.Bounds(), image.NewUniform(color.RGBA{0x0A, 0x0C, 0x10, 0xFF}), image.Point{}, draw.Src)

	bg := previewBG(night)
	for i, sec := range offsets {
		t, ok := tlFromExt(e, time.Duration(sec)*time.Second)
		s := snapshot{}
		s.tl, s.tlOK = t, ok
		cv := newCanvas()
		paintTrafficLight(cv, cfg, s, false, night, func(x, y float64) (float64, float64) { return x, y })

		cell := image.NewRGBA(image.Rect(0, 0, fw, fh))
		draw.Draw(cell, cell.Bounds(), image.NewUniform(bg), image.Point{}, draw.Src)
		srcRect := image.Rect(int(tlCardX), int(tlCardY),
			int(tlCardX+tlCardW), int(tlCardY+tlCardH)).Intersect(cv.img.Bounds())
		if ok {
			xdraw.CatmullRom.Scale(cell, cell.Bounds(), cv.img, srcRect, draw.Over, nil)
		}
		cx := gap + (i%cols)*(fw+gap)
		cy := gap + (i/cols)*(fh+gap)
		draw.Draw(dst, image.Rect(cx, cy, cx+fw, cy+fh), cell, image.Point{}, draw.Src)
	}
	if err := writePNG(out, dst); err != nil {
		fmt.Println("生成失败:", err)
		return
	}
	fmt.Printf("已生成 %s  时间点 %v 秒（第 4 格起绿灯走完 → 黄，第 6 格全撤）\n", out, offsets)
}

// dumpCmd 排障：直接打 DBus 原始值
// daemonMainSafe 包一层崩溃捕获：守护进程是无人值守跑的，panic 后如果只留在
// stderr（启动方多半重定向到 /dev/null），现场就彻底丢了——2026-11-17 充电页
// 崩了三次都是这样，只能靠猜。这里把 panic 现场写到 appDir/panic.txt。
func daemonMainSafe() {
	defer func() {
		if r := recover(); r != nil {
			msg := fmt.Sprintf("panic: %v\n\n%s", r, debug.Stack())
			_ = os.WriteFile(filepath.Join(appDir(), "panic.txt"), []byte(msg), 0644)
			fmt.Fprintln(os.Stderr, msg)
			os.Exit(2)
		}
	}()
	daemonMain()
}

func dumpCmd() {
	conn, err := connectBus()
	if err != nil {
		fmt.Println("连接失败:", err)
		return
	}
	fmt.Println("bus:", findBusAddress())
	c := &busCtx{conn: conn, obj: conn.Object(busDest, dbus.ObjectPath(busPath)), lg: dbgLog()}
	methods := []string{
		"get_host_etpms_pressure_lf", "get_host_etpms_pressure_rf", "get_host_etpms_pressure_lb", "get_host_etpms_pressure_rb",
		"get_host_etpms_temperature_lf", "get_host_etpms_temperature_rf", "get_host_etpms_temperature_lb", "get_host_etpms_temperature_rb",
		"get_host_vcu_current_gearlev", "get_host_vcu_raw_car_speed",
		"get_host_eac_temp_value", "get_host_trip_distance_time", "get_host_eavg_vehelcconsp",
		"get_host_eind_lbsd", "get_host_eind_rbsd",
		// 充电态（充电让出用）：mcu/gun 是标定过的两条状态量，st/cur 供实车对枚举
		"get_host_mcu_charging_state", "get_host_vcu_charge_gun_status",
		"get_host_echarge_status", "get_host_echarge_cur",
	}
	for _, m := range methods {
		v, err := c.callVal(m)
		fmt.Printf("%-36s %v  err=%v\n", m, v, err)
	}
}

var buildTime = func() string {
	return time.Now().Format("2006-01-02")
}()
