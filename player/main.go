//go:build linux

package main

// p7vplay —— 仪表本地充电动画播放器（Go 版，读 P7LZ 帧包）。
//
// 为什么要这一版：原来的 C 播放器每帧要 JPEG 解码（20~34ms）+ 缩放转 565（18~28ms），
// 这两段把帧周期顶到 40~60ms。P7LZ 帧包把画面在 PC 上预先转成 RGB565 再 LZ4 压缩，
// 仪表端只剩「解压（约 1ms）+ 2 倍整数复制 + 写屏」，把解码从关键路径上摘掉。
//
// 它不是银弹：写屏 2.64MB 的耗时一分不变，单缓冲撕裂也照旧。这个版本的用途
// 就是把「解码到底占多少」这件事量出来——日志里的 dec/ren/wri 三段与 C 版同格式。
//
// 帧包格式（全部小端）：
//   "P7LZ" | ver=1 | count | w | h | fps | (clen + LZ4 block) × count
// 每帧解压后固定 w*h*2 字节 RGB565；w×h 是源尺寸（960×360），播放时 2 倍复制到 1920×720。
//
// 命令行与 C 版保持一致，便于 daemon 无差别拉起：
//   --pack <path> --fps <n> --overlay <jpg> --verbose --write pwrite|mmap --bench <n>

import (
	"bytes"
	"errors"
	"flag"
	"fmt"
	"image/jpeg"
	"log"
	"os"
	"os/signal"
	"sync"
	"syscall"
	"time"
	"unsafe"

	"github.com/pierrec/lz4/v4"
)

var lg = log.New(os.Stderr, "", log.LstdFlags)

const fbioGetFixScreeninfo = 0x4602

// overlay 文字层：灰度 JPEG 的亮度当 alpha，把白色叠到左上角（与 C 版规则一致）
type overlay struct {
	mu     sync.RWMutex
	mask   []uint8
	mw     int
	mh     int
	rowX0  []int // 每行第一个非透明像素
	rowX1  []int // 每行最后一个非透明像素 +1
}

func (o *overlay) load(path string) {
	b, err := os.ReadFile(path)
	if err != nil {
		return
	}
	img, err := jpeg.Decode(bytes.NewReader(b))
	if err != nil {
		return
	}
	bnd := img.Bounds()
	mw, mh := bnd.Dx(), bnd.Dy()
	mask := make([]uint8, mw*mh)
	for y := 0; y < mh; y++ {
		for x := 0; x < mw; x++ {
			r, g, bb, _ := img.At(bnd.Min.X+x, bnd.Min.Y+y).RGBA()
			// 灰度图三个通道相同，取平均即可
			mask[y*mw+x] = uint8(((r >> 8) + (g >> 8) + (bb >> 8)) / 3)
		}
	}
	// 预先算出每行真正有内容的 x 区间：文字其实只占左上一小片，
	// 全宽 760 逐像素扫一遍纯属浪费，而这段每帧都要跑。
	x0 := make([]int, mh)
	x1 := make([]int, mh)
	for y := 0; y < mh; y++ {
		a, b := 0, 0
		for x := 0; x < mw; x++ {
			if mask[y*mw+x] >= 12 {
				if a == 0 && b == 0 {
					a = x
				}
				b = x + 1
			}
		}
		x0[y], x1[y] = a, b
	}
	o.mu.Lock()
	o.mask, o.mw, o.mh = mask, mw, mh
	o.rowX0, o.rowX1 = x0, x1
	o.mu.Unlock()
}

func (o *overlay) watch(path string, stop <-chan struct{}) {
	var last time.Time
	for {
		select {
		case <-stop:
			return
		case <-time.After(300 * time.Millisecond):
		}
		st, err := os.Stat(path)
		if err != nil || st.ModTime().Equal(last) {
			continue
		}
		last = st.ModTime()
		o.load(path)
	}
}

