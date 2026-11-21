package update

import (
	"crypto/rsa"
	"crypto/x509"
	"os"
	"os/exec"
	"path/filepath"
	"testing"
)

// Child is this test binary, not any downloaded/vehicle executable. Exit at an
// exact filesystem boundary bypasses defers to simulate updater-process loss.
// This does not model storage-controller power loss or lying fsync.
func TestCrashWorker(t *testing.T) {
	root := os.Getenv("P7_TEST_CRASH_ROOT")
	if root == "" {
		return
	}
	raw, e := os.ReadFile(filepath.Join(root, "test-public.der"))
	if e != nil {
		t.Fatal(e)
	}
	any, e := x509.ParsePKIXPublicKey(raw)
	if e != nil {
		t.Fatal(e)
	}
	key := any.(*rsa.PublicKey)
	m, e := os.ReadFile(filepath.Join(root, "test-candidate.json"))
	if e != nil {
		t.Fatal(e)
	}
	sig, e := os.ReadFile(filepath.Join(root, "test-candidate.sig"))
	if e != nil {
		t.Fatal(e)
	}
	next, e := Verify(m, sig, key)
	if e != nil {
		t.Fatal(e)
	}
	s, e := OpenStore(root, key)
	if e != nil {
		t.Fatal(e)
	}
	action, point := os.Getenv("P7_TEST_CRASH_ACTION"), os.Getenv("P7_TEST_CRASH_POINT")
	var trial Snapshot
	if action != "trial" {
		trial, e = s.SimulateTrial(next, compatible(), "child-boot")
		if e != nil {
			t.Fatal(e)
		}
	}
	s.failpoint = func(p string) error {
		if p == point {
			os.Exit(99)
		}
		return nil
	}
	switch action {
	case "trial":
		_, e = s.SimulateTrial(next, compatible(), "child-boot")
	case "confirm":
		e = s.SimulateConfirm(trial.Nonce, trial.Boot)
	case "recover":
		_, e = s.Recover()
	case "generation":
		_, e = s.MaterializeRecovery(filepath.Join(root, "test-generations"))
	}
	t.Fatalf("crash point not reached: %s %s %v", action, point, e)
}
func TestActualProcessLossRecovery(t *testing.T) {
	for _, action := range []string{"trial", "confirm", "recover", "generation"} {
		points := []string{"write", "file-sync", "rename", "directory-sync"}
		if action == "generation" {
			points = []string{"generation-file", "generation-dir-sync", "generation-rename", "generation-parent-sync"}
		}
		for _, point := range points {
			t.Run(action+"/"+point, func(t *testing.T) {
				root, k, s, next := setupMultiStore(t)
				s.Close()
				pub, _ := x509.MarshalPKIXPublicKey(&k.PublicKey)
				os.WriteFile(filepath.Join(root, "test-public.der"), pub, 0600)
				os.WriteFile(filepath.Join(root, "test-candidate.json"), next.raw, 0600)
				os.WriteFile(filepath.Join(root, "test-candidate.sig"), next.signature, 0600)
				os.Mkdir(filepath.Join(root, "test-generations"), 0700)
				cmd := exec.Command(os.Args[0], "-test.run=^TestCrashWorker$")
				cmd.Env = append(os.Environ(), "P7_TEST_CRASH_ROOT="+root, "P7_TEST_CRASH_ACTION="+action, "P7_TEST_CRASH_POINT="+point)
				out, e := cmd.CombinedOutput()
				exit, ok := e.(*exec.ExitError)
				if !ok || exit.ExitCode() != 99 {
					t.Fatalf("unexpected child result %v %s", e, out)
				}
				reopened, e := OpenStore(root, &k.PublicKey)
				if e != nil {
					t.Fatal(e)
				}
				defer reopened.Close()
				state, e := reopened.Recover()
				if e != nil {
					t.Fatal(e)
				}
				want := uint64(1)
				if action == "confirm" && (point == "rename" || point == "directory-sync") {
					want = 2
				}
				for id, a := range state.Components {
					if a.Version != want {
						t.Fatal("mixed generation", id, state)
					}
				}
				floor := uint64(2)
				if action == "trial" && (point == "write" || point == "file-sync") {
					floor = 1
				}
				if state.SequenceFloor != floor {
					t.Fatal("floor regressed", state)
				}
				gen, e := reopened.MaterializeRecovery(filepath.Join(root, "test-generations"))
				if e != nil || gen.Sequence != want {
					t.Fatal(gen, e)
				}
			})
		}
	}
}
