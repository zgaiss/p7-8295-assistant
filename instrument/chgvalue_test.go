package main

import "testing"

// 快充实测数组后面混入峰值电流，取 max 会把 71.8A 读成 471.8A。
// 固定这里防止以后又改回"取最大值"。
func TestFirstRealValueChargeArrays(t *testing.T) {
	cases := []struct {
		name string
		in   []float64
		min  float64
		want float64
	}{
		{"慢充电流重复", []float64{29.5, 29.5}, 2, 29.5},
		{"慢充电压标志位", []float64{1, 210.8}, chgVoltMin, 210.8},
		{"快充电流混峰值", []float64{2, 2, 71.8, 471.8, 471.8}, 2, 71.8},
		{"快充电压标志位", []float64{2, 2, 409.1}, chgVoltMin, 409.1},
		{"占位值全小", []float64{0, 0, 0}, 2, 0},
		{"空数组", nil, 2, 0},
	}
	for _, c := range cases {
		got := firstRealValue(c.in, c.min)
		if got != c.want {
			t.Errorf("%s: firstRealValue(%v, %g) = %g, want %g", c.name, c.in, c.min, got, c.want)
		}
	}
}
