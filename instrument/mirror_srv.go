package main

// 打灯影像接收端（mirror）：TCP 收帧 → 解压 → 直接写进 fb1 的影像矩形。
//
// 并发模型（这是"不花屏"的根本）：
//
//	分区所有权，而不是单写者。影像矩形和界面区域是两块**互不相交**的像素，
//	各自只有一个写者：
//	  - 解码线程只写影像矩形（flushTo），从不写界面；
//	  - 渲染主循环只写界面（boxBlit.minus 把影像矩形减掉），从不写影像。
//
//	所以两者不需要每帧加锁，也不会出现"两套程序抢同一块显存"导致的花屏。
//	唯一需要串行化的是**归属变更**：换侧或撤区时主循环先 revoke（持锁等解码
//	线程写完手上这一帧）再 clearBox，然后 grant 新矩形。
//
// 为什么不再走"主循环统一上屏"：实车实测主循环本身只有 4.7fps
// （每轮绘制 150~190ms + 上屏 50ms，与影像无关），影像跟着它就只能 4.7fps。
// 改成解码线程直写后，影像帧率只受解码速度与车机推流帧率限制。

import (
	"bytes"
	"compress/zlib"
	"fmt"
	"io"
	"net"
	"os"
	"strconv"
	"sync"
	"sync/atomic"
	"time"

	"github.com/klauspost/compress/flate"
	"github.com/klauspost/compress/zstd"
)

// mirServer 是网络线程与渲染线程之间唯一的交接点。
type mirServer struct {
	port int
	logf func(string, ...any)

	ln  net.Listener
	dec *zstd.Decoder

	// deflate 解码器复用。原先每帧 zlib.NewReader 都要新建 reader（约 40KB 分配）
	// 再解 1MB，A9 上单帧要 100ms 上下——正好把 TCP 接收窗口堵死，
	// 表现为车机端 socket write 阻塞 140ms、端到端只剩 7fps。
	// 现在走 klauspost 的 flate（A9 上比标准库快约一倍）并 Reset 复用。
	decMu sync.Mutex
	zsrc  *bytes.Reader
	zr    io.ReadCloser

	mu      sync.Mutex
	pending *mirFrame // 最新未消费帧（深度 1，新帧直接顶掉旧帧）
	pool    [][]byte  // 复用缓冲，避免 15fps 下每秒分配 15MB

	// 注意：ARMv7 上 64 位原子操作要求变量本身 8 字节对齐，结构体里的裸
	// int64 字段编译器并不保证这一点（Go 官方文档明确由调用方负责）。
	// 这里一律用 atomic.Int64 / atomic.Int32 —— 它们的 align64 标记会让
	// 编译器补齐对齐，避免 "unaligned 64-bit atomic operation" 直接 panic。
	lastRecv atomic.Int64 // 最后一次收帧的 UnixMilli
	conns    atomic.Int32 // 当前连接数
	closed   atomic.Int32

	drops atomic.Int32 // 缓冲不足丢帧
	bad   atomic.Int32 // 解析/解压失败

	// 耗时统计：车机端 sock write 阻塞时，只看到"对端消费慢"这一个现象，
	// 分不清是解压慢还是上屏慢。这两项分别计时，一打日志就能定位。
	decNs  atomic.Int64
	decN   atomic.Int32
	blitNs atomic.Int64
	blitN  atomic.Int32

	// 直写通道：解码线程收到帧后直接 memcpy 进 fb1 的影像矩形，
	// 不再等渲染主循环（实车主循环只有 4.7fps，每轮绘制 150~190ms）。
	//
	// 这不是"两个写者抢同一块显存"：授权矩形和主循环绘制的区域由
	// rect.minus 保证互不相交，主循环永远不写影像矩形；换侧/撤区时主循环先
	// revoke（持锁等到解码线程写完当前帧）再 clearBox，所以清理和写入也不会交错。
	dmu      sync.Mutex
	fbRef    rectWriter
	zone     rect   // 已授权的影像矩形（空 = 未授权）
	authSide uint8  // 已授权的方向（0 = 未授权）
	nzSeen   atomic.Int32 // 已打印过几次载荷指纹（限频，只打前几帧）
}

// rectWriter 是直写通道需要的最小能力：把一整帧 565 写进某块矩形。
//
// 用接口而不是 *fbDev，是因为 fb.go 带 `//go:build linux`，主机上跑单测时
// 那个类型根本不存在——直写逻辑必须能在主机上编译，否则协议与几何的
// 回归测试就得迁到实车去做，成本高一个量级。
type rectWriter interface {
	write565Rect(b rect, pix []byte, srcW, srcH int) int
	write565Scaled(b rect, pix []byte, srcW, srcH int) int
}

