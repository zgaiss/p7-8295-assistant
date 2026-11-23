package update

// The durable store is an offline receiver-side reference implementation.
// It never changes a process, boot script, symlink, vehicle path or framebuffer.
import (
	"bytes"
	"crypto/rand"
	"crypto/rsa"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"math/big"
	"os"
	"path/filepath"
	"sync"
	"syscall"
)

type envelope struct{ Raw, Signature []byte }
type journal struct {
	Schema    int
	Floor     uint64
	Phase     string
	Good      envelope
	Candidate *envelope
	Nonce     string
	Boot      string
}
type Store struct {
	mu       sync.Mutex
	poisoned bool
	root     string
	key      *rsa.PublicKey
	lock     *os.File
	// failpoint is deliberately private and used only for crash-boundary tests.
	failpoint func(string) error
}
type Snapshot struct {
	SequenceFloor      uint64
	Phase, Nonce, Boot string
	Components         map[string]Artifact
}

// OpenStore requires an already-created private receiver directory and a
// trusted pin. One process owns it exclusively. It refuses absent/corrupt
// state; callers must bootstrap explicitly, never reset a failed OpenStore.
// Reopening automatically revokes an unconfirmed trial before returning, so a
// delayed health message from a previous updater process cannot confirm it.
func OpenStore(root string, key *rsa.PublicKey) (*Store, error) {
	s, e := lockStore(root, key)
	if e != nil {
		return nil, e
	}
	if _, e = s.Recover(); e != nil {
		s.Close()
		return nil, e
	}
	return s, nil
}
func lockStore(root string, key *rsa.PublicKey) (*Store, error) {
	if key == nil || key.N == nil || key.N.BitLen() < 2048 {
		return nil, errors.New("missing trusted key")
	}
	st, e := os.Lstat(root)
	if e != nil || !st.IsDir() || st.Mode().Perm()&0077 != 0 {
		return nil, errors.New("private 0700 store required")
	}
	// Parent path ownership/stability remains a deployment prerequisite.
	p := filepath.Join(root, "lock")
	fd, e := syscall.Open(p, syscall.O_CREAT|syscall.O_RDWR|syscall.O_NOFOLLOW, 0600)
	if e != nil {
		return nil, e
	}
	f := os.NewFile(uintptr(fd), p)
	if e = syscall.Flock(fd, syscall.LOCK_EX|syscall.LOCK_NB); e != nil {
		f.Close()
		return nil, errors.New("store already open")
	}
	return &Store{root: root, key: &rsa.PublicKey{N: new(big.Int).Set(key.N), E: key.E}, lock: f}, nil
}
func (s *Store) Close() error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.lock == nil {
		return nil
	}
	e := s.lock.Close()
	s.lock = nil
	return e
}
func (s *Store) live() error {
	if s.lock == nil || s.poisoned {
		return errors.New("store closed or uncertain write; reopen required")
	}
	return nil
}
func (s *Store) check(v VerifiedManifest) error {
	if v.manifest == nil {
		return errors.New("unverified bundle")
	}
	// Verification is repeated using this receiver's own pin.
	if _, e := Verify(v.raw, v.signature, s.key); e != nil {
		return e
	}
	for _, a := range v.manifest.Artifacts {
		p := filepath.Join(s.root, a.SHA256+".verified")
		fd, e := syscall.Open(p, syscall.O_RDONLY|syscall.O_NOFOLLOW, 0)
		if e != nil {
			return e
		}
		f := os.NewFile(uintptr(fd), p)
		st, e := f.Stat()
		if e != nil || !st.Mode().IsRegular() || st.Size() != a.Size {
			f.Close()
			return errors.New("receiver artifact size/type")
		}
		h := sha256.New()
		n, e := io.Copy(h, io.LimitReader(f, a.Size+1))
		f.Close()
		if e != nil || n != a.Size || hex.EncodeToString(h.Sum(nil)) != a.SHA256 {
			return errors.New("receiver hash mismatch")
		}
	}
	return nil
}
func (s *Store) decode(e envelope) (VerifiedManifest, error) {
	v, err := Verify(e.Raw, e.Signature, s.key)
	if err != nil {
		return v, err
	}
	return v, s.check(v)
}
func (s *Store) read() (journal, error) {
	var j journal
	if e := s.live(); e != nil {
		return j, e
	}
	fd, e := syscall.Open(filepath.Join(s.root, "journal.json"), syscall.O_RDONLY|syscall.O_NOFOLLOW, 0)
	if e != nil {
		return j, e
	}
	f := os.NewFile(uintptr(fd), "journal")
	defer f.Close()
	b, e := io.ReadAll(io.LimitReader(f, 4*MaxManifest+1))
	if e != nil || len(b) > 4*MaxManifest {
		return j, errors.New("journal size")
	}
	if e = json.Unmarshal(b, &j); e != nil {
		return j, e
	}
	canonical, e := json.Marshal(j)
	if e != nil || !bytes.Equal(b, canonical) {
		return j, errors.New("noncanonical/corrupt journal")
	}
	if j.Schema != 1 || j.Floor == 0 {
		return j, errors.New("journal schema")
	}
	good, e := s.decode(j.Good)
	if e != nil {
		return j, e
	}
	if j.Floor < good.manifest.Sequence {
		return j, errors.New("journal floor")
	}
	switch j.Phase {
	case "good":
		if j.Candidate != nil || j.Nonce != "" || j.Boot != "" {
			return j, errors.New("good journal inconsistent")
		}
	case "pending":
		if j.Candidate == nil || len(j.Nonce) != 32 || j.Boot == "" {
			return j, errors.New("pending journal inconsistent")
		}
		// Candidate may have been damaged by the failed trial. Recovery must not
		// require it to be healthy: signature/sequence suffice; good is rehashed.
		v, e := Verify(j.Candidate.Raw, j.Candidate.Signature, s.key)
		if e != nil {
			return j, e
		}
		if v.manifest.Sequence != j.Floor || v.manifest.Sequence <= good.manifest.Sequence {
			return j, errors.New("candidate sequence")
		}
	default:
		return j, errors.New("journal phase")
	}
	return j, nil
}
func (s *Store) checkpoint(point string) error {
	if s.failpoint != nil {
		return s.failpoint(point)
	}
	return nil
}
func (s *Store) write(j journal) (err error) {
	defer func() {
		if err != nil {
			s.poisoned = true
		}
	}()
	if e := s.live(); e != nil {
		return e
	}
	b, e := json.Marshal(j)
	if e != nil {
		return e
	}
	f, e := os.CreateTemp(s.root, "journal-*.part")
	if e != nil {
		return e
	}
	defer os.Remove(f.Name())
	defer f.Close()
	if _, e = f.Write(b); e != nil {
		return e
	}
	if e = s.checkpoint("write"); e != nil {
		return e
	}
	if e = f.Sync(); e != nil {
		return e
	}
	if e = s.checkpoint("file-sync"); e != nil {
		return e
	}
	if e = f.Close(); e != nil {
		return e
	}
	if e = os.Rename(f.Name(), filepath.Join(s.root, "journal.json")); e != nil {
		return e
	}
	if e = s.checkpoint("rename"); e != nil {
		return e
	}
	d, e := os.Open(s.root)
	if e != nil {
		return e
	}
	defer d.Close()
	if e = d.Sync(); e != nil {
		return e
	}
	return s.checkpoint("directory-sync")
}

