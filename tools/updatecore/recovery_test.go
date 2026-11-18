package update

import (
	"bytes"
	"errors"
	"os"
	"path/filepath"
	"testing"
)

func TestRecoveryGenerationCrashBoundaries(t *testing.T) {
	for _, point := range []string{"generation-file", "generation-dir-sync", "generation-rename", "generation-parent-sync"} {
		t.Run(point, func(t *testing.T) {
			root, k, s, next := setupMultiStore(t)
			dest := t.TempDir()
			os.Chmod(dest, 0700)
			if _, e := s.SimulateTrial(next, compatible(), "boot"); e != nil {
				t.Fatal(e)
			}
			s.failpoint = func(p string) error {
				if p == point {
					return errors.New("cut power boundary")
				}
				return nil
			}
			if _, e := s.MaterializeRecovery(dest); e == nil {
				t.Fatal("fault not injected")
			}
			s.Close()
			s, e := OpenStore(root, &k.PublicKey)
			if e != nil {
				t.Fatal(e)
			}
			defer s.Close()
			gen, e := s.MaterializeRecovery(dest)
			if e != nil {
				t.Fatal(e)
			}
			if gen.Sequence != 1 || len(gen.Components) != 3 {
				t.Fatal(gen)
			}
			for id, path := range gen.Components {
				got, e := os.ReadFile(path)
				if e != nil || !bytes.Equal(got, []byte(id+"1")) {
					t.Fatal(id, string(got), e)
				}
				st, _ := os.Stat(path)
				if st.Mode().Perm()&0111 != 0 {
					t.Fatal("recovery export became executable")
				}
			}
			state, e := s.Recover()
			if e != nil || state.SequenceFloor != 2 {
				t.Fatal(state, e)
			}
		})
	}
}
func TestRecoveryGenerationTamperingFailsClosed(t *testing.T) {
	_, _, s, _ := setupMultiStore(t)
	defer s.Close()
	dest := t.TempDir()
	os.Chmod(dest, 0700)
	gen, e := s.MaterializeRecovery(dest)
	if e != nil {
		t.Fatal(e)
	}
	p := gen.Components["instrument"]
	os.Chmod(p, 0600)
	os.WriteFile(p, []byte("corrupt"), 0600)
	if _, e := s.MaterializeRecovery(dest); e == nil {
		t.Fatal("corrupt export reused")
	}
	// Do not repair or silently overwrite damaged recovery material.
	got, _ := os.ReadFile(p)
	if string(got) != "corrupt" {
		t.Fatal("overwrote corrupt generation")
	}
}
func TestRecoveryUsesCommittedGenerationAndRejectsSymlinks(t *testing.T) {
	_, _, s, next := setupMultiStore(t)
	defer s.Close()
	trial, e := s.SimulateTrial(next, compatible(), "boot")
	if e != nil {
		t.Fatal(e)
	}
	if e = s.SimulateConfirm(trial.Nonce, trial.Boot); e != nil {
		t.Fatal(e)
	}
	dest := t.TempDir()
	os.Chmod(dest, 0700)
	gen, e := s.MaterializeRecovery(dest)
	if e != nil || gen.Sequence != 2 {
		t.Fatal(gen, e)
	}
	p := gen.Components["player"]
	os.Remove(p)
	os.Symlink(gen.Components["instrument"], p)
	if _, e = s.MaterializeRecovery(dest); e == nil {
		t.Fatal("symlink accepted")
	}
	link := filepath.Join(t.TempDir(), "link")
	os.Symlink(dest, link)
	if _, e = s.MaterializeRecovery(link); e == nil {
		t.Fatal("symlink parent accepted")
	}
}

func TestExistingGenerationRequiresParentSyncOnRetry(t *testing.T) {
	_, _, s, _ := setupMultiStore(t)
	defer s.Close()
	dest := t.TempDir()
	os.Chmod(dest, 0700)
	s.failpoint = func(p string) error {
		if p == "generation-rename" {
			return errors.New("lost before parent fsync")
		}
		return nil
	}
	if _, e := s.MaterializeRecovery(dest); e == nil {
		t.Fatal("rename fault not hit")
	}
	synced := 0
	s.failpoint = func(p string) error {
		if p == "generation-parent-sync" {
			synced++
			return errors.New("parent sync acknowledgement failed")
		}
		return nil
	}
	if _, e := s.MaterializeRecovery(dest); e == nil {
		t.Fatal("existing directory skipped durability gate")
	}
	if synced != 1 {
		t.Fatal("parent sync not reached", synced)
	}
	s.failpoint = nil
	if _, e := s.MaterializeRecovery(dest); e != nil {
		t.Fatal(e)
	}
}
