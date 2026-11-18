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
	"log"
	"os"
	"strconv"
	"strings"
	"os/exec"
	"path/filepath"
	"sync"
	"syscall"
	"time"
)

const (
	videoBinName = "p7videoplay"
	videoPackName = "charge.pack"
	videoOvName  = "chgoverlay.jpg"
	videoFpsArg  = "10"
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

func videoRunning() bool {
	videoMu.Lock()
	defer videoMu.Unlock()
	return videoCmd != nil
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
	cmd := exec.Command(bin, "--pack", pack, "--fps", videoFpsNow(dir), "--overlay", ov, "--verbose")
	cmd.Dir = dir
	if f, err := os.OpenFile(filepath.Join(dir, "log", "p7video.log"),
		os.O_CREATE|os.O_WRONLY|os.O_TRUNC, 0644); err == nil {
		cmd.Stdout, cmd.Stderr = f, f
	}
	cmd.SysProcAttr = &syscall.SysProcAttr{Setsid: true}
	if err := cmd.Start(); err != nil {
		lg.Printf("视频: 启动失败 %v", err)
		return
	}
	videoCmd = cmd
	lg.Printf("视频: 已启动 pid=%d pack=%s", cmd.Process.Pid, pack)
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
