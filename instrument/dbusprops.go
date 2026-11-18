package main

// DBus 属性清单导出。
//
// 目的：实车标定"温度/警示/充电口"这类我们不确定属性名的量时，不用靠猜。
// daemon 启动时（只在文件不存在或版本变了时）向内省接口要一次 XML，把里面
// 所有 get_host_* 方法名抽出来写到 /data/p7assist/dbus_props.txt。
// 车机端「充电模式」页可以直接读这份，一眼看到这台固件到底暴露了哪些量。
//
// 为什么不每次启动都做：内省要序列化整个接口（几十 KB），做一次就够。

import (
	"log"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strings"
)

var reMethod = regexp.MustCompile(`<method\s+name="([A-Za-z0-9_]+)"`)

// dumpBusProps 导出总线方法名清单；force=true 时无论文件新旧都重写
func dumpBusProps(c *busCtx, lg *log.Logger, force bool) {
	dst := filepath.Join(appDir(), "dbus_props.txt")
	if !force {
		if st, err := os.Stat(dst); err == nil && st.Size() > 1024 {
			return // 已经有了，不重复做
		}
	}
	var xmlStr string
	err := c.obj.Call("org.freedesktop.DBus.Introspectable.Introspect", 0).Store(&xmlStr)
	if err != nil || len(xmlStr) < 64 {
		if lg != nil {
			lg.Printf("内省失败（不影响主功能）: %v", err)
		}
		return
	}
	names := make([]string, 0, 256)
	for _, m := range reMethod.FindAllStringSubmatch(xmlStr, -1) {
		if strings.HasPrefix(m[1], "get_") || strings.HasPrefix(m[1], "set_") {
			names = append(names, m[1])
		}
	}
	sort.Strings(names)
	var b strings.Builder
	b.WriteString("# P7 仪表 DBus 属性清单（daemon 自动导出，供标定用）\n")
	for _, n := range names {
		b.WriteString(n)
		b.WriteByte('\n')
	}
	tmp := dst + ".tmp"
	if err := os.WriteFile(tmp, []byte(b.String()), 0644); err != nil {
		if lg != nil {
			lg.Printf("属性清单写盘失败: %v", err)
		}
		return
	}
	_ = os.Rename(tmp, dst)
	if lg != nil {
		lg.Printf("DBus 属性清单已导出: %d 个方法 → %s", len(names), dst)
	}
}