// BootstrapStore is an explicit offline provisioning operation. It requires
// an empty journal, verified complete baseline and already-staged artifacts.
// Never call it as automatic recovery from an invalid existing store.
func BootstrapStore(root string, key *rsa.PublicKey, baseline VerifiedManifest) (*Store, error) {
	s, e := lockStore(root, key)
	if e != nil {
		return nil, e
	}
	if baseline.manifest == nil {
		s.Close()
		return nil, errors.New("unverified baseline")
	}
	fail := func(e error) (*Store, error) { s.Close(); return nil, e }
	if _, e = os.Lstat(filepath.Join(root, "journal.json")); !os.IsNotExist(e) {
		return fail(errors.New("existing journal; refuse bootstrap"))
	}
	if _, e = Plan(baseline, Device{Target: "cluster-linux-armv7", APKCode: baseline.manifest.MinAPK, Protocol: baseline.manifest.Protocol, InstrumentABI: baseline.manifest.InstrumentABI}); e != nil {
		return fail(e)
	}
	if e = s.check(baseline); e != nil {
		return fail(e)
	}
	j := journal{Schema: 1, Floor: baseline.manifest.Sequence, Phase: "good", Good: envelope{baseline.raw, baseline.signature}}
	if e = s.write(j); e != nil {
		return fail(e)
	}
	return s, nil
}

