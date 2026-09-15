package store

import (
	"encoding/json"
	"errors"
	"fmt"
	"io/fs"
	"os"
	"path/filepath"
)

const configFile = "config.json"

// Config holds user settings (architecture §5.4).
type Config struct {
	// Relay is the base URL of the relay (e.g. wss://relay.example.com), or
	// empty for LAN and direct connections only.
	Relay string `json:"relay,omitempty"`
}

// Config loads config.json; a missing file is the zero Config.
func (s *Store) Config() (Config, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	var c Config
	b, err := os.ReadFile(filepath.Join(s.dir, configFile))
	if errors.Is(err, fs.ErrNotExist) {
		return c, nil
	}
	if err != nil {
		return c, fmt.Errorf("read config: %w", err)
	}
	if err := json.Unmarshal(b, &c); err != nil {
		return c, fmt.Errorf("parse %s: %w", configFile, err)
	}
	return c, nil
}

// SaveConfig replaces config.json.
func (s *Store) SaveConfig(c Config) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	b, err := json.MarshalIndent(c, "", "  ")
	if err != nil {
		return err
	}
	return writeAtomic(filepath.Join(s.dir, configFile), append(b, '\n'))
}
