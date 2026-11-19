//go:build linux

package main

// 充电页"本地播放视频"模式（对照《充电动画投仪表_源码交付说明》v1.0.35 的做法）。
//
// 为什么改：逐帧从车机推 JPEG 再让 Go 解码 + 缩放 + 上屏，实测每帧
// decode 20~34ms、缩放 18~28ms、写屏 37~46ms，一帧 90ms 起步 → 最多 11fps。
// 文档里对端能做到 29~30fps 的关键不是网络，而是**帧包一次性传到仪表、本地解码播放**，
// 播放循环和网络彻底解耦。
//
// 现在的分工：
//   · 动画：/data/p7assist/p7videoplay（静态 ARM，对端交付的播放器）读 charge.pack
//     本地播放，直接写 fb1，30/24fps 由它自己的节拍控制。
//   · 文字：本进程每秒把充电数据画成 760×540 的灰度 JPEG（黑底白字），
//     播放器把亮度当 alpha 叠加在画面左上角。
//   · 互斥：播放器在跑的时候本进程**完全不碰 fb1**（不 ensureMode、不 blit、
//     不开关层），否则两边会被对方改 mode 互相打断。
//
// 播放器退出时会把 fb1 mode 改回 240x320 + blank，所以停掉之后必须重新
// ensureMode + 重开 fb，否则我们的界面就"看不见了"。

import (
	"fmt"
	"log"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"
)

const (
	videoBinName  = "p7videoplay"
	videoPackName = "charge.pack"
	videoOvName   = "chgoverlay.jpg"
	videoFpsArg   = "10"
	// 写屏方式开关文件：内容 "mmap" 走内存映射，其它/不存在走播放器默认的 pwrite
	videoWriteFile = "videowrite"
	// 垂直同步开关文件：内容 "off" 表示写屏前不等 vsync
	videoVsyncFile = "videovsync"
)

var (
	videoMu  sync.Mutex
	videoCmd *exec.Cmd
	// videoTouched 播放器"动过"fb1：它会把色键关成不透明整层，
	// 只要它跑过（不管正常退出还是被别人 kill），本进程都必须重新 setFGAttrs，
	// 否则我们的层会变成不透明黑，把原厂仪表整个盖住（踩过一次，整屏黑）。
	videoTouched bool

	// 视频模式会话状态（Issue #7）：跨轮持久，退出后带冷却，避免 chg.on 抖动
	// 造成反复退出/重进（每次退出近 6 秒阻塞 + 黑闪）。
	videoOn        bool
	videoCoolUntil time.Time

	// 最近一次写 overlay 的时间（每秒一次就够，文字不会这么快变）
	videoOvAt time.Time

	// 播放器当前内存里那份 charge.pack 的指纹（size:mtime）。
	// 播放器启动时把整个包 read() 进内存（p7videoplay.c load_pack），之后不再看磁盘，
	// 所以车机重传新包必须重启它才生效——否则用户得先「还原」再「上传」才看到新动画。
	videoPackSig string

	// 播放器当前这份启动参数（fps|write）的指纹，见 videoTuneNow
	videoTuneSig string
)

// killStaleVideo 启动时清理上一次遗留的播放器。
// 车机 App 会把仪表二进制刷回它自带的版本，升级瞬间会出现"旧 daemon 不认识
// 播放器进程"的孤儿，它继续写 fb1 就会和我们对抢，必须在开头清掉。
func killStaleVideo(lg *log.Logger) {
	for _, sig := range []string{"TERM", "KILL"} {
		c := exec.Command("sh", "-c", "killall -"+sig+" p7videoplay 2>/dev/null")
		_ = c.Run()
		time.Sleep(300 * time.Millisecond)
	}
	// killall 在没有进程时返回非 0，不代表失败，这里不判断退出码
	if b, err := exec.Command("sh", "-c", "ps w | grep p7videoplay | grep -v grep | wc -l").Output(); err == nil {
		if n := len(b); n > 0 {
			lg.Printf("视频: 启动前清理遗留播放器")
		}
	}
}

