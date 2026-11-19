package main

import (
	"os"
	"path/filepath"
	"testing"
)

// 帧包头校验：两种包都要认，半截文件/垃圾数据都要拒。
func TestPackHeaderOKAt(t *testing.T) {
	dir := t.TempDir()
	mk := func(name string, b []byte) string {
		p := filepath.Join(dir, name)
		if err := os.WriteFile(p, b, 0644); err != nil {
			t.Fatal(err)
		}
		return p
	}
	body := make([]byte, 64)
	copy(body, "P7VP")
	jpg := mk("vp.pack", append(body, make([]byte, 0)...))

	body2 := make([]byte, 64)
	copy(body2, "P7LZ")
	lz := mk("lz.pack", body2)

	bad := mk("bad.pack", []byte("XXXX0123456789abcdefghij"))
	short := mk("short.pack", []byte("P7VP"))

	if !packHeaderOKAt(jpg) {
		t.Error("P7VP 应该被接受")
	}
	if !packHeaderOKAt(lz) {
		t.Error("P7LZ 应该被接受")
	}
	if packHeaderOKAt(bad) {
		t.Error("未知魔数应该被拒绝")
	}
	if packHeaderOKAt(short) {
		t.Error("不足 24 字节应该被拒绝")
	}
	if packHeaderOKAt(filepath.Join(dir, "nope.pack")) {
		t.Error("不存在的文件应该被拒绝")
	}
}
