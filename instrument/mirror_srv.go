package main

// 打灯影像接收端（mirror）：TCP 收帧 → 解压 → 交给渲染线程。
//
// 并发模型（这是"不花屏"的根本）：
//
//	网络线程只做三件事：读帧头、读载荷、解压。它**从不碰 fb1**。
//	渲染线程（主循环）每轮从这里取"最新一帧"，写进 fb1 的影像矩形。
//
// 于是 fb1 从头到尾只有一个写者。影像区和主循环画的界面区通过 rect.minus
// 保证不相交，两者不共享任何像素，不需要每帧加锁，也就不会出现
// "两套程序抢同一块显存"导致的花屏。
//
// 代价是帧率上限被主循环节拍（50ms = 20fps）压住，一期目标 15fps 正好在
// 范围内；二期换 VPU 硬解时再评估是否让解码线程直接写区。

import (
	"io"
	"net"
	"strconv"
	"sync"
	"sync/atomic"
	"time"

	"github.com/klauspost/compress/zstd"
)

// mirServer 是网络线程与渲染线程之间唯一的交接点。
type mirServer struct {
	port int
	logf func(string, ...any)

	ln  net.Listener
	dec *zstd.Decoder

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
		switch h.Codec {
		case mirCodecRaw:
			if len(src) < len(out) {
				v.bad.Add(1)
				v.putBuf(src)
				v.putBuf(out)
				continue
			}
			copy(out, src)
		case mirCodecZstd:
			got, derr := v.dec.DecodeAll(src, out[:0])
			if derr != nil || len(got) != len(out) {
				v.bad.Add(1)
				if derr != nil {
					v.logf("影像: 解压失败 %v", derr)
				}
				v.putBuf(src)
				v.putBuf(out)
				continue
			}
			out = got
		default:
			v.bad.Add(1)
			v.putBuf(src)
			v.putBuf(out)
			continue
		}
		v.putBuf(src)

		v.publish(&mirFrame{seq: h.Seq, side: h.Side, pix: out})
		v.lastRecv.Store(time.Now().UnixMilli())
	}
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
