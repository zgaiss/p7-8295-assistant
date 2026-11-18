//go:build !linux

package main

// 非 Linux 平台只保留预览渲染（方便在 PC 上校对布局）
import "fmt"

func daemonMain() {
	fmt.Println("daemon 只能在仪表（Linux/ARMv7）上运行；本机请用 p7assist render 生成预览图")
}

func cleanupCmd() {
	fmt.Println("cleanup 只能在仪表（Linux/ARMv7）上运行")
}

// 非 Linux 平台桩：PC 上 go test 时 chargeframe.go / config.go / chgoverlay.go
// 仍会引用这些符号，而 videoplay.go 带 linux build tag 不参与编译。
func videoRunning() bool   { return false }
func chgVideoTag() string  { return "" }
const videoOvName = "chgoverlay.jpg"
