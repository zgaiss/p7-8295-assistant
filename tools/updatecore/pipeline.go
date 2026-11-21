package update

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"io"
	"os"
	"path/filepath"
	"syscall"
)

// Cached verifies a private content-addressed cache entry without following its
// final symlink. Missing or corrupted entries are never treated as installed.
func Cached(root string, a VerifiedArtifact) bool {
	if !a.verified {
		return false
	}
	p := filepath.Join(root, a.artifact.SHA256+".verified")
	fd, e := syscall.Open(p, syscall.O_RDONLY|syscall.O_NOFOLLOW, 0)
	if e != nil {
		return false
	}
	f := os.NewFile(uintptr(fd), p)
	defer f.Close()
	st, e := f.Stat()
	if e != nil || !st.Mode().IsRegular() || st.Size() != a.artifact.Size {
		return false
	}
	h := sha256.New()
	n, e := io.Copy(h, io.LimitReader(f, a.artifact.Size+1))
	return e == nil && n == a.artifact.Size && hex.EncodeToString(h.Sum(nil)) == a.artifact.SHA256
}

// Prepare derives anti-replay and component versions from receiver state, never
// caller claims. No accepted floor changes until SimulateTrial is persisted.
func (s *Store) Prepare(v VerifiedManifest, d Device) ([]VerifiedArtifact, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	// A VerifiedManifest may have been verified under a DIFFERENT pin by its
	// caller. Bind it to this receiver before any planning, download or write.
	if v.manifest == nil {
		return nil, errors.New("unverified manifest")
	}
	if _, e := Verify(v.raw, v.signature, s.key); e != nil {
		return nil, e
	}
	j, e := s.read()
	if e != nil {
		return nil, e
	}
	if j.Phase != "good" {
		return nil, errors.New("pending trial")
	}
	good, e := s.decode(j.Good)
	if e != nil {
		return nil, e
	}
	d.LastSequence = j.Floor
	d.Installed = map[string]Installed{}
	present := map[string]bool{}
	if v.manifest == nil {
		return nil, errors.New("unverified manifest")
	}
	for _, a := range v.manifest.Artifacts {
		present[a.Component] = true
	}
	for _, a := range good.manifest.Artifacts {
		if !present[a.Component] {
			return nil, errors.New("candidate omits baseline component")
		}
		d.Installed[a.Component] = Installed{a.Version, a.SHA256}
	}
	return Plan(v, d)
}

type StageResult struct{ Downloaded, SenderCached, Transferred, ReceiverCached int }

// StageRelease runs the actual byte path from a GitHub downloader through a
// separate host cache into the receiver's content-addressed store. Receiver
// independently rehashes after every copy. It does NOT activate anything.
// This implementation is a same-host/off-car bench transport, NOT vehicle FTP.
func StageRelease(ctx context.Context, d *Downloader, sender string, receiver *Store, v VerifiedManifest, device Device) (StageResult, error) {
	var result StageResult
	plan, e := receiver.Prepare(v, device)
	if e != nil {
		return result, e
	}
	for _, a := range plan {
		if Cached(receiver.root, a) {
			result.ReceiverCached++
			continue
		}
		if Cached(sender, a) {
			result.SenderCached++
		} else {
			if _, e = d.Fetch(ctx, sender, a); e != nil {
				return result, e
			}
			result.Downloaded++
		}
		p := filepath.Join(sender, a.artifact.SHA256+".verified")
		fd, e := syscall.Open(p, syscall.O_RDONLY|syscall.O_NOFOLLOW, 0)
		if e != nil {
			return result, e
		}
		f := os.NewFile(uintptr(fd), p)
		_, e = Stage(receiver.root, a, f)
		f.Close()
		if e != nil {
			return result, e
		}
		result.Transferred++
	}
	// All candidate entries including unchanged installed content are rechecked
	// using the receiver pin. Another thread may have advanced the journal, but
	// this is staging only; trial/activation will check it again under the lock.
	receiver.mu.Lock()
	defer receiver.mu.Unlock()
	if e = receiver.live(); e != nil {
		return result, e
	}
	if e = receiver.check(v); e != nil {
		return result, e
	}
	return result, nil
}

// ImportBundle is a bounded offline bootstrap copy. Source names are fixed by
// signed asset metadata; it never executes imported files or touches legacy .new.
func ImportBundle(root, source string, v VerifiedManifest, d Device) error {
	p, e := Plan(v, d)
	if e != nil {
		return e
	}
	for _, a := range p {
		if Cached(root, a) {
			continue
		}
		fd, e := syscall.Open(filepath.Join(source, a.artifact.Asset), syscall.O_RDONLY|syscall.O_NOFOLLOW, 0)
		if e != nil {
			return e
		}
		f := os.NewFile(uintptr(fd), a.artifact.Asset)
		st, e := f.Stat()
		if e != nil || !st.Mode().IsRegular() {
			f.Close()
			return errors.New("bootstrap input not regular file")
		}
		_, e = Stage(root, a, f)
		f.Close()
		if e != nil {
			return e
		}
	}
	return nil
}
