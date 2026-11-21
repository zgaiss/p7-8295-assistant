package main

import (
	"context"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestLegacyMigrationFence(t *testing.T) {
	root := t.TempDir()
	if !legacyUpdateAllowed(root) {
		t.Fatal("legacy without namespace blocked")
	}
	for _, kind := range []string{"file", "directory", "dangling-link", "directory-with-bad-owner"} {
		t.Run(kind, func(t *testing.T) {
			root := t.TempDir()
			p := filepath.Join(root, "update-v1")
			switch kind {
			case "file":
				os.WriteFile(p, []byte("unknown"), 0600)
			case "directory":
				os.Mkdir(p, 0700)
			case "dangling-link":
				os.Symlink("absent", p)
			default:
				os.Mkdir(p, 0700)
				os.WriteFile(filepath.Join(p, "OWNER"), []byte("{broken"), 0600)
			}
			if legacyUpdateAllowed(root) {
				t.Fatal("legacy adoption allowed")
			}
			// Execute only the actual script's guard prefix under a temporary D, never
			// the remainder (which contains vehicle startup/cleanup commands).
			for _, name := range []string{"p7boot.sh", "p7watch.sh"} {
				raw, e := os.ReadFile(filepath.Join("autostart", name))
				if e != nil {
					t.Fatal(e)
				}
				script := strings.Split(string(raw), "# END MIGRATION FENCE")[0]
				script = strings.Replace(script, "D=/data/p7assist", "D="+root, 1)
				if e = exec.Command("sh", "-c", script).Run(); e == nil {
					t.Fatal("shell guard allowed", name)
				}
			}
		})
	}
	// A non-directory parent must not be treated as a missing namespace.
	file := filepath.Join(root, "notdir")
	os.WriteFile(file, []byte("x"), 0600)
	if legacyUpdateAllowed(file) {
		t.Fatal("ENOTDIR allowed")
	}
}
func TestLegacyScriptsStayUnmigratedByDefault(t *testing.T) {
	root := t.TempDir()
	for _, name := range []string{"p7boot.sh", "p7watch.sh"} {
		raw, e := os.ReadFile(filepath.Join("autostart", name))
		if e != nil {
			t.Fatal(e)
		}
		script := strings.Split(string(raw), "# END MIGRATION FENCE")[0]
		script = strings.Replace(script, "D=/data/p7assist", "D="+root, 1)
		if out, e := exec.Command("sh", "-c", script).CombinedOutput(); e != nil {
			t.Fatal(name, e, string(out))
		}
		asset, e := os.ReadFile(filepath.Join("..", "carpad", "assets", name))
		if e != nil || string(asset) != string(raw) {
			t.Fatal("script asset out of sync", name)
		}
	}
}

func TestMigrationFenceLatchesUntilRestart(t *testing.T) {
	root := t.TempDir()
	f := legacyUpdateFence{}
	if !f.allowed(root) {
		t.Fatal("initial legacy blocked")
	}
	os.Mkdir(filepath.Join(root, "update-v1"), 0700)
	if f.allowed(root) {
		t.Fatal("migration not observed")
	}
	os.Remove(filepath.Join(root, "update-v1"))
	if f.allowed(root) {
		t.Fatal("transient deletion reopened legacy")
	}
	// A new process still needs the persistent namespace. The fence alone is not
	// an immutable root: bootstrap must separately prevent old clients deleting it.
	fresh := legacyUpdateFence{}
	if !fresh.allowed(root) {
		t.Fatal("legacy restart changed")
	}
}

func TestLegacyLaunchersNoticeMigrationWhileRunning(t *testing.T) {
	for _, name := range []string{"p7boot.sh", "p7watch.sh"} {
		t.Run(name, func(t *testing.T) {
			root := t.TempDir()
			bin := t.TempDir()
			// The only executable component is a local fixture that records an error
			// if launched. No downloaded binaries or real vehicle paths are used.
			os.WriteFile(filepath.Join(root, "p7assist"), []byte("#!/bin/sh\necho invoked >> \"$P7_TEST_ROOT/unexpected\"\n"), 0700)
			os.WriteFile(filepath.Join(bin, "pidof"), []byte("#!/bin/sh\nmkdir -p \"$P7_TEST_ROOT/update-v1\"\nexit 0\n"), 0700)
			if name == "p7watch.sh" {
				os.WriteFile(filepath.Join(bin, "pidof"), []byte("#!/bin/sh\nmkdir -p \"$P7_TEST_ROOT/update-v1\"\nexit 1\n"), 0700)
			}
			raw, e := os.ReadFile(filepath.Join("autostart", name))
			if e != nil {
				t.Fatal(e)
			}
			script := strings.Replace(string(raw), "D=/data/p7assist", "D="+root, 1)
			ctx, cancel := context.WithTimeout(context.Background(), 4*time.Second)
			defer cancel()
			cmd := exec.CommandContext(ctx, "sh", "-c", script)
			cmd.Env = append(os.Environ(), "P7_TEST_ROOT="+root, "PATH="+bin+":/usr/bin:/bin")
			out, e := cmd.CombinedOutput()
			exit, ok := e.(*exec.ExitError)
			if !ok || exit.ExitCode() != 73 {
				t.Fatal("did not stop for dynamic migration", e, string(out))
			}
			if _, e := os.Stat(filepath.Join(root, "unexpected")); !os.IsNotExist(e) {
				t.Fatal("legacy cleanup/daemon ran after marker")
			}
		})
	}
}
