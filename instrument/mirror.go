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

	mirCodecRaw  = 0 // 未压缩：只用于本机联调与单元测试
	mirCodecZstd = 1 // zstd：实车用

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
// 尺寸直接钉死 960×540：协议本身允许任意尺寸，但仪表端只有一块固定矩形，
// 对不上就没法整行 memcpy。拒绝比"尽量凑"安全 —— 凑出来的画面会错位。
func (h mirHeader) ok() error {
	if int(h.W) != mirZoneW || int(h.H) != mirZoneH {
		return errMirGeom
	}
	if int(h.ULen) != mirZoneW*mirZoneH*2 {
		return errMirGeom
	}
	if h.CLen == 0 || h.CLen > uint32(mirZoneW*mirZoneH*2) {
		return errMirGeom
	}
	return nil
}

// mirFrame 是网络线程交给渲染线程的一帧。pix 已经是 RGB565（小端，
// 与 fb1 内存布局一致），渲染线程拿到就整行 copy，不做任何转换。
type mirFrame struct {
	seq  uint32
	side uint8
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
