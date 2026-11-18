package main

// 路径与日志：仪表上默认 /data/p7assist，可用 P7ASSIST_DIR 覆盖（本地调试）

import (
	"log"
	"os"
	"path/filepath"
)

const rootDefault = "/data/p7assist"

var (
	appDirPath  string
	cfgPath     string
	extPath     string
	statusPath  string
	stopPath    string
	restartPath string
	reinitPath  string
	upgradePath string
	shotReqPath string
	shotPath    string
)

func appDir() string {
	if v := os.Getenv("P7ASSIST_DIR"); v != "" {
		appDirPath = v
	} else if appDirPath == "" {
		if exe, err := os.Executable(); err == nil {
			appDirPath = filepath.Dir(exe)
		} else {
			appDirPath = rootDefault
		}
	}
	if appDirPath == "." {
		appDirPath = rootDefault
	}
	cfgPath = filepath.Join(appDirPath, "config.json")
	extPath = filepath.Join(appDirPath, "ext.json")
	statusPath = filepath.Join(appDirPath, "status.json")
	stopPath = filepath.Join(appDirPath, "stop")
	restartPath = filepath.Join(appDirPath, "restart")
	reinitPath = filepath.Join(appDirPath, "reinit")
	upgradePath = filepath.Join(appDirPath, "p7assist.new")
	shotReqPath = filepath.Join(appDirPath, "shot.req")
	shotPath = filepath.Join(appDirPath, "shot.png")
	return appDirPath
}

// dbgLog 滚动日志：超过 512KB 清空重来，避免长期运行把 /data 写满
func dbgLog() *log.Logger {
	dir := appDir()
	_ = os.MkdirAll(dir+"/log", 0755)
	f, err := os.OpenFile(dir+"/log/hud.log", os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0644)
	if err != nil {
		return log.New(os.Stderr, "", log.LstdFlags)
	}
	if st, err := f.Stat(); err == nil && st.Size() > 512*1024 {
		f.Truncate(0)
		f.Seek(0, 0)
	}
	return log.New(f, "", log.LstdFlags)
}
