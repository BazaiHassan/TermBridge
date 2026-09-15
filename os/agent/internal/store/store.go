// Package store persists the agent identity and the paired-device allowlist
// (architecture §5.4) in the per-user config directory. Every file is written
// atomically with owner-only permissions.
package store

import (
	"bytes"
	"crypto/ed25519"
	"crypto/rand"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io/fs"
	"os"
	"path/filepath"
	"runtime"
	"slices"
	"strconv"
	"strings"
	"sync"
	"time"
)

const (
	identityFile = "identity.key"
	devicesFile  = "devices.json"
)

// ErrNotFound is returned when no paired device has the given name.
var ErrNotFound = errors.New("store: no such device")

// Device is a paired phone. PubKey is its X25519 Noise static key.
type Device struct {
	PubKey   string    `json:"pubkey"`
	Name     string    `json:"name"`
	PairedAt time.Time `json:"paired_at"`
	LastSeen time.Time `json:"last_seen,omitzero"`
}

// Key decodes PubKey.
func (d Device) Key() []byte {
	b, _ := base64.StdEncoding.DecodeString(d.PubKey)
	return b
}

// Store is safe for concurrent use within one process. Separate agent
// processes (e.g. `termbridge revoke` next to `termbridge run`) coordinate
// through atomic file replacement.
type Store struct {
	dir string
	mu  sync.Mutex
}

// DefaultDir is ~/.config/termbridge on Linux and %APPDATA%\TermBridge on
// Windows.
func DefaultDir() (string, error) {
	base, err := os.UserConfigDir()
	if err != nil {
		return "", fmt.Errorf("locate config directory: %w", err)
	}
	name := "termbridge"
	if runtime.GOOS == "windows" {
		name = "TermBridge"
	}
	return filepath.Join(base, name), nil
}

// Open uses dir, creating it with owner-only permissions.
func Open(dir string) (*Store, error) {
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return nil, fmt.Errorf("create %s: %w", dir, err)
	}
	return &Store{dir: dir}, nil
}

// Dir returns the store directory.
func (s *Store) Dir() string { return s.dir }

// HasIdentity reports whether an identity has been created.
func (s *Store) HasIdentity() bool {
	_, err := os.Stat(filepath.Join(s.dir, identityFile))
	return err == nil
}

// Identity returns the agent's Ed25519 key, generating it on first use.
func (s *Store) Identity() (ed25519.PrivateKey, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	path := filepath.Join(s.dir, identityFile)
	b, err := os.ReadFile(path)
	switch {
	case err == nil:
		if err := checkPrivate(path); err != nil {
			return nil, err
		}
		seed, err := base64.StdEncoding.DecodeString(string(bytes.TrimSpace(b)))
		if err != nil || len(seed) != ed25519.SeedSize {
			return nil, fmt.Errorf("%s is corrupt; `termbridge reset` creates a new identity", path)
		}
		return ed25519.NewKeyFromSeed(seed), nil
	case errors.Is(err, fs.ErrNotExist):
		_, priv, err := ed25519.GenerateKey(rand.Reader)
		if err != nil {
			return nil, fmt.Errorf("generate identity: %w", err)
		}
		data := base64.StdEncoding.EncodeToString(priv.Seed()) + "\n"
		if err := writeAtomic(path, []byte(data)); err != nil {
			return nil, err
		}
		return priv, nil
	default:
		return nil, fmt.Errorf("read identity: %w", err)
	}
}

// checkPrivate refuses a private key others can read, as ssh does.
func checkPrivate(path string) error {
	if runtime.GOOS == "windows" {
		return nil
	}
	fi, err := os.Stat(path)
	if err != nil {
		return fmt.Errorf("stat %s: %w", path, err)
	}
	if fi.Mode().Perm()&0o077 != 0 {
		return fmt.Errorf("%s is readable by other users (mode %v); run: chmod 600 %s", path, fi.Mode().Perm(), path)
	}
	return nil
}

// Devices returns the paired devices.
func (s *Store) Devices() ([]Device, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.loadDevices()
}

// FindDevice looks a device up by its Noise static key.
func (s *Store) FindDevice(key []byte) (Device, bool, error) {
	devices, err := s.Devices()
	if err != nil {
		return Device{}, false, err
	}
	enc := base64.StdEncoding.EncodeToString(key)
	for _, d := range devices {
		if d.PubKey == enc {
			return d, true, nil
		}
	}
	return Device{}, false, nil
}