// videoUsable 播放器 + 帧包都在才启用（首次部署前自动回落到逐帧投屏）
func videoUsable(dir string) bool {
	b, err := os.Stat(filepath.Join(dir, videoBinName))
	if err != nil || b.Size() < 100000 {
		return false
	}
	p, err := os.Stat(filepath.Join(dir, videoPackName))
	return err == nil && p.Size() > 100000
}

// reapZombies 回收本进程名下的僵尸子进程。
//
// 热升级走的是 syscall.Exec（pid 不变、镜像替换），上一代进程里等待播放器的
// goroutine 随之消失；那些播放器退出后没人 waitpid，就堆成 Z 状态僵尸。
// 每次启动在清理遗留播放器之后调一次，把历史残留收干净。
func reapZombies(lg *log.Logger) {
	n := 0
	for {
		var ws syscall.WaitStatus
		pid, err := syscall.Wait4(-1, &ws, syscall.WNOHANG, nil)
		if pid <= 0 || err != nil {
			break
		}
		n++
	}
	if n > 0 {
		lg.Printf("视频: 回收 %d 个僵尸子进程", n)
	}
}

// videoFpsNow 播放帧率：可用 /data/p7assist/videofps 现场调（8~30），默认 24。
//
// fb1 是单缓冲，写整帧期间被 IPU 扫到就会撕一条。撕裂次数 ≈ 帧率 × 写屏占单帧
// 时长的比例，所以降帧率能减少"每秒撕几次"，但消不掉单次撕裂——想彻底解决只能
// 上 fb0 三缓冲翻页。这个文件就是给现场比对用的，不用重新打包。
func videoFpsNow(dir string) string {
	b, err := os.ReadFile(filepath.Join(dir, "videofps"))
	if err != nil {
		return videoFpsArg
	}
	n, err := strconv.Atoi(strings.TrimSpace(string(b)))
	if err != nil || n < 8 || n > 30 {
		return videoFpsArg
	}
	return strconv.Itoa(n)
}

// videoWriteNow 写屏方式：pwrite（默认）或 mmap。
//
// 播放器里两条路差一层开销：pwrite 每帧 2.64MB 要走系统调用 + 内核 copy_from_user，
// mmap 则是把显存直接映射进进程、用 memcpy 写。省的是那次额外拷贝，但快不快取决于
// 映射的缓存属性（write-combining 才明显快，纯 uncached 逐字节写反而可能更慢），
// 所以做成可切换、现场用播放器日志里的 wri= 对比，不靠推断。
func videoWriteNow(dir string) string {
	b, err := os.ReadFile(filepath.Join(dir, videoWriteFile))
	if err != nil {
		return ""
	}
	if strings.TrimSpace(string(b)) == "mmap" {
		return "mmap"
	}
	return ""
}

// videoStats 解析播放器日志，取最近一次的实测指标。
//
// 播放器每 2 秒打一行：
//
//	fps=12.3 dec=28.4ms ren=6.1ms wri=41.2ms frames=24
//
// 以及启动时的 vsync ioctl=0 errno=0（0 = 支持等垂直同步，-1 = 内核不支持）。
type videoStats struct {
	OK         bool
	RealFps    float64
	DecMs      float64
	RenMs      float64
	WriMs      float64
	Frames     int
	Vsync      string
	PackFrames int
	PackKB     int
	WriteMode  string
	WantFps    string
	VsyncMode  string
}

