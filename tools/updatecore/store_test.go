package update

import (
	"bytes"
	"crypto/rand"
	"crypto/rsa"
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"testing"
)

func bundle(t *testing.T, k *rsa.PrivateKey, seq uint64, data []byte) VerifiedManifest {
	t.Helper()
	m := sample()
	m.Sequence = seq
	m.Artifacts[0] = artifact(data)
	m.Artifacts[0].Version = seq
	raw, _ := json.Marshal(m)
	v, e := Verify(raw, signed(t, k, raw), &k.PublicKey)
	if e != nil {
		t.Fatal(e)
	}
	return v
}
func stageBundle(t *testing.T, root string, v VerifiedManifest, data []byte) {
	t.Helper()
	p, e := Plan(v, Device{Target: "cluster-linux-armv7", APKCode: 101, Protocol: 1, InstrumentABI: "linux-armv7"})
	if e != nil {
		t.Fatal(e)
	}
	for _, a := range p {
		if _, e := Stage(root, a, bytes.NewReader(data)); e != nil {
			t.Fatal(e)
		}
	}
}
func setupStore(t *testing.T) (string, *rsa.PrivateKey, *Store, VerifiedManifest) {
	t.Helper()
	root := t.TempDir()
	os.Chmod(root, 0700)
	k, e := rsa.GenerateKey(rand.Reader, 2048)
	if e != nil {
		t.Fatal(e)
	}
	old := bundle(t, k, 1, []byte("old"))
	next := bundle(t, k, 2, []byte("new"))
	stageBundle(t, root, old, []byte("old"))
	stageBundle(t, root, next, []byte("new"))
	s, e := BootstrapStore(root, &k.PublicKey, old)
	if e != nil {
		t.Fatal(e)
	}
	return root, k, s, next
}
func compatible() Device {
	return Device{Target: "cluster-linux-armv7", APKCode: 101, Protocol: 1, InstrumentABI: "linux-armv7"}
}
func TestStoreDurableTrialRecovery(t *testing.T) {
	root, k, s, next := setupStore(t)
	if _, e := OpenStore(root, &k.PublicKey); e == nil {
		t.Fatal("second writer")
	}
	if e := s.Activate(); e == nil {
		t.Fatal("production activation allowed")
	}
	trial, e := s.SimulateTrial(next, compatible(), "boot-1")
	if e != nil {
		t.Fatal(e)
	}
	if e = s.SimulateConfirm("stale", trial.Boot); e == nil {
		t.Fatal("stale health")
	}
	s.Close()
	s, e = OpenStore(root, &k.PublicKey)
	if e != nil {
		t.Fatal(e)
	}
	defer s.Close()
	state, e := s.Recover()
	if e != nil {
		t.Fatal(e)
	}
	if state.Components["instrument"].Version != 1 || state.SequenceFloor != 2 {
		t.Fatal(state)
	}
	if _, e = s.SimulateTrial(next, compatible(), "boot-2"); e == nil {
		t.Fatal("replayed failed release")
	}
	if e = s.SimulateConfirm(trial.Nonce, trial.Boot); e == nil {
		t.Fatal("late health")
	}
}
func TestStoreConfirmIsDurable(t *testing.T) {
	root, k, s, next := setupStore(t)
	trial, e := s.SimulateTrial(next, compatible(), "boot")
	if e != nil {
		t.Fatal(e)
	}
	if e = s.SimulateConfirm(trial.Nonce, trial.Boot); e != nil {
		t.Fatal(e)
	}
	s.Close()
	s, e = OpenStore(root, &k.PublicKey)
	if e != nil {
		t.Fatal(e)
	}
	defer s.Close()
	state, e := s.Recover()
	if e != nil || state.Components["instrument"].Version != 2 {
		t.Fatalf("%+v %v", state, e)
	}
	if _, e = s.SimulateTrial(next, compatible(), "boot2"); e == nil {
		t.Fatal("confirmed release replay")
	}
}
func TestStoreCrashBoundaries(t *testing.T) {
	for _, action := range []string{"trial", "confirm", "recover"} {
		for _, point := range []string{"write", "file-sync", "rename", "directory-sync"} {
			t.Run(action+"/"+point, func(t *testing.T) {
				root, k, s, next := setupMultiStore(t)
				var trial Snapshot
				var e error
				if action != "trial" {
					trial, e = s.SimulateTrial(next, compatible(), "boot")
					if e != nil {
						t.Fatal(e)
					}
				}
				s.failpoint = func(p string) error {
					if p == point {
						return errors.New("simulated crash")
					}
					return nil
				}
				switch action {
				case "trial":
					_, e = s.SimulateTrial(next, compatible(), "boot")
				case "confirm":
					e = s.SimulateConfirm(trial.Nonce, trial.Boot)
				case "recover":
					_, e = s.Recover()
				}
				if e == nil {
					t.Fatal("fault not injected")
				}
				if _, e = s.Recover(); e == nil {
					t.Fatal("uncertain write store reused")
				}
				s.Close()
				s, e = OpenStore(root, &k.PublicKey)
				if e != nil {
					t.Fatal(e)
				}
				defer s.Close()
				state, e := s.Recover()
				if e != nil {
					t.Fatal(e)
				}
				want := uint64(1)
				if action == "confirm" && (point == "rename" || point == "directory-sync") {
					want = 2
				}
				for _, id := range []string{"instrument", "player", "charge-pack"} {
					if state.Components[id].Version != want {
						t.Fatal(state)
					}
				}
				// Before a trial rename there is no accepted sequence; after rename and
				// across every confirm/recover boundary the floor must remain 2.
				floor := uint64(2)
				if action == "trial" && (point == "write" || point == "file-sync") {
					floor = 1
				}
				if state.SequenceFloor != floor {
					t.Fatal(state)
				}
			})
		}
	}
}
func TestStoreCorruptionAndTrust(t *testing.T) {
	root, k, s, next := setupStore(t)
	s.Close()
	other, _ := rsa.GenerateKey(rand.Reader, 2048)
	if x, e := OpenStore(root, &other.PublicKey); e == nil {
		x.Close()
		t.Fatal("wrong trust root")
	}
	s, e := OpenStore(root, &k.PublicKey)
	if e != nil {
		t.Fatal(e)
	}
	if _, e = s.SimulateTrial(next, Device{}, "boot"); e == nil {
		t.Fatal("incompatible")
	}
	// Receiver rejects a same-length corrupted candidate, preserving good.
	os.WriteFile(filepath.Join(root, next.manifest.Artifacts[0].SHA256+".verified"), []byte("BAD"), 0600)
	if _, e = s.SimulateTrial(next, compatible(), "boot"); e == nil {
		t.Fatal("corrupt candidate")
	}
	s.Close()
	os.WriteFile(filepath.Join(root, "journal.json"), []byte("{}"), 0600)
	if x, e := OpenStore(root, &k.PublicKey); e == nil {
		x.Close()
		t.Fatal("corrupt journal")
	}
	if x, e := BootstrapStore(root, &k.PublicKey, next); e == nil {
		x.Close()
		t.Fatal("reset existing state")
	}
}
func TestPendingDamagedCandidateStillRollsBack(t *testing.T) {
	root, k, s, next := setupStore(t)
	if _, e := s.SimulateTrial(next, compatible(), "boot"); e != nil {
		t.Fatal(e)
	}
	s.Close()
	os.Remove(filepath.Join(root, next.manifest.Artifacts[0].SHA256+".verified"))
	s, e := OpenStore(root, &k.PublicKey)
	if e != nil {
		t.Fatal(e)
	}
	defer s.Close()
	state, e := s.Recover()
	if e != nil || state.Components["instrument"].Version != 1 {
		t.Fatal(state, e)
	}
}