// SimulateTrial records a pending bundle before exposing it to a LAB caller.
// It is NOT an activation API. There is no production activation implementation.
// Every bundle must describe all current components to allow atomic rollback.
func (s *Store) SimulateTrial(v VerifiedManifest, d Device, boot string) (Snapshot, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	j, e := s.read()
	if e != nil {
		return Snapshot{}, e
	}
	if j.Phase != "good" || boot == "" {
		return Snapshot{}, errors.New("trial state")
	}
	good, e := s.decode(j.Good)
	if e != nil {
		return Snapshot{}, e
	}
	d.LastSequence = j.Floor
	d.Installed = map[string]Installed{}
	for _, a := range good.manifest.Artifacts {
		d.Installed[a.Component] = Installed{a.Version, a.SHA256}
	}
	if _, e = Plan(v, d); e != nil {
		return Snapshot{}, e
	}
	if e = s.check(v); e != nil {
		return Snapshot{}, e
	}
	components := map[string]bool{}
	for _, a := range v.manifest.Artifacts {
		components[a.Component] = true
	}
	for _, a := range good.manifest.Artifacts {
		if !components[a.Component] {
			return Snapshot{}, errors.New("bundle removes installed component")
		}
	}
	var nonce [16]byte
	if _, e = rand.Read(nonce[:]); e != nil {
		return Snapshot{}, e
	}
	j.Phase = "pending"
	j.Floor = v.manifest.Sequence
	j.Candidate = &envelope{v.raw, v.signature}
	j.Nonce = hex.EncodeToString(nonce[:])
	j.Boot = boot
	if e = s.write(j); e != nil {
		return Snapshot{}, e
	}
	return snapshot(j, v), nil
}

// SimulateConfirm requires the exact nonce/boot from the pending trial. A real
// authenticated health probe is intentionally absent; this API is LAB ONLY.
func (s *Store) SimulateConfirm(nonce, boot string) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	j, e := s.read()
	if e != nil {
		return e
	}
	if j.Phase != "pending" || j.Nonce != nonce || j.Boot != boot {
		return errors.New("stale health confirmation")
	}
	if _, e = s.decode(*j.Candidate); e != nil {
		return e
	}
	j.Good = *j.Candidate
	j.Candidate = nil
	j.Phase = "good"
	j.Nonce = ""
	j.Boot = ""
	return s.write(j)
}

// Recover always returns known-good; an unconfirmed trial is discarded and its
// anti-replay floor retained. No process is launched and no retries occur.
func (s *Store) Recover() (Snapshot, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	j, e := s.read()
	if e != nil {
		return Snapshot{}, e
	}
	if j.Phase == "pending" {
		j.Candidate = nil
		j.Phase = "good"
		j.Nonce = ""
		j.Boot = ""
		if e = s.write(j); e != nil {
			return Snapshot{}, e
		}
	}
	v, e := s.decode(j.Good)
	if e != nil {
		return Snapshot{}, e
	}
	return snapshot(j, v), nil
}
func snapshot(j journal, v VerifiedManifest) Snapshot {
	out := Snapshot{j.Floor, j.Phase, j.Nonce, j.Boot, map[string]Artifact{}}
	for _, a := range v.manifest.Artifacts {
		out.Components[a.Component] = a
	}
	return out
}

// Activate is deliberately unavailable until on-device integration validates
// parked-state provenance, recovery root, authenticated health and filesystem.
func (s *Store) Activate() error {
	return errors.New("production activation disabled: bootstrap/vehicle validation required")
}