func readVideoStats(dir string) videoStats {
	var v videoStats
	v.WriteMode = videoWriteNow(dir)
	if v.WriteMode == "" {
		v.WriteMode = "pwrite"
	}
	v.WantFps = videoFpsNow(dir)
	v.VsyncMode = videoVsyncNow(dir)

	// 帧包信息：头 24 字节，偏移 8 起 count/w/h/fps
	if f, err := os.Open(filepath.Join(dir, videoPackName)); err == nil {
		var h [24]byte
		if n, _ := f.Read(h[:]); n == 24 && string(h[0:4]) == "P7VP" {
			v.PackFrames = int(uint32(h[8]) | uint32(h[9])<<8 | uint32(h[10])<<16 | uint32(h[11])<<24)
		}
		if st, err := f.Stat(); err == nil {
			v.PackKB = int(st.Size() / 1024)
		}
		f.Close()
	}

	b, err := os.ReadFile(filepath.Join(dir, "log", "p7video.log"))
	if err != nil {
		return v
	}
	if len(b) > 65536 {
		b = b[len(b)-65536:]
	}
	txt := string(b)
	if i := strings.Index(txt, "vsync ioctl="); i >= 0 {
		e := strings.IndexByte(txt[i:], '\n')
		if e < 0 {
			e = len(txt) - i
		}
		v.Vsync = strings.TrimSpace(txt[i : i+e])
	}
	// 最后一行 fps= 统计。
	// 前面补一个换行：日志被裁剪后第一行可能正好就是统计行，
	// 不补的话 LastIndex("\nfps=") 会漏掉它、只认到上一次的行。
	probe := "\n" + txt
	if i := strings.LastIndex(probe, "\nfps="); i >= 0 {
		line := probe[i+1:]
		if j := strings.IndexByte(line, '\n'); j >= 0 {
			line = line[:j]
		}
		var fps, dec, ren, wri float64
		var fr int
		n, _ := fmt.Sscanf(line, "fps=%f dec=%fms ren=%fms wri=%fms frames=%d",
			&fps, &dec, &ren, &wri, &fr)
		if n == 5 {
			v.OK, v.RealFps, v.DecMs, v.RenMs, v.WriMs, v.Frames = true, fps, dec, ren, wri, fr
		}
	}
	return v
}

func videoRunning() bool {
	videoMu.Lock()
	defer videoMu.Unlock()
	return videoCmd != nil
}

// packSig 帧包指纹：大小 + mtime。车机重传后两者至少有一个会变。
func packSig(dir string) string {
	st, err := os.Stat(filepath.Join(dir, videoPackName))
	if err != nil {
		return ""
	}
	return strconv.FormatInt(st.Size(), 10) + ":" + strconv.FormatInt(st.ModTime().UnixNano(), 10)
}

// videoVsyncNow 写屏前是否等垂直同步（默认 on，与 C 版播放器一致）。
//
// 等 vsync 的好处是撕裂线固定在同一个位置、不随写屏时机乱跳；
// 代价是帧周期被量化到 16.7ms 的倍数，一帧写不满时可能从 24fps 掉到 20fps。
// 所以做成可关，现场用播放器日志里的 fps 值对比。
func videoVsyncNow(dir string) string {
	b, err := os.ReadFile(filepath.Join(dir, videoVsyncFile))
	if err != nil {
		return "on"
	}
	if strings.TrimSpace(string(b)) == "off" {
		return "off"
	}
	return "on"
}

// videoTuneNow 影响播放器启动参数的现场设置（帧率 + 写屏方式 + vsync）。
//
// 这几个值只在 startVideo 时读一次，改了不重启就不会生效。
// 把它们做成指纹，主循环发现变了就自己重启一次。
func videoTuneNow(dir string) string {
	return videoFpsNow(dir) + "|" + videoWriteNow(dir) + "|" + videoVsyncNow(dir)
}

// tuneChanged 播放器启动后帧率/写屏方式被改过
func tuneChanged(dir string) bool {
	videoMu.Lock()
	running, loaded := videoCmd != nil, videoTuneSig
	videoMu.Unlock()
	if !running || loaded == "" {
		return false
	}
	return videoTuneNow(dir) != loaded
}

// packChanged 播放器启动之后帧包被换过（车机上传了新版本）→ 需要重启播放器
func packChanged(dir string) bool {
	videoMu.Lock()
	running, loaded := videoCmd != nil, videoPackSig
	videoMu.Unlock()
	if !running || loaded == "" {
		return false
	}
	cur := packSig(dir)
	if cur == "" || cur == loaded {
		return false
	}
	// 大小稳定 + 头正确才认，避免上传中途的半截文件引发反复重启
	if !packHeaderOKAt(filepath.Join(dir, videoPackName)) {
		return false
	}
	if packSig(dir) != cur {
		return false
	}
	return true
}