// markDecode 累计一次解压耗时（网络线程调用）。
func (v *mirServer) markDecode(d time.Duration) {
	v.decNs.Add(int64(d))
	v.decN.Add(1)
}

// markBlit 累计一次上屏耗时（解码线程直写时调用）。
func (v *mirServer) markBlit(d time.Duration) {
	v.blitNs.Add(int64(d))
	v.blitN.Add(1)
}

// grant 授权解码线程向 z 这块矩形直写。主循环每轮调用一次，
// 顺带把 fb 句柄刷新成当前值（reinit 会重开 fb，旧指针不能再用）。
func (v *mirServer) grant(fb rectWriter, z rect, side uint8) {
	v.dmu.Lock()
	v.fbRef, v.zone, v.authSide = fb, z, side
	v.dmu.Unlock()
}

// revoke 撤销授权。返回前保证解码线程不再持有锁、也不会再写这块矩形——
// 主循环随后的 clearBox 才是安全的。
func (v *mirServer) revoke() {
	v.dmu.Lock()
	v.fbRef, v.zone, v.authSide = nil, rect{}, mirSideNone
	v.dmu.Unlock()
}

// flushTo 把一帧直接写进已授权的影像矩形。返回 false 表示当前未授权
// （没打灯 / 换侧中 / 已撤区），这帧不用上屏。
//
// 写 1MB 放在锁内：换侧时主循环最多等 6ms，换来的是"清理矩形"与
// "写入矩形"绝不会交错，比锁外写安全得多。
func (v *mirServer) flushTo(side uint8, pix []byte, srcW, srcH int) bool {
	v.dmu.Lock()
	defer v.dmu.Unlock()
	if v.fbRef == nil || v.zone.empty() || side == mirSideNone || side != v.authSide {
		return false
	}
	// 前 3 帧打一次内容指纹：实车遇到"直写调用正常、fb1 影像区却全是 0"，
	// 只有把载荷本身的非零字节数打出来，才能区分"没写进去"和"写进去的就是黑帧"。
	if n := v.nzSeen.Load(); n < 3 && v.logf != nil {
		v.nzSeen.Add(1)
		nz := 0
		for _, b := range pix {
			if b != 0 {
				nz++
			}
		}
		var head []byte
		if len(pix) >= 8 {
			head = pix[:8]
		}
		// 首帧落盘一份原始载荷：gzip 后的体积能立刻判断画面是"纯色/黑帧"
		// 还是真有内容（纯色 1MB 压完只有几 KB），比逐点采样可靠。
		if n == 0 {
			_ = os.WriteFile("/data/p7assist/pix.raw", pix, 0644)
		}
		v.logf("影像诊断: side=%d 载荷 %d 字节 非零 %d 首8=% x 授权矩形=(%.0f,%.0f)-(%.0f,%.0f)",
			side, len(pix), nz, head, v.zone.x0, v.zone.y0, v.zone.x1, v.zone.y1)
	}
	t0 := time.Now()
	// 源尺寸与影像区等宽时整行 memcpy；否则最近邻放大（raw 降量的 640×360）。
	if srcW == int(v.zone.x1-v.zone.x0) && srcH == int(v.zone.y1-v.zone.y0) {
		v.fbRef.write565Rect(v.zone, pix, srcW, srcH)
	} else {
		v.fbRef.write565Scaled(v.zone, pix, srcW, srcH)
	}
	v.markBlit(time.Since(t0))
	return true
}

// stats 取走并清零本轮统计，返回平均毫秒与帧数。
func (v *mirServer) stats() (decMs, blitMs float64, decN, blitN int32) {
	decN = v.decN.Swap(0)
	blitN = v.blitN.Swap(0)
	dn := v.decNs.Swap(0)
	bn := v.blitNs.Swap(0)
	if decN > 0 {
		decMs = float64(dn) / float64(decN) / 1e6
	}
	if blitN > 0 {
		blitMs = float64(bn) / float64(blitN) / 1e6
	}
	return
}

