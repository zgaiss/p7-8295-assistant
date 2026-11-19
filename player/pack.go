// 帧包解析与 2 倍复制：不含平台相关代码，PC 上可直接单元测试。
package main

import (
	"encoding/binary"
	"fmt"
	"os"
	"unsafe"
)

type pack struct {
	data   []byte
	count  int
	w, h   int
	fps    int
	off    []uint32 // 每帧压缩数据的起始偏移
	clen   []uint32 // 每帧压缩长度
	rawLen int      // 单帧解压后长度 = w*h*2
}

func loadPack(path string) (*pack, error) {
	d, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	if len(d) < 24 || string(d[0:4]) != "P7LZ" {
		return nil, fmt.Errorf("bad pack magic")
	}
	p := &pack{data: d}
	ver := binary.LittleEndian.Uint32(d[4:8])
	if ver != 1 {
		return nil, fmt.Errorf("unsupported version %d", ver)
	}
	p.count = int(binary.LittleEndian.Uint32(d[8:12]))
	p.w = int(binary.LittleEndian.Uint32(d[12:16]))
	p.h = int(binary.LittleEndian.Uint32(d[16:20]))
	p.fps = int(binary.LittleEndian.Uint32(d[20:24]))
	if p.count <= 0 || p.count > 20000 || p.w <= 0 || p.w > 4096 || p.h <= 0 || p.h > 4096 {
		return nil, fmt.Errorf("bad header %d %dx%d", p.count, p.w, p.h)
	}
	p.rawLen = p.w * p.h * 2
	p.off = make([]uint32, p.count)
	p.clen = make([]uint32, p.count)
	pos := uint32(24)
	for i := 0; i < p.count; i++ {
		if int(pos)+4 > len(d) {
			return nil, fmt.Errorf("truncated at frame %d", i)
		}
		cl := binary.LittleEndian.Uint32(d[pos : pos+4])
		pos += 4
		if int(pos)+int(cl) > len(d) {
			return nil, fmt.Errorf("truncated data at frame %d", i)
		}
		p.off[i] = pos
		p.clen[i] = cl
		pos += cl
	}
	return p, nil
}

// render2x 960×360 → 1920×720 两倍整数复制（和 C 版同一做法，保证像素对齐）。
//
// 两处提速：一是把「4 个字节逐个赋值」换成一次 32 位写（每源像素 -> 两个目标像素），
// 二是同一行的下半行直接 copy —— 上下两行内容完全相同，没必要各算一遍。
// 这段在 24fps 下每帧要跑 1920×720 次，慢一点就是好几毫秒。
func render2x(src, dst []byte, w, h, outW int) {
	for y := 0; y < h; y++ {
		s := src[y*w*2 : (y+1)*w*2]
		d0 := dst[(y*2)*outW*2 : (y*2+1)*outW*2]
		d1 := dst[((y*2)+1)*outW*2 : ((y*2)+2)*outW*2]
		for x := 0; x < w; x++ {
			p := uint32(s[x*2]) | uint32(s[x*2+1])<<8
			q := p | p<<16
			*(*uint32)(unsafe.Pointer(&d0[x*4])) = q
		}
		copy(d1, d0)
	}
}

