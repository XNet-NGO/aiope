//go:build !windows

package main

import (
	"os"
	"os/exec"
	"syscall"
)

func setSysProcAttr(c *exec.Cmd) {
	c.SysProcAttr = &syscall.SysProcAttr{Setsid: true}
}

func killProcessGroup(pid int) error {
	return syscall.Kill(-pid, syscall.SIGHUP)
}

func getDiskFree() (total uint64, free uint64, ok bool) {
	var stat syscall.Statfs_t
	if err := syscall.Statfs("/", &stat); err == nil {
		return stat.Blocks * uint64(stat.Bsize), stat.Bavail * uint64(stat.Bsize), true
	}
	return 0, 0, false
}

func shellCommand(cmdStr string) *exec.Cmd {
	return exec.Command("sh", "-c", cmdStr)
}

func defaultShell() string {
	if sh := os.Getenv("SHELL"); sh != "" {
		return sh
	}
	return "/bin/sh"
}
