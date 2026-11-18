package main

// 仪表本地 DBus 采集
//
//   服务/接口：com.xiaopeng.DomainController.Cluster
//   对象路径：/com/xiaopeng/DomainController/Cluster
//   所有 getter 返回 (ii?x) 结构：Body[0] 是内层 struct，取 [2] 才是业务值
//
// 快通道 250ms（挡位/车速，另有 PropertiesChanged 推送做即时更新）
// 慢通道 2s（四轮压力/胎温、空调温度、行程、能耗、盲区）

import (
	"fmt"
	"log"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/godbus/dbus/v5"
)

const (
	busDest  = "com.xiaopeng.DomainController.Cluster"
	busPath  = "/com/xiaopeng/DomainController/Cluster"
	busIface = "com.xiaopeng.DomainController.Cluster"
)

type busCtx struct {
	mu   sync.Mutex
	conn *dbus.Conn
	obj  dbus.BusObject
	sig  chan *dbus.Signal
	lg   *log.Logger
}

// findBusAddress 运行时发现 session bus：地址里的随机串每次开机都变，不能写死。
// 顺序：环境变量 → /tmp/dbus_env → 其它进程 environ → /proc/net/unix
func findBusAddress() string {
	if v := os.Getenv("DBUS_SESSION_BUS_ADDRESS"); v != "" {
		return v
	}
	if b, err := os.ReadFile("/tmp/dbus_env"); err == nil {
		for _, line := range strings.Split(string(b), "\n") {
			line = strings.TrimSpace(line)
			line = strings.TrimPrefix(line, "export ")
			if strings.HasPrefix(line, "DBUS_SESSION_BUS_ADDRESS=") {
				v := strings.Trim(strings.TrimPrefix(line, "DBUS_SESSION_BUS_ADDRESS="), `"'`)
				if strings.Contains(v, "dbus-") {
					return v
				}
			}
		}
	}
	if paths, _ := filepath.Glob("/tmp/dbus-*"); len(paths) > 0 {
		for _, p := range paths {
			if fi, err := os.Stat(p); err == nil && fi.Mode()&os.ModeSocket != 0 {
				return "unix:path=" + p
			}
		}
	}
	if ents, err := os.ReadDir("/proc"); err == nil {
		for _, e := range ents {
			if _, err := strconv.Atoi(e.Name()); err != nil {
				continue
			}
			b, err := os.ReadFile("/proc/" + e.Name() + "/environ")
			if err != nil {
				continue
			}
			for _, kv := range strings.Split(string(b), "\x00") {
				if strings.HasPrefix(kv, "DBUS_SESSION_BUS_ADDRESS=") {
					v := strings.TrimPrefix(kv, "DBUS_SESSION_BUS_ADDRESS=")
					if strings.Contains(v, "dbus-") {
						return v
					}
				}
			}
		}
	}
	if b, err := os.ReadFile("/proc/net/unix"); err == nil {
		for _, line := range strings.Split(string(b), "\n") {
			if i := strings.Index(line, "@/tmp/dbus-"); i >= 0 {
				return "unix:abstract=" + line[i+1:]
			}
		}
	}
	return ""
}

func connectBus() (*dbus.Conn, error) {
	addr := findBusAddress()
	if addr == "" {
		return nil, fmt.Errorf("no session bus found")
	}
	os.Setenv("DBUS_SESSION_BUS_ADDRESS", addr)
	if c, err := dbus.ConnectSessionBus(); err == nil {
		return c, nil
	}
	return dbus.ConnectSessionBus(dbus.WithAuth(dbus.AuthAnonymous()))
}

func waitBus(lg *log.Logger, max time.Duration) (*dbus.Conn, error) {
	deadline := time.Now().Add(max)
	for {
		c, err := connectBus()
		if err == nil {
			return c, nil
		}
		if time.Now().After(deadline) {
			return nil, err
		}
		lg.Printf("等待 bus: %v", err)
		time.Sleep(2 * time.Second)
	}
}

func (c *busCtx) reconnect() {
	c.mu.Lock()
	old := c.conn
	c.conn, c.obj, c.sig = nil, nil, nil
	c.mu.Unlock()
	if old != nil {
		old.Close()
	}
	nc, err := waitBus(c.lg, 30*time.Second)
	if err != nil {
		c.lg.Printf("bus 重连失败: %v", err)
		return
	}
	c.mu.Lock()
	c.conn = nc
	c.obj = nc.Object(busDest, dbus.ObjectPath(busPath))
	c.mu.Unlock()
	if err := c.enableSignals(); err != nil {
		c.lg.Printf("重连后订阅失败: %v", err)
	}
	c.lg.Printf("bus 重连成功")
}

