//go:build linux

package main

// 依赖 box（daemon.go 中定义，仅 Linux/ARM 可用）的充电页运行时逻辑。
// 单独拆到本文件，使 chargepage.go 的纯逻辑（状态推导、排版、预览渲染）
// 能在 macOS / Windows 上编译，从而让 go test 与 render 预览在 PC 上可用。

import "time"

func collectChargeLoop(c *busCtx, b *box, stop <-chan struct{}) {
	for {
		b.pollChargePage(c)
		select {
		case <-stop:
			return
		case <-time.After(chgPollMS * time.Millisecond):
		}
	}
}

func (b *box) pollChargePage(c *busCtx) {
	var ci chargeInfo
	mcu, okMcu := c.getI("get_host_mcu_charging_state")
	gun, okGun := c.getI("get_host_vcu_charge_gun_status")
	st, okSt := c.getI("get_host_echarge_status")
	cur, okCur := c.getAD("get_host_echarge_cur")
	vol, okVol := c.getAD("get_host_echarge_voltage")
	pow, okPow := c.getAD("get_host_echarge_power")
	left, okLeft := c.getI("get_host_echarge_limit_time")
	soc, okSoc := c.getI("get_host_vcu_bms_socdisp")
	rng, okRng := c.getI("get_host_vcu_dstbat_disp")
	dur, okDur := c.getI("get_host_icm_charging_duration")

	if okMcu {
		ci.mcu = int(mcu)
	}
	if okGun {
		ci.gun = int(gun)
	}
	if okSt {
		ci.state = int(st)
	}
	// getAD 返回的 double[] 不是纯数值数组，前几位可能是标志位，快充时后面还会混入峰值/限值。
	// 实测形态（2026-11-22 快充，rawCur/rawVolt 已落盘）：
	//   电压 慢充 [1, 210.8] / 快充 [2, 2, 409.1]
	//   电流 慢充 [29.5, 29.5] / 快充 [2, 2, 71.8, 471.8, 471.8]
	// 末尾的 471.8 是峰值电流，取 max 会把 71.8A 读成 471.8A、功率虚高到 193kW。
	// 慢充数组形态简单（值重复两次），取 max 恰好正确，所以之前没暴露。
	// 统一改为：跳过标志位，取第一个真实值。
	if okCur {
		ci.cur = firstRealValue(cur, 2)
	}
	if okVol {
		ci.volt = firstRealValue(vol, chgVoltMin)
	}
	if okPow && len(pow) > 0 && pow[0] > 0 {
		ci.power = pow[0]
	} else if ci.volt > 0 && ci.cur > 0 {
		ci.power = ci.volt * ci.cur / 1000.0
	}
	// 剩余时间：交付文档明确指出未充电时可能是 2555 哨兵值，另外超过 24 小时的
	// 数值不可信（原厂自己也不会显示）。这里同时挡掉，避免充电动画上出现
	// "剩余 2555 分钟" 这种荒唐读数。
	if okLeft && left > 0 && left < 1440 && left != 2555 {
		ci.leftMin = int(left)
	}
	if okSoc && soc >= 0 && soc <= 100 {
		ci.soc = int(soc)
	}
	if okRng && rng >= 0 && rng < 2000 {
		ci.rangeKm = int(rng)
	}
	if okDur && dur >= 0 && dur < 10000 {
		ci.durMin = int(dur)
	}
	// 充电中判定走非对称去抖（进快出慢），避免 DBus 偶发超时把 chg.on 读成 false
	// 而误退视频模式——那会带来约 6 秒渲染阻塞 + 一次黑闪（Issue #7 主因）。
	// 电流数组读不到视为不可信；桩状态读不到时看电流；两者都读不到时保持上次值。
	prevOn := b.s.chg.on
	curOK := okCur && len(cur) > 0
	switch {
	case !curOK && !okMcu:
		// 两条关键输入都读不到。前 chgBlindMax 轮保持上次结论，避免 DBus 偶发
		// 超时直接打断视频；但超过上限必须保守判为未充电，否则一次误判之后
		// 永远保持 true，视频模式退不出来（2026-11-19 实车复现）。
		b.chgBlind++
		if b.chgBlind >= chgBlindMax {
			ci.on = false
			b.chgOnDeb.reset()
		} else {
			ci.on = prevOn
		}
	default:
		b.chgBlind = 0
		// 判定规则必须与 charge.go 的 chargeProbe.charging() 完全一致：
		// MCU 状态 / 枪状态任一命中即为充电中；**电流只在两者都读不到时才兜底**。
		// 之前这里让电流无条件参与，实测 get_host_echarge_cur 会返回 [0,100] 这类
		// 带占位值的数组，取最大值后 cur=100 ≥ 阈值，于是在 mcu=0（明确未充电）时
		// 被判成充电中，视频模式无故长开（2026-11-19 实车复现，见 chgraw.json）。
		raw := (okMcu && (mcu == mcuChargingA || mcu == mcuChargingB)) ||
			(okGun && gun == chargeGunOn) ||
			(!okMcu && !okGun && curOK && ci.cur >= chgCurMinA)
		ci.on = b.chgOnDeb.step(raw, chgOnUpN, chgOnDownN)
	}
	ci.ok = okMcu || okGun || okSt

	// 车辆侧判断量（属性名来自 dbus_props.txt 的内省结果，不是猜的）
	ci.batHot = intOr(c, "get_host_vcu_bat_hot_disp")
	ci.batCold = intOr(c, "get_host_vcu_batcold_disp")
	ci.batFault = intOr(c, "get_host_vcu_batt_fault_disp")
	ci.hvCutoff = intOr(c, "get_host_vcu_hv_cutoff_disp")
	ci.ind12v = intOr(c, "get_host_vcu_ind_12v_bat")
	ci.pwLimit = intOr(c, "get_host_vcu_powerlimitation_dsp")
	ci.socLow = intOr(c, "get_host_vcu_bms_soclow_status")
	ci.chgPwLimit = intOr(c, "get_host_esocmaxchg_pwlimit")
	ci.portL = intOr(c, "get_host_bcm_l_charger_port")
	ci.portR = intOr(c, "get_host_bcm_r_charger_port")
	ci.portLFault = intOr(c, "get_host_bcm_chargeport_left_fault")
	ci.portRFault = intOr(c, "get_host_bcm_echargeport_right_fault")
	ci.apptH = intOr(c, "get_host_tbox_appoint_chg_set_hour")
	ci.apptM = intOr(c, "get_host_tbox_appoint_chg_set_min")
	ci.voltLevel = intOr(c, "get_host_echarge_voltage_level")
	ci.batPrepMin = int(adOr(c, "get_host_ebat_prepare_time"))
	if ci.batPrepMin <= 0 {
		ci.batPrepMin = intOr(c, "get_host_ebat_prepare_time")
	}
	ci.alarmID = intOr(c, "get_host_ealarm_id")

	tm := readChgTemps(c)

	b.mu.Lock()
	prev := b.s.chg
	if ci.ok {
		// 占位值清理必须在峰值统计/状态推导之前：
		// DBus 在未充电、尤其"插枪等待桩启动"时，echarge_* 会返回固定占位
		// volt=100 / cur=100 / power=10。照单全收会把峰值写成 100A，
		// 并让 deriveState 的 cur>=0.5 误判为"充电中"，界面显示假的 100V/100A/10kW。
		// 原条件要求 gun/mcu/state 全 0，但插枪等桩时 gun=1，条件不成立。
		// 改为按数值特征识别；只要没在真正充电就清掉这组占位读数。
		placeholder := ci.volt >= 99 && ci.volt <= 101 &&
			ci.cur >= 99 && ci.cur <= 101 && ci.power >= 9 && ci.power <= 11
		idleAllZero := ci.gun == 0 && ci.mcu == 0 && ci.state == 0
		if idleAllZero || (!ci.on && placeholder) {
			ci.volt, ci.cur, ci.power = 0, 0, 0
			ci.leftMin, ci.durMin = 0, 0
		}
		// 会话峰值：新会话（刚插枪开始充）清零，充电中持续跟踪；
		// 不在充电时保留上一轮的峰值与结论，让"充电完成"页还能显示桩类型。
		switch {
		case ci.on && !prev.on:
			ci.maxPower, ci.maxCur, ci.maxVolt = 0, 0, 0
			ci.sessSec = 0
		case ci.on:
			ci.maxPower, ci.maxCur, ci.maxVolt = prev.maxPower, prev.maxCur, prev.maxVolt
			ci.sessSec = prev.sessSec + 1
		default:
			ci.maxPower, ci.maxCur, ci.maxVolt = prev.maxPower, prev.maxCur, prev.maxVolt
			ci.sessSec = prev.sessSec
		}
		if ci.power > ci.maxPower {
			ci.maxPower = ci.power
		}
		if ci.cur > ci.maxCur {
			ci.maxCur = ci.cur
		}
		if ci.volt > ci.maxVolt {
			ci.maxVolt = ci.volt
		}
		ci.pile = derivePile(ci)
		ci.st = deriveState(ci, prev.st)
		// 完成态尚未拔枪时也要继续整屏接管：否则视频播放器退出、常规数据
		// 直接叠在原厂完成页上，既没有动画也没屏蔽数据。
		// 拔枪（gun 归 0）或完全空闲即退出接管。
		pluggedNow := ci.gun >= int(gunPlugged) || ci.portL != 0 || ci.portR != 0
		ci.pageOn = ci.on || (ci.st == chgDone && pluggedNow)
		ci.warns = deriveChargeWarns(ci, ci.st, tm)
		// 完全空闲（未插枪/未充电/状态机空闲）时连峰值和桩类型一起清，
		// 让下次插枪从干净状态开始；插枪等桩的情况上面已清瞬时占位读数，
		// 但保留上一轮峰值，供"充电完成"页继续显示桩类型。
		if idleAllZero {
			ci.sessSec = 0
			ci.maxPower, ci.maxCur, ci.maxVolt = 0, 0, 0
			ci.pile = pileUnknown
			ci.warns = nil
		}
		b.s.chg = ci
	}
	pageOn := b.cfg.Features.ChargePage || b.cfg.Features.ChargeDebug
	b.mu.Unlock()

	// 原始值落盘：实车充一次电就能把 mcu/gun/state 的枚举对齐
	if pageOn {
		dumpChgRaw(ci, tm, vol, cur, pow)
	}
}
