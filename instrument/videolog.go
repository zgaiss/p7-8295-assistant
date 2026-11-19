package main

// 播放器日志的"限长"写入器。
//
// 原来 daemon 把播放器的 stdout 直接接到 log/p7video.log（O_TRUNC 打开），
// 播放器每 2 秒打一行 fps= 统计，跑一整天就是四万多行、3MB 以上。
// 而车机点「播放器日志」是把整个文件 ftp get 回来再截最后 1200 字节，
// 文件越大传得越慢，超过传输超时就直接读不到（现场："过一段时间就读不到日志"）。
//
// 这里改成自己接管输出：每次追加写完，行数超过上限就把文件裁到只剩后一半。
// 因为落盘只经过本进程（播放器写的是管道），裁文件时没有别的 fd 在同一位点上写，
// 不会产生空洞或错位。

import (
	"os"
	"path/filepath"
	"strings"
	"sync"
)

const (
	videoLogMaxLines  = 400 // 超过这个行数就裁
	videoLogKeepLines = 200 // 裁完只剩这么多
)

type logRing struct {
	mu   sync.Mutex
	path string
	n    int
}

func newLogRing(path string) *logRing {
	return &logRing{path: path}
}

// reset 清空日志（每次拉起播放器时用，保证看到的是这一次运行的输出）
func (l *logRing) reset() {
	l.mu.Lock()
	defer l.mu.Unlock()
	if err := os.MkdirAll(filepath.Dir(l.path), 0755); err != nil {
		return
	}
	if f, err := os.OpenFile(l.path, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, 0644); err == nil {
		f.Close()
	}
	l.n = 0
}

func (l *logRing) Write(p []byte) (int, error) {
	l.mu.Lock()
	defer l.mu.Unlock()
	f, err := os.OpenFile(l.path, os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0644)
	if err != nil {
		// 写不进去也不能让播放器挂住：直接吞掉，播放本身不受影响
		return len(p), nil
	}
	f.Write(p)
	f.Close()
	l.n += strings.Count(string(p), "\n")
	if l.n > videoLogMaxLines*2 {
		l.trimLocked()
	}
	return len(p), nil
}

func (l *logRing) trimLocked() {
	b, err := os.ReadFile(l.path)
	if err != nil {
		return
	}
	lines := strings.SplitAfter(string(b), "\n")
	if n := len(lines); n > 0 && lines[n-1] == "" {
		lines = lines[:n-1]
	}
	if len(lines) > videoLogKeepLines {
		lines = lines[len(lines)-videoLogKeepLines:]
	}
	if err := os.WriteFile(l.path, []byte(strings.Join(lines, "")), 0644); err != nil {
		return
	}
	l.n = len(lines)
}
