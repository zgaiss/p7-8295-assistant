//go:build linux

package main

// fb1 前景层：16bpp RGB565 + 色键透明（键值 0）
//
// 与 p7hud 完全一致的显示通路：
//   1. 把 fb1 注册/切换为 1920x720（写 modes → mode → virtual_size 三步）
//   2. 全屏清 0（透明），只画自己的像素
//   3. 退出时清像素 + FBIOBLANK(4) 关层，原厂仪表立即透出
//
// 安全约束：**绝不读 /sys/class/graphics/fb1/modes**（该内核 show_modes 会 Oops），
// 是否切换成功只看 stride 与 FBIOGET_VSCREENINFO。

import (
	"fmt"
	"log"
	"os"
	"os/exec"
	"sort"
	"strconv"
	"strings"
	"syscall"
	"time"
	"unsafe"
)

const (
	fbPath = "/dev/fb1"
	fbSys  = "/sys/class/graphics/fb1"
	modeNm = "U:1920x720p-60"
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

func structBytes(m *fbVideomode) []byte {
	return unsafe.Slice((*byte)(unsafe.Pointer(m)), int(unsafe.Sizeof(*m)))
}

type fbDev struct {
	f       *os.File
	mem     []byte
	lineLen int
	w, h    int
	bg      *bgDev // 原生层 fb0，用于把半透明像素正确合成到背景上
}

// ---------- 原生图层 fb0（只读） ----------
//
// fb1 是 16bpp RGB565 + 色键透明（像素值 0 = 透明），没有逐像素 alpha，
// 唯一的"透明手段"就是色键。之前 blit 把半覆盖的边缘像素还原成满强度实色，
// 抗锯齿等于被整体抹掉：小字变硬、变粗，加超采样也不会有任何改善。
//
// 这里直接读 fb0 的背景像素，在软件里按覆盖率混合，再把结果写进 fb1。
// 车机自带的文字是 GPU 在 32bpp 层上做 alpha 混合，这样两边观感才对得上。

type bgDev struct {
	f                *os.File
	mem              []byte
	lineLen          int
	w, h             int
	bpp              int
	rOff, gOff, bOff uint32
}

func openBG() *bgDev {
	f, err := os.OpenFile("/dev/fb0", os.O_RDONLY, 0)
	if err != nil {
		log.Printf("bg: 打不开 /dev/fb0 (%v)，抗锯齿退化为实色", err)
		return nil
	}
	vi := &fbVarScreeninfo{}
	if err := ioctl(f.Fd(), 0x4600, unsafe.Pointer(vi)); err != nil {
		f.Close()
		return nil
	}
	fi := &fbFixScreeninfo{}
	if err := ioctl(f.Fd(), 0x4602, unsafe.Pointer(fi)); err != nil {
		f.Close()
		return nil
	}
	d := &bgDev{f: f, w: int(vi.Xres), h: int(vi.Yres), bpp: int(vi.BitsPerPixel),
		rOff: vi.Red.Offset, gOff: vi.Green.Offset, bOff: vi.Blue.Offset}
	d.lineLen = int(fi.LineLength)
	if d.lineLen <= 0 {
		d.lineLen = d.w * d.bpp / 8
	}
	// 只接受 32bpp、每通道 8bit 的布局；其它格式宁可不合成，也不出花屏
	if d.bpp != 32 || vi.Red.Length != 8 || vi.Green.Length != 8 || vi.Blue.Length != 8 {
		log.Printf("bg: fb0 格式不支持 bpp=%d r=%d/%d g=%d/%d b=%d/%d，退化为实色",
			d.bpp, vi.Red.Offset, vi.Red.Length, vi.Green.Offset, vi.Green.Length, vi.Blue.Offset, vi.Blue.Length)
		f.Close()
		return nil
	}
	mem, err := syscall.Mmap(int(f.Fd()), 0, d.lineLen*d.h, syscall.PROT_READ, syscall.MAP_SHARED)
	if err != nil {
		f.Close()
		return nil
	}
	d.mem = mem
	log.Printf("bg: fb0 %dx%d stride=%d bpp=%d r=%d g=%d b=%d 已接入合成",
		d.w, d.h, d.lineLen, d.bpp, d.rOff, d.gOff, d.bOff)
	return d
}

func (d *bgDev) close() {
	if d == nil {
		return
	}
	if d.mem != nil {
		syscall.Munmap(d.mem)
		d.mem = nil
	}
	if d.f != nil {
		d.f.Close()
		d.f = nil
	}
}

// at 取背景色；越界或未启用返回 false
func (d *bgDev) at(x, y int) (uint8, uint8, uint8, bool) {
	if d == nil || d.mem == nil || x < 0 || y < 0 || x >= d.w || y >= d.h {
		return 0, 0, 0, false
	}
	p := y*d.lineLen + x*4
	if p+4 > len(d.mem) {
		return 0, 0, 0, false
	}
	v := uint32(d.mem[p]) | uint32(d.mem[p+1])<<8 | uint32(d.mem[p+2])<<16 | uint32(d.mem[p+3])<<24
	return uint8(v >> d.rOff), uint8(v >> d.gOff), uint8(v >> d.bOff), true
}

// mix8 dst 上按 a/255 混入 src
func mix8(dst, src, a uint8) uint8 {
	if a == 0 {
		return dst
	}
	if a == 255 {
		return src
	}
	return uint8((int(dst)*(255-int(a)) + int(src)*int(a) + 127) / 255)
}

// avgLum 采样原厂画面的中位亮度，用来判断原厂现在是白天还是夜间配色。
//
// 为什么可信：亮度调节走的是**硬件背光**（实测把亮度调到最暗，fb0 里的像素值
// 一点都不变，白天仍是 220/230），所以这里读到的就是原厂 UI 本身的配色亮度，
// 不受用户亮度设置、背光曲线影响。实测白天中位 ≈220、夜间 ≈20，余量极大。
//
// 用 16x6 网格取中位数，避开车辆模型、文字等局部高亮；全黑（屏保/未点亮）视为无效。
func (d *bgDev) avgLum() (int, bool) {
	if d == nil || d.mem == nil || d.w <= 0 || d.h <= 0 {
		return 0, false
	}
	vals := make([]int, 0, 16*6)
	for gy := 0; gy < 6; gy++ {
		for gx := 0; gx < 16; gx++ {
			x := (gx*2 + 1) * d.w / 32
			y := (gy*2 + 1) * d.h / 12
			r, g, b, ok := d.at(x, y)
			if !ok {
				continue
			}
			vals = append(vals, (299*int(r)+587*int(g)+114*int(b))/1000)
		}
	}
	if len(vals) < 20 {
		return 0, false
	}
	sort.Ints(vals)
	med := vals[len(vals)/2]
	if med <= 2 { // 全黑：屏保/未点亮，不参与判断
		return med, false
	}
	return med, true
}

func openFB() (*fbDev, error) {
	f, err := os.OpenFile(fbPath, os.O_RDWR, 0)
	if err != nil {
		return nil, err
	}
	d := &fbDev{f: f}
	if err := d.refresh(); err != nil {
		f.Close()
		return nil, err
	}
	d.bg = openBG() // 失败不影响显示，只是抗锯齿退化为实色
	return d, nil
}

func (d *fbDev) refresh() error {
	vi := &fbVarScreeninfo{}
	if err := ioctl(d.f.Fd(), 0x4600, unsafe.Pointer(vi)); err != nil {
		return err
	}
	fi := &fbFixScreeninfo{}
	if err := ioctl(d.f.Fd(), 0x4602, unsafe.Pointer(fi)); err != nil {
		return err
	}
	d.w, d.h, d.lineLen = int(vi.Xres), int(vi.Yres), int(fi.LineLength)
	if d.lineLen <= 0 {
		d.lineLen = d.w * 2
	}
	need := d.lineLen * d.h
	if need > int(fi.SmemLen) {
		need = int(fi.SmemLen)
	}
	if d.mem != nil {
		syscall.Munmap(d.mem)
		d.mem = nil
	}
	mem, err := syscall.Mmap(int(d.f.Fd()), 0, need, syscall.PROT_READ|syscall.PROT_WRITE, syscall.MAP_SHARED)
	if err != nil {
		return err
	}
	d.mem = mem
	return nil
}

func (d *fbDev) close() {
	if d == nil {
		return
	}
	// 先清像素 + 关层，再释放资源：任何关闭路径（含 panic 展开）都保证原厂仪表透出
	restore(d)
	d.release()
}

// closeQuiet 只释放句柄，不做 restore。
//
// 专用于"旧句柄即将被新句柄替换"的场景（applyOn 开层、recoverFG、reinit、
// 几何自愈四处）：那里 openFB 已经拿到同一块 fb1 的新映射，紧接着就
// setFGAttrs + layerOn + clearAll 重新接管。若此时仍走 close() 里的 restore，
// 会先把整块显存清 0 再 FBIOBLANK(4) 关屏——屏幕必然黑一下；视频模式退出或
// 重启播放器时，这一步和随后的 clearAll 叠起来就是肉眼看到的"闪黑两下"
// （2026-11-19 实车定位）。
func (d *fbDev) closeQuiet() {
	if d == nil {
		return
	}
	d.release()
}

// release 释放映射与文件句柄（不动像素、不改图层状态）。
func (d *fbDev) release() {
	if d.mem != nil {
		syscall.Munmap(d.mem)
		d.mem = nil
	}
	if d.f != nil {
		d.f.Close()
		d.f = nil
	}
	if d.bg != nil {
		d.bg.close()
	}
}

func (d *fbDev) clearAll() {
	for i := range d.mem {
		d.mem[i] = 0
	}
}

func (d *fbDev) clearBox(b rect) {
	b = b.clamp()
	if b.empty() {
		return
	}
	for y := int(b.y0); y < int(b.y1) && y < d.h; y++ {
		if y < 0 {
			continue
		}
		row := d.mem[y*d.lineLen:]
		for x := int(b.x0) * 2; x < int(b.x1)*2 && x+1 < len(row); x += 2 {
			row[x] = 0
			row[x+1] = 0
		}
	}
}

// blitBuf 整屏上屏用的复用缓冲（RGB565）
var blitBuf []byte

// blitOpaqueFull 整屏不透明快路径。
//
// 逐像素带分支写 fb 内存实测要 95ms（1.38M 次 2 字节写），而先把整帧转成
// RGB565 到普通内存、再一次性 memmove 进 fb 只要个位数到几十毫秒
// （对端交付文档实测 2.76MB 写 6~35ms）。充电页是整屏不透明画面，走这条路。
func (d *fbDev) blitOpaqueFull(cv *canvas) {
	w, h := d.w, d.h
	need := w * h * 2
	if need <= 0 || len(d.mem) < need {
		return
	}
	if cap(blitBuf) < need {
		blitBuf = make([]byte, need)
	}
	buf := blitBuf[:need]
	src := cv.img.Pix
	si, di := 0, 0
	for di < need {
		v := pack565(src[si], src[si+1], src[si+2])
		buf[di] = byte(v)
		buf[di+1] = byte(v >> 8)
		si += 4
		di += 2
	}
	copy(d.mem[:need], buf)
}

// blit 把画布上 b 区域写进 fb1；alpha=0 的像素写色键（透明）
func (d *fbDev) blit(cv *canvas, b rect) {
	b = b.clamp()
	if b.empty() {
		return
	}
	img := cv.img
	dx := img.Bounds().Dx()
	for y := int(b.y0); y < int(b.y1); y++ {
		if y < 0 || y >= d.h {
			continue
		}
		row := d.mem[y*d.lineLen:]
		for x := int(b.x0); x < int(b.x1); x++ {
			if x < 0 || x >= d.w {
				continue
			}
			o := (y*dx + x) * 4
			a := img.Pix[o+3]
			var v uint16
			if a != 0 {
				r, g, bl := img.Pix[o], img.Pix[o+1], img.Pix[o+2]
				if a < 0xff { // RGBA 是预乘的，先还原成实色
					r = byte(int(r) * 255 / int(a))
					g = byte(int(g) * 255 / int(a))
					bl = byte(int(bl) * 255 / int(a))
					// 关键：16bpp + 色键没有逐像素 alpha，必须在这里和背景
					// 真正混合，否则抗锯齿全部退化成实色（字变硬、变粗）。
					if br, bgc, bb, ok := d.bg.at(x, y); ok {
						r = mix8(br, r, a)
						g = mix8(bgc, g, a)
						bl = mix8(bb, bl, a)
					}
				}
				v = pack565(r, g, bl)
			}
			p := x * 2
			if p+1 >= len(row) {
				break
			}
			row[p] = byte(v)
			row[p+1] = byte(v >> 8)
		}
	}
}

// write565Rect 把一整帧已打包好的 RGB565 直接搬进 fb1 的目标矩形。
//
// 与 blit 的区别：这里**不解包、不混合、不查背景**。像素进来就是 fb1 的
// 最终内存布局（小端 16bpp），一行 960 像素 = 1920 字节，用 copy 整行搬。
//
// 为什么必须走这条路：blit 是逐像素带分支写（还要按 alpha 和 fb0 混合），
// 全屏实测 95ms；而整行 memmove 两个数量级更快。影像 960×540 = 1.0MB，
// 按 175MB/s 约 6ms，15fps 才占得住这颗 CPU。
//
// 返回写入的像素行数，0 表示没写（尺寸不符或矩形越界）。
func (d *fbDev) write565Rect(b rect, pix []byte, srcW, srcH int) int {
	b = b.clamp()
	if b.empty() || srcW <= 0 || srcH <= 0 {
		return 0
	}
	w := int(b.x1) - int(b.x0)
	h := int(b.y1) - int(b.y0)
	if w != srcW || h > srcH {
		return 0 // 宽度对不上就没法整行搬；宁可整帧不写，也不要写半块留残影
	}
	rowBytes := srcW * 2
	for y := 0; y < h; y++ {
		dy := int(b.y0) + y
		if dy < 0 || dy >= d.h {
			continue
		}
		so := y * srcW * 2
		if so+rowBytes > len(pix) {
			break
		}
		row := d.mem[dy*d.lineLen:]
		do := int(b.x0) * 2
		if do+rowBytes > len(row) {
			break
		}
		copy(row[do:do+rowBytes], pix[so:so+rowBytes])
	}
	return h
}

func readSys(name string) string {
	b, _ := os.ReadFile(fbSys + "/" + name)
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

func genModeBin(dst string) error {
	f, err := os.OpenFile("/dev/fb0", os.O_RDWR, 0)
	if err != nil {
		return err
	}
	defer f.Close()
	vi := &fbVarScreeninfo{}
	if err := ioctl(f.Fd(), 0x4600, unsafe.Pointer(vi)); err != nil {
		return err
	}
	m := fbVideomode{
		Name:        0,
		Refresh:     60,
		Xres:        frameW,
		Yres:        frameH,
		Pixclock:    vi.Pixclock,
		LeftMargin:  vi.LM,
		RightMargin: vi.RM,
		UpperMargin: vi.UM,
		LowerMargin: vi.LoM,
		HsyncLen:    vi.HsyncLen,
		VsyncLen:    vi.VsyncLen,
		Sync:        vi.Sync,
		Vmode:       vi.Vmode,
	}
	raw := structBytes(&m)
	if len(raw) != 56 {
		return fmt.Errorf("bad videomode size %d", len(raw))
	}
	return os.WriteFile(dst, raw, 0644)
}

// fbGeomOK 除 x/y/stride 外还必须保证「单缓冲 + 从第 0 页开始」。
// 原厂接管过 fb1 之后，可能把 yres_virtual 改回双缓冲、或把 yoffset 挪到第二页；
// 此时我们的 mmap 仍然指向第 0 页，进程照常绘制、frames 一直涨，但屏幕上什么都没有。
// 这种「在画却看不见」的故障只查 x/y 是发现不了的。
func fbGeomOK() bool {
	if readSys("stride") != strconv.Itoa(frameW*2) {
		return false
	}
	f, err := os.OpenFile(fbPath, os.O_RDWR, 0)
	if err != nil {
		return false
	}
	defer f.Close()
	vi := &fbVarScreeninfo{}
	if err := ioctl(f.Fd(), 0x4600, unsafe.Pointer(vi)); err != nil {
		return false
	}
	if int(vi.Xres) != frameW || int(vi.Yres) != frameH {
		return false
	}
	if int(vi.YresVirtual) != frameH || vi.Xoffset != 0 || vi.Yoffset != 0 {
		return false
	}
	return true
}

// listFB 列出 /dev/fb*：确认原厂有没有启用额外的 overlay 层（比如 fb2 盖在我们上面）
func listFB() []string {
	ents, err := os.ReadDir("/dev")
	if err != nil {
		return nil
	}
	var out []string
	for _, e := range ents {
		if strings.HasPrefix(e.Name(), "fb") {
			out = append(out, e.Name())
		}
	}
	sort.Strings(out)
	return out
}

// layerOn 打开前景层：sysfs 与 ioctl 两条路都走，任一失效另一条还能兜住。
func layerOn(d *fbDev) {
	_ = writeSys("blank", "0")
	if d != nil && d.f != nil {
		ioctl(d.f.Fd(), 0x4611, unsafe.Pointer(uintptr(0))) // FBIOBLANK(0)
	}
}

// layerBlanked 前景层当前是否被关掉。读不到一律按「没关」处理：
// 宁可不动（照旧画），也不能因为读不到就去抢原厂画面。
func layerBlanked() bool {
	v := readSys("blank")
	return v != "" && v != "0"
}

// ensureMode 确保 fb1 = 1920x720；几何已正常时直接返回
func ensureMode(dir string, lg *log.Logger) error {
	if fbGeomOK() {
		return nil
	}
	return setMode(dir, lg)
}

// setMode 无条件重走「写 modes → mode → virtual_size」三步。
// 原厂接管过 fb1 之后，overlay 通道可能不再参与合成：此时几何、blank 全部正常，
// 进程照常写 mmap，但屏幕上什么都看不到。只有重新走一遍 setmode 才能让内核
// 重新注册前景层，单纯 FBIOBLANK(0) 和重设 alpha 都没用。
func setMode(dir string, lg *log.Logger) error {
	bin := dir + "/m1920.bin"
	if err := genModeBin(bin); err != nil {
		return fmt.Errorf("genModeBin: %v", err)
	}
	var lastErr error
	for attempt := 1; attempt <= 3; attempt++ {
		// 写 modes 的 write 在内核里不返回，只能起子进程、等 3 秒、杀掉
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
		if err := writeSys("virtual_size", fmt.Sprintf("%d,%d\n", frameW, frameH)); err != nil {
			lg.Printf("mode: 写 virtual_size 失败 %v", err)
		}
		time.Sleep(700 * time.Millisecond)

		if fbGeomOK() {
			lg.Printf("mode: OK (attempt %d) stride=%s vsize=%q", attempt, readSys("stride"), readSys("virtual_size"))
			return nil
		}
		lastErr = fmt.Errorf("attempt %d 未生效 stride=%s vsize=%q", attempt, readSys("stride"), readSys("virtual_size"))
		lg.Printf("mode: %v", lastErr)
	}
	return lastErr
}

func setFGAttrs(d *fbDev) {
	type p struct{ X, Y uint16 }
	pp := p{0, 0}
	ioctl(d.f.Fd(), 0xc0044624, unsafe.Pointer(&pp)) // MXCFB_SET_OVERLAY_POS
	type ck struct{ Enable, Key uint32 }
	k := ck{1, 0}
	ioctl(d.f.Fd(), 0x40084622, unsafe.Pointer(&k)) // MXCFB_SET_CLR_KEY
	type ga struct{ Enable, A uint32 }
	a := ga{1, 255}
	ioctl(d.f.Fd(), 0x40084621, unsafe.Pointer(&a))     // MXCFB_SET_GBL_ALPHA
	ioctl(d.f.Fd(), 0x4611, unsafe.Pointer(uintptr(0))) // FBIOBLANK(0)
}

// fbBlank 0=开层 4=关层
func fbBlank(d *fbDev, mode uintptr) {
	ioctl(d.f.Fd(), 0x4611, unsafe.Pointer(mode))
}

// restore 清空 FG 像素并关层，原厂仪表立即透出
func restore(d *fbDev) {
	if d != nil && d.mem != nil {
		for i := range d.mem {
			d.mem[i] = 0
		}
		ioctl(d.f.Fd(), 0x4611, unsafe.Pointer(uintptr(4))) // FBIOBLANK(4)
	}
}

// cleanupCmd 独立清屏兜底：主进程被 SIGKILL 等无法自清时，由看门狗调用，
// 关层 + 清像素，原厂仪表立即透出。不进入主循环，几何异常时也保证关层。
func cleanupCmd() {
	if d, err := openFB(); err == nil {
		restore(d)
		d.close()
		fmt.Println("cleanup: 已清屏并关闭前景层")
		return
	}
	f, err := os.OpenFile(fbPath, os.O_RDWR, 0)
	if err != nil {
		fmt.Println("cleanup: 打开 fb1 失败:", err)
		os.Exit(1)
	}
	defer f.Close()
	ioctl(f.Fd(), 0x4611, unsafe.Pointer(uintptr(4))) // FBIOBLANK(4)
	fmt.Println("cleanup: 已关闭前景层（几何异常，仅关层）")
}
