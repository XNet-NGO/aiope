package browser

import (
	"database/sql"
	"fmt"
	"os"
	"path/filepath"
	"time"

	_ "modernc.org/sqlite"
)

// This module gives BOTH browser engines a single, consistent auth-copy path.
//
// The problem: refresh copies auth stores while the source browser is RUNNING
// (refresh must stay non-disruptive). Those stores are live SQLite DBs in WAL
// mode. A naive byte-copy can capture a torn/stale state (main file and -wal out
// of sync) or fail on Windows (sharing violation). VACUUM INTO opens the DB
// read-only and writes a transactionally CONSISTENT single-file snapshot with
// the WAL folded in — safe even while the browser holds the DB, on all OSes.
//
// Plain (non-SQLite) auth files (Chrome "Local State", Firefox logins.json) are
// byte-copied; they are written atomically by the browser so a plain copy is
// safe enough, and there is no consistent-snapshot primitive for them.

// authKind classifies how an auth store must be copied.
type authKind int

const (
	authSQLite authKind = iota // copy via VACUUM INTO (consistent under a live lock)
	authPlain                  // byte-copy (JSON/text; browser writes atomically)
	authDir                    // recursive tree copy (LevelDB/IndexedDB dirs)
)

// authStore is one auth-relevant file within a profile, with its copy strategy.
// relPath is relative to the profile root (user-data-dir for Chrome, profile
// dir for Firefox). This unified schema is shared by both engines.
type authStore struct {
	relPath string
	kind    authKind
}

// chromeAuthStores: Chrome/Chromium auth schema.
//
// IMPORTANT: modern sites keep the session/auth token in web storage
// (localStorage / IndexedDB / sessionStorage), NOT the cookie jar — so those
// LevelDB dirs are as essential as Cookies for "carry my login over".
//   - Local State (JSON): wrapped cookie key on some setups (keyring-based on
//     Linux, where the key lives in the OS keyring, not here).
//   - Cookies / Network/Cookies (SQLite): cookie jar.
//   - Login Data / Web Data (SQLite): saved logins / autofill.
//   - Local Storage / Session Storage (LevelDB dirs): localStorage tokens.
//   - IndexedDB (dir): IndexedDB auth tokens (many SPAs).
//   - Sessions (dir): open-tab/session-restore state (logged-in tabs).
var chromeAuthStores = []authStore{
	{"Local State", authPlain},
	{"Default/Cookies", authSQLite},
	{"Default/Network/Cookies", authSQLite},
	{"Default/Login Data", authSQLite},
	{"Default/Web Data", authSQLite},
	{"Default/Local Storage", authDir},
	{"Default/Session Storage", authDir},
	{"Default/IndexedDB", authDir},
	{"Default/Sessions", authDir},
}

// firefoxAuthStores: Firefox auth schema.
//   - cookies/places/key4/cert9 (SQLite): cookies, history, key store, certs.
//   - logins.json / sessionstore (plain): saved logins / open tabs.
//   - webappsstore.sqlite (SQLite): legacy localStorage.
//   - storage/default (dir): per-origin localStorage + IndexedDB (where modern
//     session/auth tokens live) — the Firefox equivalent of Chrome's LevelDB
//     web-storage dirs; essential for carrying a logged-in session.
//   - storage.sqlite (SQLite): storage metadata for storage/default.
var firefoxAuthStores = []authStore{
	{"cookies.sqlite", authSQLite},
	{"places.sqlite", authSQLite},
	{"key4.db", authSQLite},
	{"cert9.db", authSQLite},
	{"logins.json", authPlain},
	{"sessionstore.jsonlz4", authPlain},
	{"webappsstore.sqlite", authSQLite},
	{"storage.sqlite", authSQLite},
	{"storage/default", authDir},
}