func (o *overlay) composite(dst []byte) {
	o.mu.RLock()
	mask, mw, mh := o.mask, o.mw, o.mh
	rowX0, rowX1 := o.rowX0, o.rowX1
	o.mu.RUnlock()
	if mask == nil {
		return
	}
	w, h := mw, mh
	if w > 760 {
		w = 760
	}
	if h > 540 {
		h = 540
	}
	for y := 0; y < h; y++ {
		xs, xe := rowX0[y], rowX1[y]
		if xs >= xe {
			continue // 整行透明，直接跳过
		}
		if xe > w {
			xe = w
		}
		mrow := mask[y*mw : y*mw+xe]
		drow := dst[y*fbW*2:]
		for x := xs; x < xe; x++ {
			a := int(mrow[x])
			if a < 12 {
				continue
			}
			i := x * 2
			v := uint16(drow[i]) | uint16(drow[i+1])<<8
			r := int((v >> 11) & 31)
			g := int((v >> 5) & 63)
			b := int(v & 31)
			r = (r*(255-a) + 31*a) / 255
			g = (g*(255-a) + 63*a) / 255
			b = (b*(255-a) + 31*a) / 255
			n := uint16((r << 11) | (g << 5) | b)
			drow[i] = byte(n)
			drow[i+1] = byte(n >> 8)
		}
	}
}