func TestReopenRevokesOldHealthBeforeAnyCallerAction(t *testing.T) {
	root, k, s, next := setupStore(t)
	trial, e := s.SimulateTrial(next, compatible(), "old-boot")
	if e != nil {
		t.Fatal(e)
	}
	s.Close()
	s, e = OpenStore(root, &k.PublicKey)
	if e != nil {
		t.Fatal(e)
	}
	defer s.Close()
	if e = s.SimulateConfirm(trial.Nonce, trial.Boot); e == nil {
		t.Fatal("restarted process accepted old health")
	}
}

func TestCompleteBundleGeneration(t *testing.T) {
	root := t.TempDir()
	os.Chmod(root, 0700)
	k, e := rsa.GenerateKey(rand.Reader, 2048)
	if e != nil {
		t.Fatal(e)
	}
	makeBundle := func(seq uint64, ids []string) VerifiedManifest {
		m := sample()
		m.Sequence = seq
		m.Artifacts = nil
		for _, id := range ids {
			data := []byte(id + string(rune('0'+seq)))
			a := artifact(data)
			a.Component = id
			a.Version = seq
			m.Artifacts = append(m.Artifacts, a)
		}
		raw, _ := json.Marshal(m)
		v, e := Verify(raw, signed(t, k, raw), &k.PublicKey)
		if e != nil {
			t.Fatal(e)
		}
		p, e := Plan(v, compatible())
		if e != nil {
			t.Fatal(e)
		}
		for _, a := range p {
			data := []byte(a.Metadata().Component + string(rune('0'+seq)))
			if _, e := Stage(root, a, bytes.NewReader(data)); e != nil {
				t.Fatal(e)
			}
		}
		return v
	}
	ids := []string{"instrument", "player", "charge-pack"}
	old := makeBundle(1, ids)
	next := makeBundle(2, ids)
	s, e := BootstrapStore(root, &k.PublicKey, old)
	if e != nil {
		t.Fatal(e)
	}
	missing := makeBundle(3, []string{"player", "charge-pack"})
	if _, e := s.SimulateTrial(missing, compatible(), "boot"); e == nil {
		t.Fatal("omitted old instrument")
	}
	if _, e := s.SimulateTrial(next, compatible(), "boot"); e != nil {
		t.Fatal(e)
	}
	s.Close()
	s, e = OpenStore(root, &k.PublicKey)
	if e != nil {
		t.Fatal(e)
	}
	state, e := s.Recover()
	if e != nil {
		t.Fatal(e)
	}
	for _, id := range ids {
		if state.Components[id].Version != 1 {
			t.Fatal("mixed rollback", state)
		}
	}
	third := makeBundle(4, ids)
	trial, e := s.SimulateTrial(third, compatible(), "boot2")
	if e != nil {
		t.Fatal(e)
	}
	if e := s.SimulateConfirm(trial.Nonce, trial.Boot); e != nil {
		t.Fatal(e)
	}
	s.Close()
	s, e = OpenStore(root, &k.PublicKey)
	if e != nil {
		t.Fatal(e)
	}
	defer s.Close()
	state, e = s.Recover()
	if e != nil {
		t.Fatal(e)
	}
	for _, id := range ids {
		if state.Components[id].Version != 4 {
			t.Fatal("mixed confirm", state)
		}
	}
}

