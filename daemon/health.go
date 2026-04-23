package main

import (
	"bufio"
	"fmt"
	"os"
	"runtime"
	"strings"
	"syscall"
	"time"
)

type HealthReport struct {
	Version        string `json:"version"`
	OS             string `json:"os"`
	Arch           string `json:"arch"`
	Hostname       string `json:"hostname"`
	CPUs           int    `json:"cpus"`
	LoadAvg        string `json:"load_avg"`
	MemTotalMB     uint64 `json:"mem_total_mb"`
	MemAvailableMB uint64 `json:"mem_available_mb"`
	DiskTotalGB    uint64 `json:"disk_total_gb"`
	DiskFreeGB     uint64 `json:"disk_free_gb"`
	Uptime         string `json:"uptime"`
	ActiveProcs    int    `json:"active_processes"`
}

func GetHealth(tracker *ProcessTracker) HealthReport {
	hostname, _ := os.Hostname()

	var memTotal, memAvail uint64
	if f, err := os.Open("/proc/meminfo"); err == nil {
		defer f.Close()
		scanner := bufio.NewScanner(f)
		for scanner.Scan() {
			line := scanner.Text()
			if strings.HasPrefix(line, "MemTotal:") {
				fmt.Sscanf(line, "MemTotal: %d kB", &memTotal)
			} else if strings.HasPrefix(line, "MemAvailable:") {
				fmt.Sscanf(line, "MemAvailable: %d kB", &memAvail)
			}
		}
	}

	loadAvg := ""
	if data, err := os.ReadFile("/proc/loadavg"); err == nil {
		parts := strings.Fields(string(data))
		if len(parts) >= 3 {
			loadAvg = strings.Join(parts[:3], " ")
		}
	}

	var stat syscall.Statfs_t
	var diskTotal, diskFree uint64
	if err := syscall.Statfs("/", &stat); err == nil {
		diskTotal = stat.Blocks * uint64(stat.Bsize) / (1024 * 1024 * 1024)
		diskFree = stat.Bfree * uint64(stat.Bsize) / (1024 * 1024 * 1024)
	}

	uptime := ""
	if data, err := os.ReadFile("/proc/uptime"); err == nil {
		var secs float64
		fmt.Sscanf(string(data), "%f", &secs)
		d := time.Duration(secs) * time.Second
		days := int(d.Hours()) / 24
		hours := int(d.Hours()) % 24
		mins := int(d.Minutes()) % 60
		uptime = fmt.Sprintf("%dd %dh %dm", days, hours, mins)
	}

	return HealthReport{
		Version:        Version,
		OS:             runtime.GOOS,
		Arch:           runtime.GOARCH,
		Hostname:       hostname,
		CPUs:           runtime.NumCPU(),
		LoadAvg:        loadAvg,
		MemTotalMB:     memTotal / 1024,
		MemAvailableMB: memAvail / 1024,
		DiskTotalGB:    diskTotal,
		DiskFreeGB:     diskFree,
		Uptime:         uptime,
		ActiveProcs:    tracker.Count(),
	}
}
