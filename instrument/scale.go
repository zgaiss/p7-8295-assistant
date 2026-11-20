package main

import "unsafe"

// RGB565 最近邻放大/缩小（纯函数，可在主机上单测）。
//
// 为什么需要它：raw 模式下一帧 960×540 要 1.01MB，链路实测 11MB/s，
// 也就是 10.9fps 的硬天花板——仪表就算零解码也快不起来。把车机侧采集
// 降到 640×360（460KB）后链路允许 23fps，代价是仪表要把这一小块放大
// 1.5× 填满影像区。
//
// 选最近邻而不是双线性：目标是"补回面积"而不是"补回细节"（细节在降采样
// 那一刻就丢了），而双线性要对每像素做 4 次加权，在这颗 A9 上是 3 倍开销，
// 换不来肉眼可辨的改善。
//
// 只做逐点取源，不做行缓冲：行序由调用方决定（fb1 是倒序还是正序由
// lineLen 决定），这里只管把 dw×dh 填满。

// maxScaleW 是列映射表的静态上限。影像区宽 960，留一倍余量。
const maxScaleW = 2048

// scale565NN 把 srcW×srcH 的 RGB565 最近邻映射到 dw×dh，
// 写到 dst，每行步进 dstStride 像素。row 是调用方提供的行暂存区
// （长度至少 dw*2），必须在可缓存内存里。
//
// 为什么一定要先拼行再整行 copy：fb1 是写合并/非缓存内存，逐像素写 2 字节
// 每次都是一个总线事务。实测 640×360→960×540 直接逐点写要 42.6ms（82ns/像素），
// 而先在缓存里拼行再 copy，1MB 整块写只要 6~8ms。这条差异和"blit 逐像素 95ms
// vs write565Rect 整行 6.8ms"是同一个原因。
//
// src 长度不足 srcW*srcH*2 时直接不写：宁可整帧不写，也不要写半块留残影。
func scale565NN(dst []byte, dstStride, dw, dh int, src []byte, srcW, srcH int, row []byte) {
	if dw <= 0 || dh <= 0 || srcW <= 0 || srcH <= 0 || dstStride < dw {
		return
	}
	if dw > maxScaleW || len(row) < dw*2 || len(src) < srcW*srcH*2 {
		return
	}
	if len(dst) < (dh-1)*dstStride*2+dw*2 {
		return
	}

	// 3:2 是当前唯一的实际比例（640×360 → 960×540），走专用快路径。
	// 通用路径每输出一个像素就要查一次表、做两次字节读写，34.5 万次循环在
	// 1GHz 的 A9 上要 15ms；而 3:2 下每 2 个源像素固定生成 [a, a, b] 三个目标像素，
	// 可以按"每 2 源像素一组"处理，循环次数降到 1/3，且 uint16 读写各一次到位。
	if dw*2 == srcW*3 && dw%3 == 0 {
		scale565NN32(dst, dstStride, dw, dh, src, srcW, srcH, row)
		return
	}
	scale565NNSlow(dst, dstStride, dw, dh, src, srcW, srcH, row)
}

// scale565NN32 是 3:2 比例的专用路径（dw*2 == srcW*3）。
//
// 映射关系：目标列 x 的源列是 x*2/3，所以 x=0,1,2 取源 0,0,1，x=3,4,5 取源 2,2,3，
// 也就是每 2 个源像素展开成 [a, a, b]。这里用 uint16 视图直接搬像素值，
// 省掉逐字节拼装和边界检查；对齐是安全的：源行偏移恒为偶数像素、行缓冲按
// dw*2 分配（8 字节对齐），uint16 访问都落在 2 字节对齐地址上。
func scale565NN32(dst []byte, dstStride, dw, dh int, src []byte, srcW, srcH int, row []byte) {
	r := row[:dw*2]
	groups := dw / 3

	sp := unsafe.Slice((*uint16)(unsafe.Pointer(&src[0])), len(src)/2)
	dp := unsafe.Slice((*uint16)(unsafe.Pointer(&r[0])), dw)

	built := -1
	for y := 0; y < dh; y++ {
		sy := y * srcH / dh
		if sy != built {
			base := sy * srcW
			for g := 0; g < groups; g++ {
				a := sp[base+2*g]
				b := sp[base+2*g+1]
				d := 3 * g
				dp[d] = a
				dp[d+1] = a
				dp[d+2] = b
			}
			built = sy
		}
		off := y * dstStride * 2
		copy(dst[off:off+dw*2], r)
	}
}

// scale565NNSlow 是任意比例的通用路径（保留给将来换分辨率用）。
func scale565NNSlow(dst []byte, dstStride, dw, dh int, src []byte, srcW, srcH int, row []byte) {
	// 列映射只跟宽度有关，算一次用满 540 行。
	// 用乘法而不是累加，避免 1.5× 这种非整数比例下的累积漂移。
	var cols [maxScaleW]int32
	for x := 0; x < dw; x++ {
		cols[x] = int32(x) * int32(srcW) / int32(dw)
	}

	rowBytes := dw * 2
	r := row[:rowBytes]
	// 1.5× 放大时相邻两行常来自同一源行，拼一次用两次：行数从 540 降到 360。
	built := -1
	for y := 0; y < dh; y++ {
		sy := y * srcH / dh
		if sy != built {
			srow := src[sy*srcW*2:]
			for x := 0; x < dw; x++ {
				s := int(cols[x]) * 2
				r[x*2] = srow[s]
				r[x*2+1] = srow[s+1]
			}
			built = sy
		}
		copy(dst[y*dstStride*2:y*dstStride*2+rowBytes], r)
	}
}

// scale565SameSize 是 1:1 的快路径：整行 copy，不查表。
// 960×540 的源仍然走这条，保持原有性能特征。
func scale565SameSize(dst []byte, dstStride, dw, dh int, src []byte, srcW, srcH int) bool {
	if srcW != dw || srcH != dh {
		return false
	}
	if dw <= 0 || dh <= 0 || dstStride < dw || len(src) < srcW*srcH*2 {
		return false
	}
	if len(dst) < (dh-1)*dstStride*2+dw*2 {
		return false
	}
	rowBytes := dw * 2
	for y := 0; y < dh; y++ {
		copy(dst[y*dstStride*2:y*dstStride*2+rowBytes], src[y*dw*2:y*dw*2+rowBytes])
	}
	return true
}
