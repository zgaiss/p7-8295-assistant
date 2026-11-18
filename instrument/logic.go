package main

// 纯逻辑：布局动画节奏、日夜判定（与平台无关，方便单元测试）

import "time"

// stepT 推进收拢进度 0..1；t<0 表示首帧未初始化，直接对齐目标
func stepT(t, target, dtMS, zoomMs float64) float64 {
	if t < 0 {
		return target
	}
	if dtMS > 1000 {
		dtMS = 1000
	}
	if target > 0.5 {
		if t < 1 {
			t += dtMS / zoomMs
			if t > 0.998 {
				t = 1
			}
		}
		return t
	}
	if t > 0 {
		t -= dtMS / zoomMs
		if t < 0.002 {
			t = 0
		}
	}
	return t
}

// ---------- 日夜 ----------

func localNight() bool {
	h := time.Now().Hour()
	return h < 6 || h >= 18
}

func resolveNight(cfg Config, ext ExtData, screenNight bool, screenValid bool) bool {
	switch cfg.DayNight {
	case "day":
		return false
	case "night":
		return true
	}
	// 仪表优先：原厂画面本身是浅底还是深底，就是最真实的昼夜状态。
	// 它不受城市/时区影响，也不受用户亮度设置影响（亮度是硬件背光，不改像素）。
	// 只有采样不可靠（全黑、亮度落在过渡区、fb0 读不到）时才往下回落。
	if screenValid {
		return screenNight
	}
	if ext.Night != nil {
		return *ext.Night == 1
	}
	return localNight()
}
