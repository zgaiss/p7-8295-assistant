package main

// 矩形集合运算。目前只有打灯影像需要：把主循环的脏矩形减掉影像独占区，
// 拆成互不相交的几块分别上屏。
//
// 减法而不是"整体跳过"：脏矩形可能横跨影像区（左边胎压、右边歌词同时变），
// 整体跳过会把影像区外的内容也一起漏掉；只裁掉重叠部分才两全。

import "math"

// intersects 判断两个矩形是否有面积重叠。贴边不算重叠（x1==o.x0 时无交集）。
func (r rect) intersects(o rect) bool {
	if r.empty() || o.empty() {
		return false
	}
	return r.x0 < o.x1 && o.x0 < r.x1 && r.y0 < o.y1 && o.y0 < r.y1
}

// sameRect 判断两矩形是否完全一致（含空矩形）。
func sameRect(a, b rect) bool {
	if a.empty() && b.empty() {
		return true
	}
	return a.x0 == b.x0 && a.y0 == b.y0 && a.x1 == b.x1 && a.y1 == b.y1
}

// minus 返回 r 减去 hole 之后剩下的矩形，最多 4 块（上、下、左、右）。
//
// 拆法固定为「先上下、后左右」，结果互不重叠，调用方可以直接逐块上屏。
// 不做最小块数优化：4 块的总面积刚好等于差集，多一次 blit 调用比多算
// 几轮矩形合并便宜得多。
func (r rect) minus(hole rect) []rect {
	if r.empty() {
		return nil
	}
	if hole.empty() || !r.intersects(hole) {
		return []rect{r}
	}
	var out []rect

	// 上：hole 上边缘以上的整条
	if hole.y0 > r.y0 {
		out = append(out, rect{r.x0, r.y0, r.x1, math.Min(hole.y0, r.y1)})
	}
	// 下：hole 下边缘以下的整条
	if hole.y1 < r.y1 {
		out = append(out, rect{r.x0, math.Max(hole.y1, r.y0), r.x1, r.y1})
	}
	// 左右两块只占 hole 的纵向区间，避免和上下两条重叠
	iy0 := math.Max(r.y0, hole.y0)
	iy1 := math.Min(r.y1, hole.y1)
	if iy1 > iy0 {
		if hole.x0 > r.x0 {
			out = append(out, rect{r.x0, iy0, math.Min(hole.x0, r.x1), iy1})
		}
		if hole.x1 < r.x1 {
			out = append(out, rect{math.Max(hole.x1, r.x0), iy0, r.x1, iy1})
		}
	}

	// 兜底：没有任何一块时返回 nil，调用方不会 blit 空矩形
	res := out[:0]
	for _, q := range out {
		if !q.empty() {
			res = append(res, q)
		}
	}
	return res
}
