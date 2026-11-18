package main

// 充电态：车在充电时不要把前景层（fb1）盖在原厂充电页上。
//
// 只读仪表本地 DBus 的三条信号，不依赖车机 ext.json。判定规则与 v1.1.0
// 「充电让出」worker（2026-11-07 实车验证）一致：任一信号命中即视为充电中。
//
//	host-mcu-charging-state    → get_host_mcu_charging_state     (iiix)   2 = 充电中
//	  依据：原厂 SOC 配色 setElectricityColorForAntiShake 里
//	        「充电中(mcu_charging_state==2)→色3」，固件级证据（A 级）
//	host-vcu-charge-gun-status → get_host_vcu_charge_gun_status  (iiix)   2 = 充电中
//	  依据：同一条 VCU_CHARGE_GUN_STATUS 在车机 CarProperty 上实测 0=未充 / 2=充电中
//	        （v1.1.0「充电让出」worker，2026-11-07 实车）
//	host-echarge-cur           → get_host_echarge_cur            (iiadx)  A
//	  仅在前两条都读不到时兜底：有充电电流就按充电中处理
//
// 读不到一律按「未充电」：总线挂掉时宁可继续按老样子画，也不要凭空把整块小部件关掉，
// 否则用户会以为程序死了。原始读数会写进日志和 status.json，方便实车标定枚举。

import (
	"fmt"
	"os"
	"strings"
)

const (
	// 充电判定的标定值（两条状态量）
	chargeMcuOn   = 2 // host-mcu-charging-state：2=充电中
	chargeGunOn   = 2 // host-vcu-charge-gun-status：2=充电中
	chargeCurMinA = 0.5

	// 去抖：连续 2 次（约 2 秒）一致才切换，插枪/拔枪瞬间不来回开关图层
	chargeDebounceN = 2
	// 轮询间隔
	chargePollMS = 1000
)

// chargeProbe 一次采样的原始读数；ok=false 表示该条读不到
type chargeProbe struct {
	mcu, gun, st int32
	cur          float64
	okMcu        bool
	okGun        bool
	okSt         bool
	okCur        bool
}

// charging 任一已标定信号命中即视为充电中。
// cur 只在两条状态量都读不到时才参与，避免行驶中电流量（若有）误触发。
func (p chargeProbe) charging() bool {
	if p.okMcu && p.mcu == chargeMcuOn {
		return true
	}
	if p.okGun && p.gun == chargeGunOn {
		return true
	}
	if !p.okMcu && !p.okGun && p.okCur && p.cur >= chargeCurMinA {
		return true
	}
	return false
}

// String 原始读数，形如 mcu=0 gun=0 st=15 cur=0.0；读不到的显示 ?
func (p chargeProbe) String() string {
	num := func(ok bool, f string, v any) string {
		if !ok {
			return "?"
		}
		return fmt.Sprintf(f, v)
	}
	return fmt.Sprintf("mcu=%s gun=%s st=%s cur=%s",
		num(p.okMcu, "%d", p.mcu), num(p.okGun, "%d", p.gun),
		num(p.okSt, "%d", p.st), num(p.okCur, "%.1f", p.cur))
}

// chgDebounce 充电判定的去抖状态机。
// 首次采样直接采信：开机时如果就在充电，不能先画一帧再把原厂充电页顶掉。
type chgDebounce struct {
	on    bool
	cnt   int
	ready bool
}

func (d *chgDebounce) step(raw bool, need int) bool {
	if !d.ready {
		d.ready, d.on, d.cnt = true, raw, 0
		return d.on
	}
	if raw == d.on {
		d.cnt = 0
		return d.on
	}
	if d.cnt++; d.cnt >= need {
		d.on, d.cnt = raw, 0
	}
	return d.on
}

// chargeReader 只抽充电判定用到的两个 getter。
// *busCtx 天然满足（getI / getAD 都是它现有的方法），测试里换成假实现即可，
// 不用连 DBus、不用真车、也不碰 fb1 —— 判定逻辑与硬件彻底解耦。
type chargeReader interface {
	getI(method string) (int32, bool)
	getAD(method string) ([]float64, bool)
}

// readChargeProbe 采一次原始读数。读不到的字段 ok=false，交给 charging() 按未充电处理。
func readChargeProbe(r chargeReader) chargeProbe {
	var p chargeProbe
	if r == nil {
		return p
	}
	if v, ok := r.getI("get_host_mcu_charging_state"); ok {
		p.mcu, p.okMcu = v, true
	}
	if v, ok := r.getI("get_host_vcu_charge_gun_status"); ok {
		p.gun, p.okGun = v, true
	}
	if v, ok := r.getI("get_host_echarge_status"); ok {
		p.st, p.okSt = v, true
	}
	if a, ok := r.getAD("get_host_echarge_cur"); ok && len(a) > 0 {
		p.cur, p.okCur = a[0], true
	}
	return p
}

// chargeState 充电判定的全部状态：原始读数 + 去抖 + 结论。
// 刻意不依赖 box / fb / DBus，PC 上可以直接喂假数据把整条链路跑一遍
// （见 charge_mock_test.go）。并发保护由调用方（box.mu）负责。
type chargeState struct {
	probe chargeProbe
	seen  bool
	deb   chgDebounce
	hold  bool
}

// apply 推进一步，返回去抖后的结论，以及是否首次采样、上一步的结论。
// 调用方用 first / prevHold 决定打哪条日志。
func (s *chargeState) apply(p chargeProbe) (hold, first, prevHold bool) {
	first = !s.seen
	prevHold = s.hold
	s.probe, s.seen = p, true
	s.hold = s.deb.step(p.charging(), chargeDebounceN)
	return s.hold, first, prevHold
}

// raw 原始读数（写 status.json / 日志，实车标定枚举时看它）
func (s *chargeState) raw() string {
	if !s.seen {
		return ""
	}
	return s.probe.String()
}

// chgWord 日志用词
func chgWord(hold bool) string {
	if hold {
		return "充电中"
	}
	return "未充电"
}

// resolveHold 把三路输入合成最终结论：
//
//	charge.force（调试/应急强制，>=0 生效） > 车况去抖结果；features.chargeYield=false 一票否决
//
// 顺序与主循环原来那段保持一致，抽出来是为了能在 PC 上把优先级也一起测掉。
func resolveHold(cfg Config, deb bool, force int) bool {
	hold := deb
	if force >= 0 {
		hold = force == 1
	}
	if !cfg.Features.ChargeYield {
		hold = false
	}
	return hold
}

// readChargeForce 调试/应急开关：<appDir>/charge.force
//
//	echo 1 > /data/p7assist/charge.force   # 强制按充电处理：停止绘制、让出原厂页面
//	echo 0 > /data/p7assist/charge.force   # 充电中也不让出（强制绘制）
//	rm -f /data/p7assist/charge.force      # 恢复按车况判断（默认）
//
// 返回 1 / 0 / -1（未设置或内容无法识别）
func readChargeForce(dir string) int {
	b, err := os.ReadFile(dir + "/charge.force")
	if err != nil {
		return -1
	}
	switch strings.ToLower(strings.TrimSpace(string(b))) {
	case "1", "on", "true", "yes":
		return 1
	case "0", "off", "false", "no":
		return 0
	}
	return -1
}

// screenWhy 关层/开层的原因，只影响日志可读性
func screenWhy(enabled, hold bool) string {
	switch {
	case !enabled && hold:
		return "全局开关=off + 充电中"
	case !enabled:
		return "全局开关=off"
	case hold:
		return "充电中，让出原厂充电页"
	}
	return "恢复"
}
