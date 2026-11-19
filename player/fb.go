//go:build linux

package main

// fb1 前景层操作（从仪表 daemon 的 instrument/fb.go 摘出来的最小集）。
//
// 只保留播放器需要的部分：把 fb1 切成 1920×720 单缓冲、开不透明整层、
// 退出时还原成 240×320。安全约束照旧：**绝不读 /sys/class/graphics/fb1/modes**，
// 那个内核的 show_modes 会 Oops；是否切成功只看 stride 与 FBIOGET_VSCREENINFO。

import (
	"fmt"
	"log"
	"os"
	"os/exec"
	"strconv"
	"strings"
	"syscall"
	"time"
	"unsafe"
)

const (
	fbW    = 1920
	fbH    = 720
	fbPath = "/dev/fb1"
	fbSys  = "/sys/class/graphics/fb1"
	modeNm = "U:1920x720p-60"
)

const (
	fbioGetVScreeninfo = 0x4600
	fbioBlank          = 0x4611
	fbioWaitForVsync   = 0x40044620
	mxcfbSetOverlayPos = 0xc0044624
	mxcfbSetClrKey     = 0x40084622
	mxcfbSetGblAlpha   = 0x40084621
)

type fbVarScreeninfo struct {
	Xres, Yres, XresVirtual, YresVirtual uint32
	Xoffset, Yoffset                     uint32
	BitsPerPixel                         uint32
	Grayscale                            uint32
	Red, Green, Blue, Transp             struct{ Offset, Length, MsbRight uint32 }
	Nonstd, Activate                     uint32
	Height, Width, AccelFlags            uint32
	Pixclock                             uint32
	LM, RM, UM, LoM                      uint32
	HsyncLen, VsyncLen                   uint32
	Sync, Vmode, Rotate, Colorspace      uint32
	Reserved                             [4]uint32
}

type fbFixScreeninfo struct {
	ID           [16]byte
	SmemStart    uint32
	SmemLen      uint32
	Type         uint32
	TypeAux      uint32
	Visual       uint32
	XpanStep     uint16
	YpanStep     uint16
	YwrapStep    uint16
	Pad0         uint16
	LineLength   uint32
	MmioStart    uint32
	MmioLen      uint32
	Accel        uint32
	Capabilities uint16
	Reserved     [2]uint16
}

type fbVideomode struct {
	Name, Refresh, Xres, Yres, Pixclock               uint32
	LeftMargin, RightMargin, UpperMargin, LowerMargin uint32
	HsyncLen, VsyncLen, Sync, Vmode, Flag             uint32
}

func ioctl(fd uintptr, req uintptr, p unsafe.Pointer) error {
	_, _, e := syscall.Syscall(syscall.SYS_IOCTL, fd, req, uintptr(p))
	if e != 0 {
		return e
	}
	return nil
}

func readSys(name string) string {
	b, err := os.ReadFile(fbSys + "/" + name)
	if err != nil {
		return ""
	}
	return strings.TrimSpace(string(b))
}

func writeSys(name, val string) error {
	f, err := os.OpenFile(fbSys+"/"+name, os.O_WRONLY, 0)
	if err != nil {
		return err
	}
	defer f.Close()
	_, err = f.WriteString(val)
	return err
}

func fbGeomOK() bool {
	if readSys("stride") != strconv.Itoa(fbW*2) {
		return false
	}
	f, err := os.OpenFile(fbPath, os.O_RDWR, 0)
	if err != nil {
		return false
	}
	defer f.Close()
	vi := &fbVarScreeninfo{}
	if err := ioctl(f.Fd(), fbioGetVScreeninfo, unsafe.Pointer(vi)); err != nil {
		return false
	}
	if int(vi.Xres) != fbW || int(vi.Yres) != fbH {
		return false
	}
	if int(vi.YresVirtual) != fbH || vi.Xoffset != 0 || vi.Yoffset != 0 {
		return false
	}
	return true
}