// AddDevice pairs a device. Re-pairing a known key updates it; a name already
// taken by another key gets a numeric suffix. It returns the stored device.
func (s *Store) AddDevice(key []byte, name string, now time.Time) (Device, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	devices, err := s.loadDevices()
	if err != nil {
		return Device{}, err
	}
	enc := base64.StdEncoding.EncodeToString(key)
	devices = slices.DeleteFunc(devices, func(d Device) bool { return d.PubKey == enc })
	unique := name
	for n := 2; slices.ContainsFunc(devices, func(d Device) bool { return strings.EqualFold(d.Name, unique) }); n++ {
		unique = name + " " + strconv.Itoa(n)
	}
	d := Device{PubKey: enc, Name: unique, PairedAt: now.UTC(), LastSeen: now.UTC()}
	devices = append(devices, d)
	return d, s.saveDevices(devices)
}

// RemoveDevice unpairs the device called name (case-insensitive).
func (s *Store) RemoveDevice(name string) (Device, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	devices, err := s.loadDevices()
	if err != nil {
		return Device{}, err
	}
	i := slices.IndexFunc(devices, func(d Device) bool { return strings.EqualFold(d.Name, name) })
	if i < 0 {
		return Device{}, fmt.Errorf("%w %q", ErrNotFound, name)
	}
	removed := devices[i]
	return removed, s.saveDevices(slices.Delete(devices, i, i+1))
}

// TouchDevice records that the device with key connected at t.
func (s *Store) TouchDevice(key []byte, t time.Time) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	devices, err := s.loadDevices()
	if err != nil {
		return err
	}
	enc := base64.StdEncoding.EncodeToString(key)
	for i := range devices {
		if devices[i].PubKey == enc {
			devices[i].LastSeen = t.UTC()
			return s.saveDevices(devices)
		}
	}
	return nil
}

// DevicesModTime reports when devices.json last changed (zero if absent), so
// a running agent can notice `termbridge revoke` from another process.
func (s *Store) DevicesModTime() time.Time {
	fi, err := os.Stat(filepath.Join(s.dir, devicesFile))
	if err != nil {
		return time.Time{}
	}
	return fi.ModTime()
}

// Reset deletes the identity and every pairing.
func (s *Store) Reset() error {
	s.mu.Lock()
	defer s.mu.Unlock()
	for _, f := range []string{identityFile, devicesFile} {
		if err := os.Remove(filepath.Join(s.dir, f)); err != nil && !errors.Is(err, fs.ErrNotExist) {
			return fmt.Errorf("remove %s: %w", f, err)
		}
	}
	return nil
}

func (s *Store) loadDevices() ([]Device, error) {
	b, err := os.ReadFile(filepath.Join(s.dir, devicesFile))
	if errors.Is(err, fs.ErrNotExist) {
		return nil, nil
	}
	if err != nil {
		return nil, fmt.Errorf("read devices: %w", err)
	}
	var devices []Device
	if err := json.Unmarshal(b, &devices); err != nil {
		return nil, fmt.Errorf("parse %s: %w", devicesFile, err)
	}
	return devices, nil
}

func (s *Store) saveDevices(devices []Device) error {
	if devices == nil {
		devices = []Device{}
	}
	b, err := json.MarshalIndent(devices, "", "  ")
	if err != nil {
		return fmt.Errorf("encode devices: %w", err)
	}
	return writeAtomic(filepath.Join(s.dir, devicesFile), append(b, '\n'))
}

// writeAtomic replaces path with data (mode 0600) via a synced temp file.
func writeAtomic(path string, data []byte) error {
	tmp, err := os.CreateTemp(filepath.Dir(path), "."+filepath.Base(path)+".*")
	if err != nil {
		return fmt.Errorf("write %s: %w", path, err)
	}
	defer os.Remove(tmp.Name()) // no-op after a successful rename
	if err := tmp.Chmod(0o600); err != nil && runtime.GOOS != "windows" {
		tmp.Close()
		return fmt.Errorf("chmod %s: %w", path, err)
	}
	if _, err := tmp.Write(data); err != nil {
		tmp.Close()
		return fmt.Errorf("write %s: %w", path, err)
	}
	if err := tmp.Sync(); err != nil {
		tmp.Close()
		return fmt.Errorf("sync %s: %w", path, err)
	}
	if err := tmp.Close(); err != nil {
		return fmt.Errorf("close %s: %w", path, err)
	}
	if err := os.Rename(tmp.Name(), path); err != nil {
		return fmt.Errorf("replace %s: %w", path, err)
	}
	return nil
}
