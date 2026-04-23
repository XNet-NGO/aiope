package main

import (
	"context"
	"fmt"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/charmbracelet/log"
	"github.com/charmbracelet/ssh"
	"github.com/charmbracelet/wish"
	"github.com/charmbracelet/wish/logging"
)

func main() {
	if len(os.Args) > 1 && (os.Args[1] == "--version" || os.Args[1] == "-v") {
		fmt.Println(Version)
		return
	}

	port := getEnv("AIOPE_PORT", "2222")
	configDir := getEnv("AIOPE_CONFIG_DIR", expandHome("~/.aiope"))

	tracker := NewProcessTracker()

	s, err := wish.NewServer(
		wish.WithAddress(fmt.Sprintf(":%s", port)),
		wish.WithPublicKeyAuth(func(ctx ssh.Context, key ssh.PublicKey) bool {
			return AuthHandler(configDir, ctx, key)
		}),
		wish.WithSubsystem("sftp", SftpHandler),
		wish.WithMiddleware(
			ExecMiddleware(tracker),
			logging.Middleware(),
		),
	)
	if err != nil {
		log.Fatal("Failed to create server", "err", err)
	}

	done := make(chan os.Signal, 1)
	signal.Notify(done, syscall.SIGINT, syscall.SIGTERM, syscall.SIGHUP)

	go func() {
		log.Info("aiope-remote started", "port", port, "version", Version)
		if err := s.ListenAndServe(); err != nil {
			log.Fatal("Server error", "err", err)
		}
	}()

	sig := <-done
	log.Info("Shutting down", "signal", sig)
	tracker.KillAll()

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	if err := s.Shutdown(ctx); err != nil {
		log.Error("Shutdown error", "err", err)
	}
}

func getEnv(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}

func expandHome(path string) string {
	if len(path) > 1 && path[:2] == "~/" {
		home, err := os.UserHomeDir()
		if err != nil {
			return path
		}
		return home + path[1:]
	}
	return path
}
