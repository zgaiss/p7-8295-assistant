package update

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"io"
	"os"
	"path/filepath"
	"syscall"
)

// RecoveryGeneration is a verified, complete, locally materialized known-good
// generation. It is a selection result, NOT an executable startup command.
type RecoveryGeneration struct {
	Directory  string
	Sequence   uint64
	Components map[string]string
}

// MaterializeRecovery first revokes an unconfirmed trial, then copies all known-
// good components into one immutable-by-convention directory. It never changes
// the vehicle's executable, boot path, active symlink or permissions to execute.
// destination must be a private, stable parent under trusted host control.
func (s *Store) MaterializeRecovery(destination string) (RecoveryGeneration, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	var out RecoveryGeneration
	j, e := s.read()
	if e != nil {
		return out, e
	}
	if j.Phase == "pending" {
		j.Candidate = nil
		j.Phase = "good"
		j.Nonce = ""
		j.Boot = ""
		if e = s.write(j); e != nil {
			return out, e
		}
	}
	v, e := s.decode(j.Good)
	if e != nil {
		return out, e
	}
	st, e := os.Lstat(destination)
	if e != nil || !st.IsDir() || st.Mode().Perm()&0077 != 0 {
		return out, errors.New("private generation parent required")
	}
	h := sha256.Sum256(v.raw)
	name := hex.EncodeToString(h[:])
	dest := filepath.Join(destination, name)
	out = RecoveryGeneration{Directory: dest, Sequence: v.manifest.Sequence, Components: map[string]string{}}
	for _, a := range v.manifest.Artifacts {
		out.Components[a.Component] = filepath.Join(dest, policies[a.Component].Filename)
	}
	if _, e = os.Lstat(dest); e == nil {
		if e = checkGeneration(dest, v); e != nil {
			return RecoveryGeneration{}, e
		}
		// A prior process may have died after rename but before parent fsync.
		// Presence plus valid bytes is not a durable publication guarantee.
		if e = syncDirectory(destination); e != nil {
			return RecoveryGeneration{}, e
		}
		if e = s.checkpoint("generation-parent-sync"); e != nil {
			return RecoveryGeneration{}, e
		}
		return out, nil
	} else if !os.IsNotExist(e) {
		return RecoveryGeneration{}, e
	}
	temp, e := os.MkdirTemp(destination, "generation-*.part")
	if e != nil {
		return RecoveryGeneration{}, e
	}
	defer os.RemoveAll(temp)
	for _, a := range v.manifest.Artifacts {
		source := filepath.Join(s.root, a.SHA256+".verified")
		fd, e := syscall.Open(source, syscall.O_RDONLY|syscall.O_NOFOLLOW, 0)
		if e != nil {
			return RecoveryGeneration{}, e
		}
		in := os.NewFile(uintptr(fd), source)
		e = copyGenerationFile(temp, policies[a.Component].Filename, in, a)
		in.Close()
		if e != nil {
			return RecoveryGeneration{}, e
		}
		if e = s.checkpoint("generation-file"); e != nil {
			return RecoveryGeneration{}, e
		}
	}
	for name, data := range map[string][]byte{"manifest.json": v.raw, "manifest.sig": v.signature} {
		f, e := os.OpenFile(filepath.Join(temp, name), os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0400)
		if e != nil {
			return RecoveryGeneration{}, e
		}
		_, e = f.Write(data)
		if e == nil {
			e = f.Sync()
		}
		ce := f.Close()
		if e == nil {
			e = ce
		}
		if e != nil {
			return RecoveryGeneration{}, e
		}
	}
	if e = checkGeneration(temp, v); e != nil {
		return RecoveryGeneration{}, e
	}
	if e = syncDirectory(temp); e != nil {
		return RecoveryGeneration{}, e
	}
	if e = s.checkpoint("generation-dir-sync"); e != nil {
		return RecoveryGeneration{}, e
	}
	if e = os.Rename(temp, dest); e != nil {
		return RecoveryGeneration{}, e
	}
	if e = s.checkpoint("generation-rename"); e != nil {
		return RecoveryGeneration{}, e
	}
	if e = syncDirectory(destination); e != nil {
		return RecoveryGeneration{}, e
	}
	if e = s.checkpoint("generation-parent-sync"); e != nil {
		return RecoveryGeneration{}, e
	}
	return out, nil
}
func syncDirectory(path string) error {
	d, e := os.Open(path)
	if e != nil {
		return e
	}
	defer d.Close()
	return d.Sync()
}
func copyGenerationFile(root, name string, in io.Reader, a Artifact) error {
	f, e := os.OpenFile(filepath.Join(root, name), os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0400)
	if e != nil {
		return e
	}
	defer f.Close()
	h := sha256.New()
	n, e := io.Copy(io.MultiWriter(f, h), io.LimitReader(in, a.Size+1))
	if e != nil {
		return e
	}
	if n != a.Size || hex.EncodeToString(h.Sum(nil)) != a.SHA256 {
		return errors.New("generation copy hash mismatch")
	}
	if e = f.Sync(); e != nil {
		return e
	}
	return f.Close()
}
func checkGeneration(root string, v VerifiedManifest) error {
	st, e := os.Lstat(root)
	if e != nil || !st.IsDir() || st.Mode().Perm()&0077 != 0 {
		return errors.New("generation is not private directory")
	}
	entries, e := os.ReadDir(root)
	if e != nil || len(entries) != len(v.manifest.Artifacts)+2 {
		return errors.New("generation incomplete or unexpected files")
	}
	for _, a := range v.manifest.Artifacts {
		fd, e := syscall.Open(filepath.Join(root, policies[a.Component].Filename), syscall.O_RDONLY|syscall.O_NOFOLLOW, 0)
		if e != nil {
			return e
		}
		f := os.NewFile(uintptr(fd), a.Component)
		st, e := f.Stat()
		if e != nil || !st.Mode().IsRegular() || st.Size() != a.Size {
			f.Close()
			return errors.New("generation artifact type/size")
		}
		h := sha256.New()
		n, e := io.Copy(h, io.LimitReader(f, a.Size+1))
		f.Close()
		if e != nil || n != a.Size || hex.EncodeToString(h.Sum(nil)) != a.SHA256 {
			return errors.New("generation artifact hash")
		}
	}
	for name, want := range map[string][]byte{"manifest.json": v.raw, "manifest.sig": v.signature} {
		fd, e := syscall.Open(filepath.Join(root, name), syscall.O_RDONLY|syscall.O_NOFOLLOW, 0)
		if e != nil {
			return e
		}
		f := os.NewFile(uintptr(fd), name)
		st, e := f.Stat()
		if e != nil || !st.Mode().IsRegular() {
			f.Close()
			return errors.New("generation metadata type")
		}
		got, e := io.ReadAll(io.LimitReader(f, int64(len(want))+1))
		f.Close()
		if e != nil || !bytes.Equal(got, want) {
			return errors.New("generation metadata mismatch")
		}
	}
	return nil
}