// logBad 限频记录坏帧：前 3 次全打，之后每 100 次打一条。
//
// 静默丢帧是排查成本最高的一类问题——第一版实机联调就栽在这上面：
// 帧头 codec 字段写成 0（raw）但载荷是 zstd，raw 分支长度校验失败后直接
// continue，表现是"连接 ESTABLISHED、Send-Q 一直积压、画面不出、日志空白"。
// 所以每个丢弃分支都必须留痕，但又要限频，避免 15fps 刷爆日志文件。
func (v *mirServer) logBad(format string, args ...any) {
	n := v.bad.Load()
	if n <= 3 || n%100 == 0 {
		v.logf(format, args...)
	}
}

func newMirServer(port int, logf func(string, ...any)) *mirServer {
	if port <= 0 {
		port = 9002
	}
	dec, err := zstd.NewReader(nil,
		zstd.WithDecoderConcurrency(1), // 单线程解码：省内存，且这颗 CPU 只有一核做这件事
		zstd.WithDecoderLowmem(true),
	)
	if err != nil {
		logf("影像: zstd 解码器创建失败 %v（影像功能关闭）", err)
		return nil
	}
	return &mirServer{port: port, logf: logf, dec: dec}
}

// start 开始监听。端口占用、权限不足都只记日志，绝不阻断主程序。
func (v *mirServer) start() {
	ln, err := net.Listen("tcp", ":"+strconv.Itoa(v.port))
	if err != nil {
		v.logf("影像: 监听 :%d 失败 %v（影像关闭，其余功能不受影响）", v.port, err)
		return
	}
	v.ln = ln
	v.logf("影像: 监听 :%d，等待车机推流", v.port)
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				if v.closed.Load() != 0 {
					return
				}
				v.logf("影像: accept 失败 %v", err)
				return
			}
			v.conns.Add(1)
			go v.serve(c)
		}
	}()
}

func (v *mirServer) close() {
	v.closed.Store(1)
	if v.ln != nil {
		v.ln.Close()
	}
	if v.dec != nil {
		v.dec.Close()
	}
}

func (v *mirServer) takeBuf(n int) []byte {
	v.mu.Lock()
	if k := len(v.pool); k > 0 {
		b := v.pool[k-1]
		v.pool = v.pool[:k-1]
		v.mu.Unlock()
		if cap(b) >= n {
			return b[:n]
		}
		return make([]byte, n)
	}
	v.mu.Unlock()
	return make([]byte, n)
}

func (v *mirServer) putBuf(b []byte) {
	if cap(b) < mirZoneW*mirZoneH*2 {
		return
	}
	v.mu.Lock()
	if len(v.pool) < 3 { // 上限 3 块：两块在途 + 一块备用，再多是浪费内存
		v.pool = append(v.pool, b[:cap(b)])
	}
	v.mu.Unlock()
}

func (v *mirServer) serve(c net.Conn) {
	defer func() {
		c.Close()
		v.conns.Add(-1)
		// 断开时立刻投一张"撤区"帧：不等 800ms 超时，界面马上恢复
		v.publish(&mirFrame{side: mirSideNone})
		v.logf("影像: 车机断开，撤区")
	}()

	// 接收窗口给足：解码偶尔抖动时让内核多缓一点，不要把车机的 write 直接堵住
	if tc, ok := c.(*net.TCPConn); ok {
		tc.SetReadBuffer(1 << 20)
	}

	hdr := make([]byte, mirHdrLen)
	for {
		if _, err := io.ReadFull(c, hdr); err != nil {
			return
		}
		h, err := parseMirHeader(hdr)
		if err != nil {
			v.bad.Add(1)
			// 魔数错一般是接错端口或协议版本不匹配，继续读只会越来越乱
			v.logf("影像: 帧头无效 %v，断开重连", err)
			return
		}
		if err := h.ok(); err != nil {
			v.bad.Add(1)
			v.logf("影像: 尺寸/长度不符 (%dx%d ulen=%d)：%v", h.W, h.H, h.ULen, err)
			return
		}

		src := v.takeBuf(int(h.CLen))
		if _, err := io.ReadFull(c, src); err != nil {
			v.putBuf(src)
			return
		}

		out := v.takeBuf(int(h.ULen))
		t0 := time.Now()
		derr := v.decode(h.Codec, src, out)
		v.markDecode(time.Since(t0))
		if derr != nil {
			v.bad.Add(1)
			v.logBad("影像: %v", derr)
			v.putBuf(src)
			v.putBuf(out)
			continue
		}
		v.putBuf(src)

		// 先直写、再入槽：入槽之后主循环随时可能把这帧的缓冲 release 回池子
		// 复用，所以像素必须在 publish 之前就已经进了 fb1。
		v.flushTo(h.Side, out, int(h.W), int(h.H))
		v.publish(&mirFrame{seq: h.Seq, side: h.Side, w: int(h.W), h: int(h.H), pix: out})
		v.lastRecv.Store(time.Now().UnixMilli())
	}
}

