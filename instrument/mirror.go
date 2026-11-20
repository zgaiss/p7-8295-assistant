package main

// 打灯盲区影像（mirror）：车机把 960×540 的 RGB565 帧经 zstd 压缩推到仪表，
// 仪表解压后按行 memmove 进 fb1 的影像矩形。
//
// 为什么是"预转 565 + 无损压缩"，而不是 JPEG / H.264：
//
//	仪表写屏实测 175MB/s（2.76MB 全屏 15.8ms），JPEG 软解只有约 20M 像素/s
//	（960×360 要 16.5ms）。960×540 软解一帧 26ms，只够 6~10fps，还要吃满
//	一个核；链路又只有 92Mbps，裸 565 跑 15fps 需要 15MB/s。两头都紧，
//	唯一能同时绕开的是"车机端把像素准备好，仪表端只搬内存"。
//
// 命名用 mirror 而不是 video：daemon 里的 videoMode 指的是充电页那套本地
// 播放器，两者是完全不同的东西，前缀必须分开，否则读代码时极易混淆。
//
// 本文件只放协议与帧结构，不依赖 fb / net，方便在主机上跑单测。

import (
	"encoding/binary"
	"errors"
)

const (
	mirMagic   = "P7VD" // 帧头魔数
	mirHdrLen  = 24     // magic4 + 4×1 + seq4 + w2 + h2 + clen4 + ulen4
	mirVersion = 1

	mirCodecRaw     = 0 // 未压缩：只用于本机联调与单元测试
	mirCodecZstd    = 1 // zstd：主机侧联调/压测用
	mirCodecDeflate = 2 // zlib/deflate：车机端实车用
	// 为什么要 deflate：车机端是纯 Java，没有 zstd 实现（zstd-jni 要打进
	// lib/arm64-v8a/*.so，当前 APK 构建链路只打 dex）。而 JDK 自带
	// Deflater（native zlib，level 1 约 50~100MB/s），压缩率对摄像头
	// 画面约 1.3~1.6×，足够把 960×540/10fps 从 10.4MB/s 压到 7MB/s 以内。
	// 仪表端 Go 标准库 compress/flate 解同一份数据，零新增依赖。

	mirSideNone  = 0 // 撤区
	mirSideLeft  = 1
	mirSideRight = 2

	// 影像区几何（1920×720 绝对坐标）。
	//
	// 960 = 屏宽 1/2，540 = 960×9/16，与 AVM 源严格 16:9，不拉伸。
	// y 从 60 起：顶部让出档位条；到 600 止：底部让出电量/续航/时间。
	// 宽高都是偶数，行字节 1920，x0*2 天然 4 字节对齐，可以整行 copy。
	mirZoneX = 0
	mirZoneY = 60
	mirZoneW = 960
	mirZoneH = 540

	// 车机侧实际推流的源尺寸。
	//
	// 960×540 是 1:1 直写（整行 memcpy，最省）；640×360 是 raw 模式下的
	// 降量方案：raw 一帧 960×540 要 1.01MB，链路 11MB/s 只能 10.9fps，
	// 这正是"仪表零解码也还是 10fps"的原因。降到 640×360 后一帧 460KB，
	// 链路上限抬到 ~23fps，仪表用最近邻放大 1.5× 补回 960×540，
	// 代价是 6ms 左右的放大耗时，远小于原来 deflate 解压的 80ms。
	mirSrcW = 640
	mirSrcH = 360

	// 断流阈值：超过这个时间没有新帧就撤区。
	// 车机 15fps 时帧间隔 67ms，800ms 相当于连续丢 12 帧，足以判定真断。
	mirStaleMS = 800
)

var (
	errMirBuf   = errors.New("mirror: 帧头太短")
	errMirMagic = errors.New("mirror: 魔数不匹配")
	errMirVer   = errors.New("mirror: 版本不支持")
	errMirGeom  = errors.New("mirror: 尺寸不符")
)

