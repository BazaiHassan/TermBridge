package main

import (
	"errors"
	"fmt"
	"os"
	"path/filepath"

	"github.com/spf13/cobra"

	"termbridge/agent/internal/autostart"
)

// agentEntry is what `termbridge autostart on` registers: this executable
// running `run` with the given flags.
func agentEntry(runArgs []string) (autostart.Entry, error) {
	exe, err := os.Executable()
	if err != nil {
		return autostart.Entry{}, fmt.Errorf("locate this executable: %w", err)
	}
	if resolved, err := filepath.EvalSymlinks(exe); err == nil {
		exe = resolved
	}
	return autostart.Entry{
		ID:   "io.termbridge.agent",
		Name: "TermBridge",
		Exe:  exe,
		Args: append([]string{"run"}, runArgs...),
		Kind: autostart.Background,
	}, nil
}

func newAutostartCmd() *cobra.Command {
	return &cobra.Command{
		Use:   "autostart [on [run flags…] | off]",
		Short: "Serve paired phones from the moment you log in",
		Long: `Registers "termbridge run" to start when you log in: a systemd user unit
(or XDG autostart entry) on Linux, a LaunchAgent on macOS, the Run key on
Windows. Flags after "on" are passed to run, e.g.:

  termbridge autostart on --lan-only

Without arguments, shows whether autostart is on.`,
		DisableFlagParsing: true, // flags after "on" belong to run
		RunE: func(cmd *cobra.Command, args []string) error {
			if len(args) > 0 && (args[0] == "-h" || args[0] == "--help") {
				return cmd.Help()
			}
			e, err := agentEntry(nil)
			if err != nil {
				return err
			}
			if len(args) == 0 {
				if autostart.Enabled(e) {
					fmt.Println("autostart: on")
				} else {
					fmt.Println("autostart: off  (turn it on with: termbridge autostart on)")
				}
				return nil
			}
			switch args[0] {
			case "on":
				if os.Geteuid() == 0 { // -1 on Windows
					return errors.New("refusing to start at login as root: every paired phone would get a root shell")
				}
				// Catch typos now rather than at the next login.
				if err := newServeCmd("run", "", false).Flags().Parse(args[1:]); err != nil {
					return fmt.Errorf("run flags: %w", err)
				}
				e.Args = append(e.Args, args[1:]...)
				where, err := autostart.Enable(e)
				if err != nil {
					return err
				}
				fmt.Printf("TermBridge will start when you log in.\n  %s\n", where)
			case "off":
				if err := autostart.Disable(e); err != nil {
					return err
				}
				fmt.Println("TermBridge no longer starts at login. A running agent keeps running until you stop it.")
			default:
				return fmt.Errorf("unknown argument %q: use on or off", args[0])
			}
			return nil
		},
	}
}
