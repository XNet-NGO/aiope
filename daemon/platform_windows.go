//go:build windows

package main

import (
	"fmt"
	"os/exec"
)

func setSysProcAttr(c *exec.Cmd) {
	// No Setsid on Windows
}

func killProcessGroup(pid int) error {
	cmd := exec.Command("taskkill", "/F", "/PID", fmt.Sprintf("%d", pid))
	return cmd.Run()
}

func getDiskFree() (total uint64, free uint64, ok bool) {
	return 0, 0, false
}

func shellCommand(cmdStr string) *exec.Cmd {
	return exec.Command("cmd", "/C", cmdStr)
}

func defaultShell() string {
	return "cmd"
}
