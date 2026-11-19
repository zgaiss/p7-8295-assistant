package main

import (
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// 日志必须自我限长：车机是整文件拉回去再看尾巴，文件无限长最后会读不到。
func TestLogRingTrims(t *testing.T) {
	dir := t.TempDir()
	p := filepath.Join(dir, "log", "p7video.log")
	l := newLogRing(p)
	l.reset()

	// 模拟播放器跑很久：每 2 秒一行，这里快速灌 1000 行
	for i := 0; i < 1000; i++ {
		line := fmt.Sprintf("fps=24.0 dec=1.0ms ren=2.0ms wri=40.0ms frames=%d\n", i)
		if _, err := l.Write([]byte(line)); err != nil {
			t.Fatalf("write: %v", err)
		}
	}

	st, err := os.Stat(p)
	if err != nil {
		t.Fatalf("stat: %v", err)
	}
	b, _ := os.ReadFile(p)
	lines := strings.Count(string(b), "\n")
	if lines > videoLogMaxLines {
		t.Fatalf("日志没有限长：%d 行 / %d 字节", lines, st.Size())
	}
	// 裁剪只能砍前面，最后一行必须还在
	if !strings.Contains(string(b), "frames=999") {
		t.Fatalf("裁剪把最新的尾巴切掉了")
	}
	t.Logf("1000 行写入后文件 %d 行 / %d 字节", lines, st.Size())
}

// reset 之后文件必须被清空，否则会看到上一次运行的旧统计
func TestLogRingReset(t *testing.T) {
	dir := t.TempDir()
	p := filepath.Join(dir, "p7video.log")
	l := newLogRing(p)
	l.Write([]byte("old line\n"))
	l.reset()
	b, _ := os.ReadFile(p)
	if len(b) != 0 {
		t.Fatalf("reset 后仍有 %d 字节", len(b))
	}
}