func (c *busCtx) connected() bool {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.conn != nil
}

// callVal 取业务值：Body[0] 的内层 struct 的 [2]
func (c *busCtx) callVal(method string) (interface{}, error) {
	c.mu.Lock()
	obj := c.obj
	c.mu.Unlock()
	if obj == nil {
		return nil, fmt.Errorf("no bus")
	}
	call := obj.Call(busIface+"."+method, 0)
	if call.Err != nil {
		return nil, call.Err
	}
	if len(call.Body) == 0 {
		return nil, fmt.Errorf("empty body")
	}
	inner, ok := call.Body[0].([]interface{})
	if !ok {
		inner = call.Body
	}
	if len(inner) < 3 {
		return nil, fmt.Errorf("short body %d", len(inner))
	}
	return inner[2], nil
}

func (c *busCtx) getF(method string) (float64, bool) {
	v, err := c.callVal(method)
	if err != nil {
		return 0, false
	}
	switch n := v.(type) {
	case float64:
		return n, true
	case int32:
		return float64(n), true
	case int64:
		return float64(n), true
	case uint32:
		return float64(n), true
	}
	return 0, false
}

func (c *busCtx) getI(method string) (int32, bool) {
	v, err := c.callVal(method)
	if err != nil {
		return 0, false
	}
	switch n := v.(type) {
	case int32:
		return n, true
	case int64:
		return int32(n), true
	case uint32:
		return int32(n), true
	case float64:
		return int32(n), true
	}
	return 0, false
}

func (c *busCtx) getIA(method string) ([]int32, bool) {
	v, err := c.callVal(method)
	if err != nil {
		return nil, false
	}
	if a, ok := v.([]int32); ok {
		return a, true
	}
	return nil, false
}

func (c *busCtx) getAD(method string) ([]float64, bool) {
	v, err := c.callVal(method)
	if err != nil {
		return nil, false
	}
	if a, ok := v.([]float64); ok {
		return a, true
	}
	return nil, false
}

// ---------- 信号（挡位/车速即时更新） ----------

func (c *busCtx) enableSignals() error {
	c.mu.Lock()
	conn := c.conn
	c.mu.Unlock()
	if conn == nil {
		return fmt.Errorf("no conn")
	}
	ch := make(chan *dbus.Signal, 128)
	conn.Signal(ch)
	if err := conn.AddMatchSignal(
		dbus.WithMatchObjectPath(dbus.ObjectPath(busPath)),
		dbus.WithMatchInterface("org.freedesktop.DBus.Properties"),
	); err != nil {
		return err
	}
	c.mu.Lock()
	c.sig = ch
	c.mu.Unlock()
	return nil
}

func (c *busCtx) currentSig() chan *dbus.Signal {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.sig
}

// applySignal 把推送属性塞进快照（挡位/车速走这里做快通道）。
//
// 盲区（host-eind-lbsd/rbsd）只在 bsdSrc="bus" 时才采信：
// 这两个属性是原厂后视镜的"指示灯"态，实车验证右侧经常不刷新，
// 而默认数据源是车机 CarProperty 推来的 ext.json。之前这里无条件写入，
// 于是两条源抢同一个字段——谁后到谁赢，表现就是圆弧忽亮忽灭、左右不同步、
// 熄灭了又被一条迟到的旧信号点回来。默认 car 模式下这里必须彻底放手。
func applySignal(s *snapshot, sig *dbus.Signal, useBusBsd bool) bool {
	if sig == nil || sig.Name != "org.freedesktop.DBus.Properties.PropertiesChanged" || len(sig.Body) < 2 {
		return false
	}
	props, ok := sig.Body[1].(map[string]dbus.Variant)
	if !ok {
		return false
	}
	changed := false
	for name, v := range props {
		switch name {
		case "host-vcu-current-gearlev":
			if g, ok := v.Value().(int32); ok {
				s.gear, s.gearOK, changed = g, true, true
			}
		case "host-vcu-raw-car-speed":
			if sp, ok := v.Value().(float64); ok {
				s.speed, s.speedOK, changed = sp, true, true
			}
		case "host-eind-lbsd":
			if useBusBsd {
				if n, ok := v.Value().(int32); ok {
					s.bsdL, changed = n == 1, true
				}
			}
		case "host-eind-rbsd":
			if useBusBsd {
				if n, ok := v.Value().(int32); ok {
					s.bsdR, changed = n == 1, true
				}
			}
		}
	}
	return changed
}
