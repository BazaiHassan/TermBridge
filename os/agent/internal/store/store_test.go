package store

import (
	"bytes"
	"errors"
	"os"
	"path/filepath"
	"runtime"
	"testing"
	"time"
)

func open(t *testing.T) *Store {
	t.Helper()
	s, err := Open(filepath.Join(t.TempDir(), "termbridge"))
	if err != nil {
		t.Fatal(err)
	}
	return s
}

func TestIdentityIsCreatedOnceAndPrivate(t *testing.T) {
	s := open(t)
	a, err := s.Identity()
	if err != nil {
		t.Fatal(err)
	}
	b, err := s.Identity()
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(a, b) {
		t.Fatal("identity changed between loads")
	}
	if runtime.GOOS != "windows" {
		fi, _ := os.Stat(filepath.Join(s.Dir(), identityFile))
		if fi.Mode().Perm() != 0o600 {
			t.Fatalf("identity mode %v", fi.Mode().Perm())
		}
	}
}

func TestIdentityRefusesLoosePermissions(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("POSIX permissions")
	}
	s := open(t)
	if _, err := s.Identity(); err != nil {
		t.Fatal(err)
	}
	if err := os.Chmod(filepath.Join(s.Dir(), identityFile), 0o644); err != nil {
		t.Fatal(err)
	}
	if _, err := s.Identity(); err == nil {
		t.Fatal("world-readable identity accepted")
	}
}

func TestDeviceLifecycle(t *testing.T) {
	s := open(t)
	now := time.Date(2026, 9, 14, 10, 0, 0, 0, time.UTC)
	keyA, keyB := bytes.Repeat([]byte{1}, 32), bytes.Repeat([]byte{2}, 32)

	a, err := s.AddDevice(keyA, "Pixel 8", now)
	if err != nil || a.Name != "Pixel 8" {
		t.Fatalf("add: %+v, %v", a, err)
	}
	b, _ := s.AddDevice(keyB, "pixel 8", now)
	if b.Name != "pixel 8 2" {
		t.Fatalf("duplicate name not suffixed: %q", b.Name)
	}
	if d, ok, _ := s.FindDevice(keyA); !ok || !bytes.Equal(d.Key(), keyA) {
		t.Fatal("device A not found")
	}
	if err := s.TouchDevice(keyA, now.Add(time.Hour)); err != nil {
		t.Fatal(err)
	}
	if d, _, _ := s.FindDevice(keyA); !d.LastSeen.Equal(now.Add(time.Hour)) {
		t.Fatalf("last seen %v", d.LastSeen)
	}
	if _, err := s.RemoveDevice("PIXEL 8"); err != nil {
		t.Fatal(err)
	}
	if _, ok, _ := s.FindDevice(keyA); ok {
		t.Fatal("revoked device still found")
	}
	if _, err := s.RemoveDevice("nope"); !errors.Is(err, ErrNotFound) {
		t.Fatalf("remove unknown: %v", err)
	}
}

func TestResetForgetsEverything(t *testing.T) {
	s := open(t)
	first, _ := s.Identity()
	_, _ = s.AddDevice(bytes.Repeat([]byte{1}, 32), "phone", time.Now())
	if err := s.Reset(); err != nil {
		t.Fatal(err)
	}
	if d, _ := s.Devices(); len(d) != 0 {
		t.Fatal("devices survived reset")
	}
	second, _ := s.Identity()
	if bytes.Equal(first, second) {
		t.Fatal("identity survived reset")
	}
}
