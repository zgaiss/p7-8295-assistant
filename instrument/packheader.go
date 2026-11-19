package main

// 帧包头校验：只认已知魔数，用来挡住"上传中的半截文件"。
//
// 放在无平台约束的文件里，PC 上就能直接单元测试 —— 这段逻辑一旦错了，
// 表现是 daemon 永远认为"包没换"，读新包的播放器就得靠手动重启才生效，很难察觉。
//
// 目前两种包：
//   P7VP  JPEG 帧（C 播放器 p7videoplay）
//   P7LZ  RGB565+LZ4 帧（Go 播放器 p7vplay）

import "os"

const (
	packMagicJpeg = "P7VP"
	packMagicLz4  = "P7LZ"
)

// packHeaderOKAt 读文件头 24 字节，魔数对得上才认。
// 大小/稳定性由调用方另判（上传走 .part + RENAME，正常不会读到半截）。
func packHeaderOKAt(path string) bool {
	f, err := os.Open(path)
	if err != nil {
		return false
	}
	defer f.Close()
	var h [24]byte
	if n, err := f.Read(h[:]); err != nil || n < 24 {
		return false
	}
	magic := string(h[0:4])
	return magic == packMagicJpeg || magic == packMagicLz4
}
