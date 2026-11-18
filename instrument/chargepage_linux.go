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
	if okCur {
		// 电流实测返回 [29.5, 29.5]，取最大值兼容 [flag, 值] / [值] 两种形态
		for _, v := range cur {
			if v > ci.cur {
				ci.cur = v
			}
		}
	}
	// 电压**不能取 [0]**：实测 get_host_echarge_voltage 返回 [1, 210.8]，
	// 第一个元素是标志位，真正的电压在第二个。以前取 vol[0]=1 被 chgVoltMin 挡掉，
	// 结果电压和功率整列都显示 "--"（2026-11-18 真车充电时才发现）。
	// 这里取数组里最大的合理值，[220] 和 [1, 210.8] 两种形态都能拿到。
	if okVol {
		for _, v := range vol {
			if v > chgVoltMin && v > ci.volt {
				ci.volt = v
			}
		}
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
	ci.on = ci.cur >= chgCurMinA || (okMcu && (mcu == mcuChargingA || mcu == mcuChargingB))
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
		ci.warns = deriveChargeWarns(ci, ci.st, tm)
		b.s.chg = ci
	}
	pageOn := b.cfg.Features.ChargePage || b.cfg.Features.ChargeDebug
	b.mu.Unlock()

	// 原始值落盘：实车充一次电就能把 mcu/gun/state 的枚举对齐
	if pageOn {
		dumpChgRaw(ci, tm)
	}
}