func main() {
	var (
		packPath = flag.String("pack", "/tmp/charge.pack", "帧包路径")
		fpsFlag  = flag.Int("fps", 0, "播放帧率，0=用包头里的")
		ovPath   = flag.String("overlay", "", "文字层 JPEG 路径")
		verbose  = flag.Bool("verbose", false, "输出详细日志")
		write    = flag.String("write", "pwrite", "写屏方式 pwrite|mmap")
		bench    = flag.Int("bench", 0, "只跑 N 帧后退出")
		vsyncOpt = flag.String("vsync", "on", "写屏前是否等垂直同步 on|off（off 更容易跑满帧，但撕裂位置随机）")
	)
	flag.Parse()

	p, err := loadPack(*packPath)
	if err != nil {
		lg.Printf("load pack failed: %v", err)
		os.Exit(2)
	}
	fps := *fpsFlag
	if fps <= 0 {
		fps = p.fps
	}
	if fps < 1 || fps > 60 {
		fps = 24
	}
	lg.Printf("pack: %d frames %dx%d %dfps (%.0f KB)", p.count, p.w, p.h, p.fps, float64(len(p.data))/1024)

	stop := make(chan struct{})
	sigc := make(chan os.Signal, 1)
	signal.Notify(sigc, syscall.SIGTERM, syscall.SIGINT)
	go func() {
		<-sigc
		close(stop)
	}()

	// 文字层
	ov := &overlay{}
	if *ovPath != "" {
		ov.load(*ovPath)
		go ov.watch(*ovPath, stop)
	}

	// fb1：几何不对才重设（约 4.4 秒，只在这一种情况下付这个代价）
	if !fbGeomOK() {
		lg.Printf("fb1 几何不对（stride=%s），重新设置 1920x720", readSys("stride"))
		if err := setMode(lg); err != nil {
			lg.Printf("setMode 失败: %v", err)
			os.Exit(2)
		}
	} else if *verbose {
		lg.Printf("fb1 已是 1920x720（stride=%s），跳过 setMode", readSys("stride"))
	}
	fb, err := fb1Open(lg)
	if err != nil {
		lg.Printf("open fb1: %v", err)
		os.Exit(2)
	}
	defer fb1Restore(fb)

	// 写屏目标：pwrite 走 fd，mmap 走映射
	var fbmap []byte
	if *write == "mmap" {
		var fix fbFixScreeninfo
		if err := ioctl(fb.f.Fd(), fbioGetFixScreeninfo, unsafe.Pointer(&fix)); err != nil {
			lg.Printf("FBIOGET_FSCREENINFO 失败: %v，退回 pwrite", err)
		} else if m, err := syscall.Mmap(int(fb.f.Fd()), 0, int(fix.SmemLen),
			syscall.PROT_READ|syscall.PROT_WRITE, syscall.MAP_SHARED); err != nil {
			lg.Printf("mmap 失败: %v，退回 pwrite", err)
		} else {
			fbmap = m
			lg.Printf("fb mmap ok smem=%d", fix.SmemLen)
			defer syscall.Munmap(m)
		}
	}

	// vsync 探测：结果打进日志，车机「刷新状态」会读这一行
	vsyncErr := waitVsync(fb)
	if vsyncErr == nil {
		lg.Printf("vsync ioctl=0 errno=0")
	} else {
		var en syscall.Errno
		if errors.As(vsyncErr, &en) {
			lg.Printf("vsync ioctl=-1 errno=%d", int(en))
		} else {
			lg.Printf("vsync ioctl=-1 errno=0")
		}
	}

	useVsync := vsyncErr == nil && *vsyncOpt != "off"
	if vsyncErr == nil && *vsyncOpt == "off" {
		lg.Printf("vsync 已按参数关闭（--vsync off）")
	}

	// 流水线：主循环只负责「解压 + 放大 + 叠字」，写屏交给独立的写屏 goroutine。
	//
	// 为什么必须拆：写一帧 2.64MB 要 37~46ms，串行做的话这段时间 CPU 完全空转，
	// 帧周期 = 准备 + 写屏 ≈ 55ms，实测就是 18fps。拆开之后周期只取两者较慢的一段，
	// 写屏期间主循环已经把下一帧准备好，帧率上限由写屏决定（约 24fps）。
	nbuf := 3
	raws := make([][]byte, nbuf) // 每块独立的解压缓冲，避免和正在写的帧抢内存
	for i := range raws {
		raws[i] = make([]byte, p.rawLen)
	}
	jobs := make(chan []byte, nbuf) // 准备好、等着写屏的帧
	free := make(chan []byte, nbuf) // 已写完、可复用的缓冲
	for i := 0; i < nbuf; i++ {
		b := make([]byte, fbW*fbH*2)
		free <- b
	}

	var (
		stMu           sync.Mutex
		wriMs          float64
		drops      int
	)

	stopWriter := make(chan struct{})
	var wgWriter sync.WaitGroup
	wgWriter.Add(1)
	go func() {
		defer wgWriter.Done()
		for {
			select {
			case <-stopWriter:
				return
			case buf := <-jobs:
				// 写前等一次垂直同步：让整帧写入从刷新起点开始，
				// 撕裂线不会随写屏时机乱跳（写不完时撕在固定位置，比随机闪好认）。
				// 代价是周期会被量化到 16.7ms 的倍数，写不满时可能掉到 20fps 附近。
				if useVsync {
					waitVsync(fb)
				}
				t0 := time.Now()
				var werr error
				if fbmap != nil {
					copy(fbmap, buf)
				} else {
					_, werr = fb.f.WriteAt(buf, 0)
				}
				stMu.Lock()
				wriMs = float64(time.Since(t0).Microseconds()) / 1000.0
				if werr != nil {
					lg.Printf("写屏失败: %v", werr)
				}
				stMu.Unlock()
				// 归还缓冲：主循环可能已经退出，这里不能阻塞
				select {
				case free <- buf:
				default:
				}
			}
		}
	}()
	defer func() {
		close(stopWriter)
		wgWriter.Wait()
	}()

	period := time.Second / time.Duration(fps)
	next := time.Now()
	total, frames, decSum, renSum := 0, 0, 0.0, 0.0
	statAt := time.Now()

	for i := 0; ; i++ {
		idx := i % p.count
		select {
		case <-stop:
			lg.Printf("收到退出信号")
			return
		default:
		}

		var out []byte
		select {
		case out = <-free:
		case <-stop:
			lg.Printf("收到退出信号")
			return
		}
		raw := raws[i%nbuf]

		t0 := time.Now()
		src := p.data[p.off[idx] : p.off[idx]+p.clen[idx]]
		if n, err := lz4.UncompressBlock(src, raw); err != nil || n != p.rawLen {
			lg.Printf("解压失败 frame=%d n=%d err=%v", idx, n, err)
			return
		}
		t1 := time.Now()
		render2x(raw, out, p.w, p.h, fbW)
		ov.composite(out)
		t2 := time.Now()

		frames++
		total++
		decSum += float64(t1.Sub(t0).Microseconds()) / 1000.0
		renSum += float64(t2.Sub(t1).Microseconds()) / 1000.0

		select {
		case jobs <- out:
		default:
			// 写屏跟不上、通道已满：丢掉这一帧，别让主循环阻塞。
			// 画面会跳一帧，但比整体节奏被拖慢好。
			drops++
			free <- out
		}

		stMu.Lock()
		curWri := wriMs
		stMu.Unlock()

		if time.Since(statAt) >= 2*time.Second {
			dt := time.Since(statAt).Seconds()
			fmt.Printf("fps=%.1f dec=%.1fms ren=%.1fms wri=%.1fms frames=%d\n",
				float64(frames)/dt, decSum/float64(frames), renSum/float64(frames), curWri, frames)
			os.Stdout.Sync()
			frames, decSum, renSum, statAt = 0, 0, 0, time.Now()
		}
		if *bench > 0 && total >= *bench {
			return
		}

		next = next.Add(period)
		if d := time.Until(next); d > 0 {
			time.Sleep(d)
		} else {
			next = time.Now()
		}
	}
}