// genModeBin 生成 56 字节的 videomode 结构：写进 fb1 的 modes 节点用。
// 抄 fb0 的时序、只改分辨率 —— 两层的像素时钟必须一致，否则 overlay 不参与合成。
func genModeBin(dst string, lg *log.Logger) error {
	f, err := os.OpenFile("/dev/fb0", os.O_RDWR, 0)
	if err != nil {
		return err
	}
	defer f.Close()
	vi := &fbVarScreeninfo{}
	if err := ioctl(f.Fd(), fbioGetVScreeninfo, unsafe.Pointer(vi)); err != nil {
		return err
	}
	m := fbVideomode{
		Name: 0, Refresh: 60, Xres: fbW, Yres: fbH,
		Pixclock: vi.Pixclock, LeftMargin: vi.LM, RightMargin: vi.RM,
		UpperMargin: vi.UM, LowerMargin: vi.LoM,
		HsyncLen: vi.HsyncLen, VsyncLen: vi.VsyncLen, Sync: vi.Sync, Vmode: vi.Vmode,
	}
	raw := unsafe.Slice((*byte)(unsafe.Pointer(&m)), int(unsafe.Sizeof(m)))
	if len(raw) != 56 {
		return fmt.Errorf("bad videomode size %d", len(raw))
	}
	return os.WriteFile(dst, raw, 0644)
}

// setMode 把 fb1 切成 1920×720 单缓冲。
//
// 关键：往 modes 节点 write 在内核里不返回，只能起子进程、等 3 秒、再杀掉。
// 整个流程约 4.4 秒，期间屏幕是黑的 —— 所以只在几何不对的时候做一次。
func setMode(lg *log.Logger) error {
	bin := "/tmp/p7vplay_mode.bin"
	if err := genModeBin(bin, lg); err != nil {
		return fmt.Errorf("genModeBin: %v", err)
	}
	var lastErr error
	for attempt := 1; attempt <= 3; attempt++ {
		cmd := exec.Command("/bin/dd", "if="+bin, "of="+fbSys+"/modes", "bs=56", "count=1")
		if err := cmd.Start(); err != nil {
			return fmt.Errorf("start dd: %v", err)
		}
		time.Sleep(3 * time.Second)
		cmd.Process.Kill()
		cmd.Wait()

		if err := writeSys("mode", modeNm+"\n"); err != nil {
			lg.Printf("mode: 写 mode 失败 %v", err)
		}
		time.Sleep(700 * time.Millisecond)
		if err := writeSys("virtual_size", fmt.Sprintf("%d,%d\n", fbW, fbH)); err != nil {
			lg.Printf("mode: 写 virtual_size 失败 %v", err)
		}
		time.Sleep(700 * time.Millisecond)

		if fbGeomOK() {
			lg.Printf("mode: OK (attempt %d) stride=%s", attempt, readSys("stride"))
			return nil
		}
		lastErr = fmt.Errorf("attempt %d 未生效 stride=%s", attempt, readSys("stride"))
		lg.Printf("mode: %v", lastErr)
	}
	return lastErr
}

type fbDev struct {
	f       *os.File
	lineLen int
}

func fb1Open(lg *log.Logger) (*fbDev, error) {
	f, err := os.OpenFile(fbPath, os.O_RDWR, 0)
	if err != nil {
		return nil, err
	}
	d := &fbDev{f: f, lineLen: fbW * 2}
	setFGAttrs(d)
	return d, nil
}

// setFGAttrs 不透明整层 + 色键 0 + 开屏
func setFGAttrs(d *fbDev) {
	type p struct{ X, Y uint16 }
	pp := p{0, 0}
	ioctl(d.f.Fd(), mxcfbSetOverlayPos, unsafe.Pointer(&pp))
	type ck struct{ Enable, Key uint32 }
	k := ck{1, 0}
	ioctl(d.f.Fd(), mxcfbSetClrKey, unsafe.Pointer(&k))
	type ga struct{ Enable, A uint32 }
	a := ga{1, 255}
	ioctl(d.f.Fd(), mxcfbSetGblAlpha, unsafe.Pointer(&a))
	ioctl(d.f.Fd(), fbioBlank, unsafe.Pointer(uintptr(0)))
}

// restore 退出时还原：关层 + 把 mode/virtual_size 改回原厂 240×320。
// 不做这一步，daemon 回来时屏幕是黑的（它要重走 setMode 才能重新注册 overlay）。
func fb1Restore(d *fbDev) {
	if d != nil && d.f != nil {
		ioctl(d.f.Fd(), fbioBlank, unsafe.Pointer(uintptr(4)))
		d.f.Close()
	}
	writeSys("mode", "U:240x320p-60\n")
	writeSys("virtual_size", "240,960\n")
}

// waitVsync 等一次垂直同步。返回 nil 表示这个内核支持。
func waitVsync(d *fbDev) error {
	var z uint32
	return ioctl(d.f.Fd(), fbioWaitForVsync, unsafe.Pointer(&z))
}
