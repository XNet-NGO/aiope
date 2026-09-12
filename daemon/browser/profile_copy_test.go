package browser

import (
	"database/sql"
	"fmt"
	"path/filepath"
	"testing"

	_ "modernc.org/sqlite"
)

// TestCopySQLiteConsistentUnderLiveWriter proves VACUUM INTO captures all
// committed rows from a WAL-mode DB while another connection holds it open with
// data still living in the -wal sidecar (simulating a running browser).
func TestCopySQLiteConsistentUnderLiveWriter(t *testing.T) {
	dir := t.TempDir()
	src := filepath.Join(dir, "cookies.sqlite")

	// "Browser" connection: WAL mode, writes 100 rows, stays OPEN (holds the DB
	// + keeps recent commits in -wal, not checkpointed into the main file).
	live, err := sql.Open("sqlite", fmt.Sprintf("file:%s?_pragma=journal_mode(WAL)&_pragma=wal_autocheckpoint(0)", src))
	if err != nil {
		t.Fatal(err)
	}
	defer live.Close()
	live.SetMaxOpenConns(1)
	if _, err := live.Exec(`CREATE TABLE cookies(id INTEGER PRIMARY KEY, name TEXT)`); err != nil {
		t.Fatal(err)
	}
	for i := 0; i < 100; i++ {
		if _, err := live.Exec(`INSERT INTO cookies(name) VALUES(?)`, fmt.Sprintf("c%d", i)); err != nil {
			t.Fatal(err)
		}
	}
	// Do NOT checkpoint: rows are in -wal. The main file alone would be empty.

	// Consistent copy while live writer is still open.
	dst := filepath.Join(dir, "copy.sqlite")
	if err := copySQLiteConsistent(src, dst); err != nil {
		t.Fatalf("copySQLiteConsistent: %v", err)
	}

	// Verify the copy has ALL 100 committed rows (proves WAL was folded in).
	cp, err := sql.Open("sqlite", "file:"+dst+"?mode=ro")
	if err != nil {
		t.Fatal(err)
	}
	defer cp.Close()
	var n int
	if err := cp.QueryRow(`SELECT COUNT(*) FROM cookies`).Scan(&n); err != nil {
		t.Fatalf("count in copy: %v", err)
	}
	if n != 100 {
		t.Fatalf("expected 100 rows in consistent copy, got %d", n)
	}
}

// TestCopyAuthStoresMixedKinds verifies the unified copier handles both SQLite
// and plain stores and skips missing ones.
func TestCopyAuthStoresMixedKinds(t *testing.T) {
	src := t.TempDir()
	dst := t.TempDir()
	// plain store
	writeFile(t, filepath.Join(src, "logins.json"), `{"k":"v"}`)
	// sqlite store
	sdb, _ := sql.Open("sqlite", "file:"+filepath.Join(src, "cookies.sqlite"))
	sdb.Exec(`CREATE TABLE t(x)`); sdb.Exec(`INSERT INTO t VALUES(1)`); sdb.Close()

	stores := []authStore{{"logins.json", authPlain}, {"cookies.sqlite", authSQLite}, {"missing.sqlite", authSQLite}}
	if err := copyAuthStores(src, dst, stores); err != nil {
		t.Fatalf("copyAuthStores: %v", err)
	}
	if !fileExists(filepath.Join(dst, "logins.json")) {
		t.Error("plain store not copied")
	}
	if !fileExists(filepath.Join(dst, "cookies.sqlite")) {
		t.Error("sqlite store not copied")
	}
}
