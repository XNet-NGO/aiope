package main

import (
	"os"
	"sync"
	"syscall"

	"github.com/charmbracelet/log"
)

type ProcessTracker struct {
	mu        sync.Mutex
	processes map[int]*os.Process
}

func NewProcessTracker() *ProcessTracker {
	return &ProcessTracker{
		processes: make(map[int]*os.Process),
	}
}

func (pt *ProcessTracker) Track(p *os.Process) {
	pt.mu.Lock()
	defer pt.mu.Unlock()
	pt.processes[p.Pid] = p
}

func (pt *ProcessTracker) Untrack(pid int) {
	pt.mu.Lock()
	defer pt.mu.Unlock()
	delete(pt.processes, pid)
}

func (pt *ProcessTracker) Count() int {
	pt.mu.Lock()
	defer pt.mu.Unlock()
	return len(pt.processes)
}

func (pt *ProcessTracker) KillAll() {
	pt.mu.Lock()
	defer pt.mu.Unlock()
	for pid, p := range pt.processes {
		log.Info("Killing process group", "pid", pid)
		if err := syscall.Kill(-pid, syscall.SIGHUP); err != nil {
			log.Warn("Failed to kill process group, trying direct", "pid", pid, "err", err)
			p.Kill()
		}
	}
	pt.processes = make(map[int]*os.Process)
}
