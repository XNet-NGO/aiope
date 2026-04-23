package main

import (
	"encoding/json"
	"fmt"
	"io"
	"os"
	"os/exec"
	"strings"
	"syscall"

	"github.com/charmbracelet/log"
	"github.com/charmbracelet/ssh"
	"github.com/charmbracelet/wish"
	"github.com/creack/pty"
)

func ExecMiddleware(tracker *ProcessTracker) wish.Middleware {
	return func(next ssh.Handler) ssh.Handler {
		return func(sess ssh.Session) {
			cmd := sess.Command()

			// No command — interactive PTY shell
			if len(cmd) == 0 {
				handlePTY(sess, tracker)
				return
			}

			cmdStr := strings.Join(cmd, " ")

			// Health check command
			if cmdStr == "__aiope_health__" {
				handleHealth(sess, tracker)
				return
			}

			// Execute command
			handleExec(sess, cmdStr, tracker)
		}
	}
}

func handleExec(sess ssh.Session, cmdStr string, tracker *ProcessTracker) {
	log.Info("Exec", "cmd", cmdStr, "user", sess.User(), "remote", sess.RemoteAddr())

	c := exec.Command("sh", "-c", cmdStr)
	c.Env = append(os.Environ(), sess.Environ()...)
	c.Stdout = sess
	c.Stderr = sess.Stderr()
	c.SysProcAttr = &syscall.SysProcAttr{Setsid: true}

	if err := c.Start(); err != nil {
		fmt.Fprintf(sess.Stderr(), "exec error: %v\n", err)
		sess.Exit(1)
		return
	}

	tracker.Track(c.Process)
	defer tracker.Untrack(c.Process.Pid)

	if err := c.Wait(); err != nil {
		if exitErr, ok := err.(*exec.ExitError); ok {
			sess.Exit(exitErr.ExitCode())
			return
		}
		fmt.Fprintf(sess.Stderr(), "wait error: %v\n", err)
		sess.Exit(1)
		return
	}
	sess.Exit(0)
}

func handlePTY(sess ssh.Session, tracker *ProcessTracker) {
	ptyReq, winCh, isPty := sess.Pty()
	if !isPty {
		fmt.Fprintln(sess, "No PTY requested. Use ssh -t for interactive sessions.")
		sess.Exit(1)
		return
	}

	shell := os.Getenv("SHELL")
	if shell == "" {
		shell = "/bin/sh"
	}

	c := exec.Command(shell)
	c.Env = append(os.Environ(), sess.Environ()...)
	c.Env = append(c.Env, fmt.Sprintf("TERM=%s", ptyReq.Term))
	c.SysProcAttr = &syscall.SysProcAttr{Setsid: true}

	f, err := pty.Start(c)
	if err != nil {
		log.Error("PTY start failed", "err", err)
		fmt.Fprintf(sess, "PTY error: %v\n", err)
		sess.Exit(1)
		return
	}
	defer f.Close()

	tracker.Track(c.Process)
	defer tracker.Untrack(c.Process.Pid)

	// Set initial window size
	pty.Setsize(f, &pty.Winsize{
		Rows: uint16(ptyReq.Window.Height),
		Cols: uint16(ptyReq.Window.Width),
	})

	// Handle window resize
	go func() {
		for win := range winCh {
			pty.Setsize(f, &pty.Winsize{
				Rows: uint16(win.Height),
				Cols: uint16(win.Width),
			})
		}
	}()

	// Bidirectional copy
	go func() {
		io.Copy(f, sess)
	}()
	io.Copy(sess, f)

	c.Wait()
	sess.Exit(0)
}

func handleHealth(sess ssh.Session, tracker *ProcessTracker) {
	health := GetHealth(tracker)
	data, err := json.Marshal(health)
	if err != nil {
		fmt.Fprintf(sess.Stderr(), "health error: %v\n", err)
		sess.Exit(1)
		return
	}
	sess.Write(data)
	sess.Exit(0)
}