// startVideo 拉起播放器；已在跑就什么都不做
func startVideo(dir string, lg *log.Logger) {
	videoMu.Lock()
	defer videoMu.Unlock()
	if videoCmd != nil {
		return
	}
	bin := filepath.Join(dir, videoBinName)
	pack := filepath.Join(dir, videoPackName)
	ov := filepath.Join(dir, videoOvName)
	// FTP 落地默认 644，车机端只在"文件大小变化"时才 SITE CHMOD，大小相同就跳过——
	// 一旦以 644 落地就再也没有机会修正，表现为 fork/exec: permission denied
	// （2026-11-19 实车：调试页一直显示静态图，查日志才发现播放器起不来）。
	// 这里每次启动前补一次 0755，不依赖车机端行为。
	if err := os.Chmod(bin, 0755); err != nil {
		lg.Printf("视频: chmod 失败 %v", err)
	}
	args := []string{"--pack", pack, "--fps", videoFpsNow(dir), "--overlay", ov, "--verbose"}
	// 显式传写屏方式：播放器默认是 pwrite，加 --write mmap 才走映射直写。
	// 不传的时候它永远是 pwrite，mmap 那条分支等于死代码（见 P7 播放器源码 writer 线程）。
	if w := videoWriteNow(dir); w != "" {
		args = append(args, "--write", w)
	}
	// 显式传 vsync 开关：默认 on，写 off 才关（关掉更容易跑满帧，但撕裂位置随机）
	args = append(args, "--vsync", videoVsyncNow(dir))
	cmd := exec.Command(bin, args...)
	cmd.Dir = dir
	// 日志自己接管：只保留最后几百行。播放器每 2 秒一行，直接接文件会无限长，
	// 车机拉日志要整文件传，最后会慢到超时（见 videolog.go）。
	lgf := newLogRing(filepath.Join(dir, "log", "p7video.log"))
	lgf.reset()
	cmd.Stdout, cmd.Stderr = lgf, lgf
	cmd.SysProcAttr = &syscall.SysProcAttr{Setsid: true}
	// 先取指纹再 Start：启动期间若车机正好换了包，这一轮指纹就是旧的，
	// 下一轮 packChanged() 会立刻发现并再重启一次，不会漏。
	sig := packSig(dir)
	tsig := videoTuneNow(dir)
	if err := cmd.Start(); err != nil {
		lg.Printf("视频: 启动失败 %v", err)
		return
	}
	videoCmd = cmd
	videoPackSig = sig
	videoTuneSig = tsig
	lg.Printf("视频: 已启动 pid=%d pack=%s fps=%s write=%s", cmd.Process.Pid, pack,
		videoFpsNow(dir), func() string {
			if w := videoWriteNow(dir); w != "" {
				return w
			}
			return "pwrite"
		}())
	c := cmd
	go func() {
		err := c.Wait()
		videoMu.Lock()
		if videoCmd == c {
			videoCmd = nil
		}
		videoMu.Unlock()
		lg.Printf("视频: 播放器退出 %v", err)
	}()
}

// stopVideo 结束播放器并等它把 fb1 还原（它自己会 blank + 改回 240x320）
func stopVideo(lg *log.Logger) {
	videoMu.Lock()
	c := videoCmd
	videoCmd = nil
	videoPackSig = ""
	videoTuneSig = ""
	videoMu.Unlock()
	if c == nil || c.Process == nil {
		return
	}
	_ = c.Process.Signal(syscall.SIGTERM)
	deadline := time.Now().Add(1500 * time.Millisecond)
	for time.Now().Before(deadline) {
		if c.ProcessState != nil {
			break
		}
		time.Sleep(50 * time.Millisecond)
	}
	_ = c.Process.Kill()
	lg.Printf("视频: 已停止播放器")
}

// overlayDue 到点该刷文字层了吗
func overlayDue() bool {
	videoMu.Lock()
	defer videoMu.Unlock()
	if time.Since(videoOvAt) < 900*time.Millisecond {
		return false
	}
	videoOvAt = time.Now()
	return true
}

// chgVideoTag 给 status.json 用：vplay = 本地播放器正在接管整屏
func chgVideoTag() string {
	if videoRunning() {
		return "vplay"
	}
	return ""
}
