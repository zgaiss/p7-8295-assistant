package main

import "testing"

// 实车充满后 mcu=3、SOC=99，但桩仍有 3A 尾电流；完成态必须优先于电流判定，
// 否则完成页永远出不来（2026-11-23 复现：state 一直 running）。
func TestDeriveStateDoneBehindTailCurrent(t *testing.T) {
	cases := []struct {
		name string
		ci   chargeInfo
		prev chgState
		want chgState
	}{
		{
			name: "完成态带尾电流",
			ci:   chargeInfo{ok: true, mcu: 3, gun: 1, cur: 3, soc: 99, portR: 1},
			prev: chgRunning,
			want: chgDone,
		},
		{
			name: "真充电电流不被误判完成",
			ci:   chargeInfo{ok: true, mcu: 2, gun: 2, cur: 71.8, soc: 80},
			prev: chgRunning,
			want: chgRunning,
		},
		{
			name: "SOC高但未完成仍充电中",
			ci:   chargeInfo{ok: true, mcu: 2, gun: 2, cur: 5, soc: 96},
			prev: chgRunning,
			want: chgRunning,
		},
	}
	for _, c := range cases {
		if got := deriveState(c.ci, c.prev); got != c.want {
			t.Errorf("%s: deriveState = %v, want %v", c.name, got, c.want)
		}
	}
}