// decode 把压缩载荷解到 out。返回 nil 表示 out 里是一整帧合法像素。
//
// 单独抽成方法是为了能在主机上直接对三种编码跑单测：serve() 依赖 net.Conn，
// 而"解压这一段对不对"恰恰是最容易出错、又最难在实车上看见的地方。
func (v *mirServer) decode(codec uint8, src, out []byte) error {
	switch codec {
	case mirCodecRaw:
		if len(src) < len(out) {
			return fmt.Errorf("raw 载荷偏短 (%d < %d)，codec 字段写错？", len(src), len(out))
		}
		copy(out, src)
		return nil
	case mirCodecZstd:
		got, err := v.dec.DecodeAll(src, out[:0])
		if err != nil {
			return fmt.Errorf("zstd 解压失败: %w", err)
		}
		if len(got) != len(out) {
			return fmt.Errorf("zstd 解压长度不符 (%d/%d)", len(got), len(out))
		}
		return nil
	case mirCodecDeflate:
		// 车机端 Deflater 输出的是 zlib 流（2 字节 CMF/FLG 头 + 裸 deflate + adler32 尾）。
		// 这里只解裸 deflate 段：跳过 2 字节头，读满 out 就返回，不读尾部校验。
		// 用 klauspost 的 flate：标准库在 A9 上解 1MB 要 ~100ms，是端到端只有 7fps 的直接原因。
		if len(src) < 2 {
			return fmt.Errorf("deflate 载荷过短 (%d)", len(src))
		}
		// FDICT 置位表示流带预置字典，裸解会错，退回标准库的 zlib 完整路径
		if src[1]&0x20 != 0 {
			zr, zerr := zlib.NewReader(bytes.NewReader(src))
			if zerr != nil {
				return fmt.Errorf("deflate 头损坏: %w", zerr)
			}
			n, rerr := io.ReadFull(zr, out)
			zr.Close()
			if rerr != nil {
				return fmt.Errorf("deflate 解压不足 (%d/%d): %w", n, len(out), rerr)
			}
			return nil
		}
		v.decMu.Lock()
		defer v.decMu.Unlock()
		if v.zr == nil {
			v.zsrc = bytes.NewReader(nil)
			v.zr = flate.NewReader(v.zsrc)
		}
		v.zsrc.Reset(src[2:])
		if r, ok := v.zr.(flate.Resetter); ok {
			if err := r.Reset(v.zsrc, nil); err != nil {
				return fmt.Errorf("deflate 重置失败: %w", err)
			}
		}
		n, rerr := io.ReadFull(v.zr, out)
		if rerr != nil {
			return fmt.Errorf("deflate 解压不足 (%d/%d): %w", n, len(out), rerr)
		}
		return nil
	}
	return fmt.Errorf("未知 codec=%d", codec)
}

// publish 放入最新帧。槽深度 1：新帧直接顶掉旧帧（保新丢旧），
// 渲染线程慢的时候不会积压出越来越大的延迟。
func (v *mirServer) publish(f *mirFrame) {
	v.mu.Lock()
	old := v.pending
	v.pending = f
	v.mu.Unlock()
	if old != nil && old.pix != nil {
		v.putBuf(old.pix)
	}
	if old != nil {
		v.drops.Add(1)
	}
}

// take 取走当前待渲染帧；没有则返回 nil。缓冲由调用方用完归还。
func (v *mirServer) take() *mirFrame {
	v.mu.Lock()
	f := v.pending
	v.pending = nil
	v.mu.Unlock()
	return f
}

func (v *mirServer) release(f *mirFrame) {
	if f != nil && f.pix != nil {
		v.putBuf(f.pix)
	}
}

// stale 判断距最后一次收帧是否超过阈值。
func (v *mirServer) stale(nowMs int64) bool {
	last := v.lastRecv.Load()
	if last == 0 {
		return true
	}
	return nowMs-last > mirStaleMS
}

// on 返回是否处于"有车机在推"的状态（有连接且没超时）。
func (v *mirServer) on(nowMs int64) bool {
	return v.conns.Load() > 0 && !v.stale(nowMs)
}
