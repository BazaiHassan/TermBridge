package store

import "testing"

func TestConfigRoundTrip(t *testing.T) {
	st, err := Open(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	if c, err := st.Config(); err != nil || c != (Config{}) {
		t.Fatalf("fresh config = %+v, %v; want zero", c, err)
	}
	want := Config{Relay: "wss://relay.example.com", Shell: "/usr/bin/fish"}
	if err := st.SaveConfig(want); err != nil {
		t.Fatal(err)
	}
	if got, err := st.Config(); err != nil || got != want {
		t.Fatalf("config = %+v, %v; want %+v", got, err, want)
	}
}
