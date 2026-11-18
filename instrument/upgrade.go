package main

// 待升级二进制（p7assist.new）的采纳判定。
//
// 教训（2026-11-15 实车）：FTP 上传是流式写入，主循环只判断「>64KB 且头 4 字节是 ELF」
// 就立刻 rename+exec，结果在一个 163320 字节的半截文件上触发替换，进程起来就退出，
// 仪表上的小部件整块消失，只能靠看门狗清屏兜底。
//
// 现在的规则：同一个 (size, mtime) 指纹连续观察到 upgradeHoldS 秒不变才采纳；
// 传输中大小每隔一帧就变，指纹一直刷新，永远进不了稳定期。

import "time"

// upgradeHoldS 待升级文件需要保持不变的秒数
const upgradeHoldS = 2

// upgradeStable 是否已可以采纳：
//
//	sig      本次看到的指纹（size/mtime）
//	prevSig  上一次的指纹（空字符串表示还没见过）
//	since    当前指纹首次被看到的时刻
func upgradeStable(sig, prevSig string, since, now time.Time) bool {
	if sig == "" || sig != prevSig {
		return false
	}
	return now.Sub(since) >= upgradeHoldS*time.Second
}
