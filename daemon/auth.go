package main

import (
	"bufio"
	"os"
	"path/filepath"
	"sync"

	"github.com/charmbracelet/log"
	"github.com/charmbracelet/ssh"
	gossh "golang.org/x/crypto/ssh"
)

var (
	authorizedKeys     []ssh.PublicKey
	authorizedKeysOnce sync.Once
	authorizedKeysMu   sync.RWMutex
)

func loadAuthorizedKeys(configDir string) []ssh.PublicKey {
	path := filepath.Join(configDir, "authorized_keys")
	f, err := os.Open(path)
	if err != nil {
		log.Warn("No authorized_keys file", "path", path, "err", err)
		return nil
	}
	defer f.Close()

	var keys []ssh.PublicKey
	scanner := bufio.NewScanner(f)
	for scanner.Scan() {
		line := scanner.Text()
		if len(line) == 0 || line[0] == byte(35) {
			continue
		}
		key, _, _, _, err := gossh.ParseAuthorizedKey([]byte(line))
		if err != nil {
			log.Warn("Skipping invalid key", "err", err)
			continue
		}
		keys = append(keys, key)
	}
	log.Info("Loaded authorized keys", "count", len(keys), "path", path)
	return keys
}

func ReloadAuthorizedKeys(configDir string) {
	authorizedKeysMu.Lock()
	defer authorizedKeysMu.Unlock()
	authorizedKeys = loadAuthorizedKeys(configDir)
}

func AuthHandler(configDir string, ctx ssh.Context, key ssh.PublicKey) bool {
	authorizedKeysOnce.Do(func() {
		authorizedKeys = loadAuthorizedKeys(configDir)
	})

	authorizedKeysMu.RLock()
	defer authorizedKeysMu.RUnlock()

	for _, ak := range authorizedKeys {
		if ssh.KeysEqual(key, ak) {
			log.Info("Auth accepted", "user", ctx.User(), "remote", ctx.RemoteAddr())
			return true
		}
	}
	log.Warn("Auth rejected", "user", ctx.User(), "remote", ctx.RemoteAddr())
	return false
}