// mirHeader 是每帧固定 24 字节的头。小端，与车机端 Kotlin/Java 的
// ByteBuffer.order(LITTLE_ENDIAN) 对齐。
type mirHeader struct {
	Ver   uint8
	Codec uint8
	Side  uint8
	Flags uint8
	Seq   uint32
	W     uint16
	H     uint16
	CLen  uint32 // 压缩后（= 实际跟在头后面的字节数）
	ULen  uint32 // 解压后（= w*h*2）
}

func (h mirHeader) marshal() []byte {
	b := make([]byte, mirHdrLen)
	copy(b, mirMagic)
	b[4] = h.Ver
	b[5] = h.Codec
	b[6] = h.Side
	b[7] = h.Flags
	binary.LittleEndian.PutUint32(b[8:], h.Seq)
	binary.LittleEndian.PutUint16(b[12:], h.W)
	binary.LittleEndian.PutUint16(b[14:], h.H)
	binary.LittleEndian.PutUint32(b[16:], h.CLen)
	binary.LittleEndian.PutUint32(b[20:], h.ULen)
	return b
}

func parseMirHeader(b []byte) (mirHeader, error) {
	if len(b) < mirHdrLen {
		return mirHeader{}, errMirBuf
	}
	if string(b[:4]) != mirMagic {
		return mirHeader{}, errMirMagic
	}
	h := mirHeader{
		Ver:   b[4],
		Codec: b[5],
		Side:  b[6],
		Flags: b[7],
		Seq:   binary.LittleEndian.Uint32(b[8:]),
		W:     binary.LittleEndian.Uint16(b[12:]),
		H:     binary.LittleEndian.Uint16(b[14:]),
		CLen:  binary.LittleEndian.Uint32(b[16:]),
		ULen:  binary.LittleEndian.Uint32(b[20:]),
	}
	if h.Ver != mirVersion {
		return h, errMirVer
	}
	return h, nil
}

// ok 校验尺寸与载荷长度是否自洽。
//
// 只接受两种源尺寸：960×540（1:1 整行 memcpy）与 640×360（最近邻放大
// 1.5× 到影像区）。协议头里带 W/H 本来就是为了让仪表能判断该怎么搬，
// 但仪表只有一块固定矩形，所以白名单比"尽量凑"安全 —— 凑出来的画面会错位。
func (h mirHeader) ok() error {
	w, hh := int(h.W), int(h.H)
	if !((w == mirZoneW && hh == mirZoneH) || (w == mirSrcW && hh == mirSrcH)) {
		return errMirGeom
	}
	ulen := w * hh * 2
	if int(h.ULen) != ulen {
		return errMirGeom
	}
	if h.CLen == 0 || h.CLen > h.ULen {
		return errMirGeom
	}
	return nil
}

// mirFrame 是网络线程交给渲染线程的一帧。pix 已经是 RGB565（小端，
// 与 fb1 内存布局一致），渲染线程拿到就整行 copy，不做任何转换。
//
// w/h 是这一帧的源尺寸：960×540 走整行 memcpy，640×360 走放大。
// 尺寸必须跟着帧走而不是读全局常量——同一路连接在不同版本的车机
// 之间切换时，只有帧头说的算。
type mirFrame struct {
	seq  uint32
	side uint8
	w    int
	h    int
	pix  []byte
}

// mirZoneRect 返回某一侧影像区在屏幕上的矩形。
func mirZoneRect(side uint8) rect {
	switch side {
	case mirSideLeft:
		return rect{mirZoneX, mirZoneY, mirZoneX + mirZoneW, mirZoneY + mirZoneH}
	case mirSideRight:
		x := float64(frameW - mirZoneW)
		return rect{x, mirZoneY, x + mirZoneW, mirZoneY + mirZoneH}
	}
	return rect{}
}

func mirSideName(side uint8) string {
	switch side {
	case mirSideLeft:
		return "左侧"
	case mirSideRight:
		return "右侧"
	}
	return "无"
}