func TestStoreRejectsCrossDeviceBundle(t *testing.T) {
	root, k, s, _ := setupStore(t)
	defer s.Close()
	m := sample()
	m.Sequence = 3
	probe := m.Artifacts[0]
	probe.Component = "probe-bsd"
	m.Artifacts = append(m.Artifacts, probe)
	raw, _ := json.Marshal(m)
	v, e := Verify(raw, signed(t, k, raw), &k.PublicKey)
	if e != nil {
		t.Fatal(e)
	}
	if _, e = s.SimulateTrial(v, compatible(), "boot"); e == nil {
		t.Fatal("cross-device trial")
	}
	fresh := t.TempDir()
	os.Chmod(fresh, 0700)
	if bad, e := BootstrapStore(fresh, &k.PublicKey, v); e == nil {
		bad.Close()
		t.Fatal("cross-device baseline")
	}
	// Rejection must not create a journal, or alter the existing good state.
	if _, e = os.Stat(filepath.Join(fresh, "journal.json")); !os.IsNotExist(e) {
		t.Fatal("baseline partially persisted")
	}
	state, e := s.Recover()
	if e != nil || state.Components["instrument"].Version != 1 {
		t.Fatal(root, state, e)
	}
}

func setupMultiStore(t *testing.T) (string, *rsa.PrivateKey, *Store, VerifiedManifest) {
	t.Helper()
	root := t.TempDir()
	os.Chmod(root, 0700)
	k, e := rsa.GenerateKey(rand.Reader, 2048)
	if e != nil {
		t.Fatal(e)
	}
	makeBundle := func(seq uint64) VerifiedManifest {
		m := sample()
		m.Sequence = seq
		m.Artifacts = nil
		data := map[string][]byte{}
		for _, id := range []string{"instrument", "player", "charge-pack"} {
			payload := []byte(id + string(rune('0'+seq)))
			data[id] = payload
			a := artifact(payload)
			a.Component = id
			a.Version = seq
			m.Artifacts = append(m.Artifacts, a)
		}
		raw, _ := json.Marshal(m)
		v, e := Verify(raw, signed(t, k, raw), &k.PublicKey)
		if e != nil {
			t.Fatal(e)
		}
		p, e := Plan(v, compatible())
		if e != nil {
			t.Fatal(e)
		}
		for _, a := range p {
			if _, e := Stage(root, a, bytes.NewReader(data[a.Metadata().Component])); e != nil {
				t.Fatal(e)
			}
		}
		return v
	}
	old, next := makeBundle(1), makeBundle(2)
	s, e := BootstrapStore(root, &k.PublicKey, old)
	if e != nil {
		t.Fatal(e)
	}
	return root, k, s, next
}