// copyAuthStores copies the given auth stores from src to dst using the
// consistent strategy per kind. Missing sources are skipped. Used by BOTH
// refresh (live source) and as part of full copies. Returns the first hard
// error; per-file "not found" is not an error.
func copyAuthStores(src, dst string, stores []authStore) error {
	for _, s := range stores {
		from := filepath.Join(src, s.relPath)
		to := filepath.Join(dst, s.relPath)
		if _, err := os.Stat(from); err != nil {
			continue // store not present in this profile — fine
		}
		if s.kind == authDir {
			if err := copyDirTree(from, to); err != nil {
				return fmt.Errorf("copy dir %s: %w", s.relPath, err)
			}
			continue
		}
		if err := os.MkdirAll(filepath.Dir(to), 0o700); err != nil {
			return fmt.Errorf("mkdir %s: %w", filepath.Dir(to), err)
		}
		switch s.kind {
		case authSQLite:
			if err := copySQLiteConsistent(from, to); err != nil {
				// Fall back to a best-effort byte copy (+sidecars) so a refresh
				// still yields *something* rather than nothing.
				_ = copyFileIfExists(from, to)
				_ = copyFileIfExists(from+"-wal", to+"-wal")
				_ = copyFileIfExists(from+"-shm", to+"-shm")
			} else {
				// VACUUM INTO produced a self-contained DB: remove any stale
				// sidecars at the destination so SQLite doesn't replay old WAL.
				_ = os.Remove(to + "-wal")
				_ = os.Remove(to + "-shm")
			}
		case authPlain:
			if err := copyFileIfExists(from, to); err != nil {
				return fmt.Errorf("copy %s: %w", s.relPath, err)
			}
		}
	}
	return nil
}

// copyDirTree recursively copies a directory (LevelDB/IndexedDB web-storage
// store) from src to dst. It skips LevelDB LOCK files (which a live browser
// holds) so a refresh doesn't drag a stale lock; the copy is a snapshot of the
// on-disk .ldb/LOG/MANIFEST files. For reseed (browser closed) this is fully
// consistent; for refresh (live) it is best-effort but reliable because LevelDB
// is append-mostly and readers tolerate a slightly-behind copy.
func copyDirTree(src, dst string) error {
	return filepath.Walk(src, func(path string, info os.FileInfo, err error) error {
		if err != nil {
			return nil // skip unreadable entries rather than abort
		}
		rel, rerr := filepath.Rel(src, path)
		if rerr != nil {
			return nil
		}
		target := filepath.Join(dst, rel)
		if info.IsDir() {
			return os.MkdirAll(target, 0o700)
		}
		base := filepath.Base(path)
		if base == "LOCK" || base == "LOG.old" {
			return nil // LevelDB lock/rotated-log: don't copy
		}
		_ = copyFileIfExists(path, target)
		return nil
	})
}

// copySQLiteConsistent writes a transactionally consistent copy of the SQLite
// database at src to dst using "VACUUM INTO". VACUUM INTO reads the source and
// writes a fresh self-contained target file (it does NOT modify the source), so
// it is safe to run while the browser holds the source DB. We open the source
// read-only at the FILE level so the source is never mutated, but do not set
// query_only (which would block VACUUM INTO's write to the separate target).
// dst is overwritten. Safe under a live lock on all platforms.
func copySQLiteConsistent(src, dst string) error {
	// mode=ro opens the source without creating/writing it; busy_timeout lets a
	// concurrent browser writer settle instead of failing immediately.
	dsn := fmt.Sprintf("file:%s?mode=ro&_pragma=busy_timeout(5000)", src)
	db, err := sql.Open("sqlite", dsn)
	if err != nil {
		return fmt.Errorf("open sqlite %s: %w", src, err)
	}
	defer db.Close()
	db.SetMaxOpenConns(1)

	if err := db.Ping(); err != nil {
		return fmt.Errorf("ping sqlite %s: %w", src, err)
	}

	// VACUUM INTO fails if the target exists; ensure a clean destination.
	if err := os.Remove(dst); err != nil && !os.IsNotExist(err) {
		return fmt.Errorf("clear dst %s: %w", dst, err)
	}
	stmt := fmt.Sprintf("VACUUM INTO %s", sqliteQuote(dst))
	if _, err := db.Exec(stmt); err != nil {
		return fmt.Errorf("vacuum into %s: %w", dst, err)
	}
	return nil
}

// sqliteQuote returns a single-quoted SQL string literal (doubling embedded
// quotes) for safe interpolation of a filesystem path into VACUUM INTO.
func sqliteQuote(s string) string {
	out := make([]byte, 0, len(s)+2)
	out = append(out, '\'')
	for i := 0; i < len(s); i++ {
		if s[i] == '\'' {
			out = append(out, '\'')
		}
		out = append(out, s[i])
	}
	out = append(out, '\'')
	return string(out)
}

// newestFileMtime returns the newest mtime among the given relative paths in a
// profile — a cheap shared staleness heuristic for both engines.
func newestFileMtime(profile string, rels ...string) time.Time {
	var newest time.Time
	for _, rel := range rels {
		if fi, err := os.Stat(filepath.Join(profile, rel)); err == nil && fi.ModTime().After(newest) {
			newest = fi.ModTime()
		}
	}
	return newest
}
