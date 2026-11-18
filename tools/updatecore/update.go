// Package update provides a default-off component update core. It never installs,
// executes, or connects to a vehicle. Network download requires explicit opt-in.
package update

import (
	"bytes"
	"crypto"
	"crypto/rsa"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"regexp"
)

const Repository = "zgaiss/p7-8295-assistant"
const MaxManifest = 64 * 1024
const MaxArtifact = 64 * 1024 * 1024

var tagPattern = regexp.MustCompile(`^v[0-9]+\.[0-9]+\.[0-9]+$`)
var hashPattern = regexp.MustCompile(`^[a-f0-9]{64}$`)
var assetPattern = regexp.MustCompile(`^[a-zA-Z0-9][a-zA-Z0-9._-]{0,100}$`)

type Manifest struct {
	Schema        int        `json:"schema"`
	Repository    string     `json:"repository"`
	Tag           string     `json:"tag"`
	Sequence      uint64     `json:"sequence"`
	MinAPK        int        `json:"minApkCode"`
	Protocol      int        `json:"protocol"`
	InstrumentABI string     `json:"instrumentAbi"`
	Artifacts     []Artifact `json:"artifacts"`
}
type Artifact struct {
	Component string `json:"component"`
	Version   uint64 `json:"version"`
	Asset     string `json:"asset"`
	URL       string `json:"url"`
	Size      int64  `json:"size"`
	SHA256    string `json:"sha256"`
}
type Installed struct {
	Version uint64
	SHA256  string
}
type Device struct {
	Target        string
	APKCode       int
	Protocol      int
	InstrumentABI string
	LastSequence  uint64
	Installed     map[string]Installed
}

// VerifiedManifest has no public constructor or mutable manifest access.
// Its zero value is invalid. Only Verify grants this capability.
type VerifiedManifest struct {
	manifest       *Manifest
	raw, signature []byte
}
type VerifiedArtifact struct {
	artifact Artifact
	verified bool
}

// Metadata returns a detached value, never a mutable view of verified data.
func (a VerifiedArtifact) Metadata() Artifact { return a.artifact }

// Verify accepts only a locally pinned RSA key, never a key supplied by the
// release. Sign exact manifest bytes with SHA256/RSA; no JSON reserialization.
func Verify(raw, signature []byte, key *rsa.PublicKey) (VerifiedManifest, error) {
	var m Manifest
	if len(raw) == 0 || len(raw) > MaxManifest {
		return VerifiedManifest{}, errors.New("manifest size")
	}
	if key == nil || key.N == nil || key.N.Sign() <= 0 || key.N.BitLen() < 2048 || key.E < 3 || key.E > 2147483647 || key.E%2 == 0 {
		return VerifiedManifest{}, errors.New("pinned RSA key missing/weak")
	}
	h := sha256.Sum256(raw)
	if rsa.VerifyPKCS1v15(key, crypto.SHA256, h[:], signature) != nil {
		return VerifiedManifest{}, errors.New("manifest signature")
	}
	if err := uniqueJSON(raw); err != nil {
		return VerifiedManifest{}, err
	}
	d := json.NewDecoder(bytes.NewReader(raw))
	d.DisallowUnknownFields()
	if err := d.Decode(&m); err != nil {
		return VerifiedManifest{}, err
	}
	if m.Schema != 1 || m.Repository != Repository || !tagPattern.MatchString(m.Tag) || m.Sequence == 0 || m.MinAPK < 1 || m.Protocol != 1 || m.InstrumentABI != "linux-armv7" {
		return VerifiedManifest{}, errors.New("manifest compatibility/identity")
	}
	if len(m.Artifacts) == 0 || len(m.Artifacts) > len(policies) {
		return VerifiedManifest{}, errors.New("artifact count")
	}
	seen := map[string]bool{}
	for _, a := range m.Artifacts {
		if _, ok := policies[a.Component]; !ok || seen[a.Component] {
			return VerifiedManifest{}, errors.New("unknown/duplicate component")
		}
		seen[a.Component] = true
		if a.Version == 0 || a.Size <= 0 || a.Size > MaxArtifact || !hashPattern.MatchString(a.SHA256) || !assetPattern.MatchString(a.Asset) {
			return VerifiedManifest{}, errors.New("artifact metadata")
		}
		want := "https://github.com/" + Repository + "/releases/download/" + m.Tag + "/" + a.Asset
		if a.URL != want {
			return VerifiedManifest{}, errors.New("artifact URL not pinned release")
		}
	}
	return VerifiedManifest{manifest: &m, raw: append([]byte(nil), raw...), signature: append([]byte(nil), signature...)}, nil
}

// Plan is fail-closed. An APK prerequisite is installed and acknowledged in a
// separate transaction, not silently bundled with an instrument activation.
// No version-only or size-only shortcut: matching content hashes skip payloads.
func Plan(v VerifiedManifest, d Device) ([]VerifiedArtifact, error) {
	if v.manifest == nil {
		return nil, errors.New("unverified manifest")
	}
	m := v.manifest
	if m.Sequence <= d.LastSequence {
		return nil, errors.New("replayed or downgraded release")
	}
	if d.APKCode < m.MinAPK || d.Protocol != m.Protocol || d.InstrumentABI != m.InstrumentABI {
		return nil, errors.New("device prerequisites unmet")
	}
	var out []VerifiedArtifact
	target := ""
	for _, a := range m.Artifacts {
		policy := policies[a.Component]
		if policy.Class == BootstrapOnly {
			return nil, errors.New("bootstrap component requires separate installer")
		}
		if target != "" && target != policy.Target {
			return nil, errors.New("cross-device bundle cannot be atomic")
		}
		target = policy.Target
	}
	if target != d.Target || target != "cluster-linux-armv7" {
		return nil, errors.New("receiver target mismatch or unimplemented target compatibility")
	}
	for _, a := range m.Artifacts {
		old := d.Installed[a.Component]
		if a.Version < old.Version {
			return nil, errors.New("component downgrade")
		}
		if a.Version == old.Version && old.SHA256 != "" && old.SHA256 != a.SHA256 {
			return nil, errors.New("version reused with different content")
		}
		if old.SHA256 != a.SHA256 {
			out = append(out, VerifiedArtifact{artifact: a, verified: true})
		}
	}
	return out, nil
}

// Stage verifies a complete bounded stream before returning a cache path.
// root must be a private app-owned directory. The output is NOT an activation
// path; a future device updater must reverify after FTP and own rollback.
func Stage(root string, v VerifiedArtifact, r io.Reader) (string, error) {
	if !v.verified {
		return "", errors.New("unverified artifact")
	}
	a := v.artifact
	if a.Size <= 0 || a.Size > MaxArtifact || !hashPattern.MatchString(a.SHA256) {
		return "", errors.New("invalid stage metadata")
	}
	st, err := os.Lstat(root)
	if err != nil || !st.IsDir() || st.Mode()&os.ModeSymlink != 0 || st.Mode().Perm()&0022 != 0 {
		return "", errors.New("stage directory must exist, not be a symlink, and not be group/world writable")
	}
	f, err := os.CreateTemp(root, "download-*.part")
	if err != nil {
		return "", err
	}
	name := f.Name()
	defer os.Remove(name)
	defer f.Close()
	h := sha256.New()
	n, err := io.Copy(io.MultiWriter(f, h), io.LimitReader(r, a.Size+1))
	if err != nil {
		return "", err
	}
	if n != a.Size || hex.EncodeToString(h.Sum(nil)) != a.SHA256 {
		return "", errors.New("artifact length/hash mismatch")
	}
	if err = f.Sync(); err != nil {
		return "", err
	}
	if err = f.Close(); err != nil {
		return "", err
	}
	dest := filepath.Join(root, a.SHA256+".verified")
	// Never overwrite an existing entry, including symlinks. Concurrent writers
	// can safely race through link(2); only one wins, no unverified partial exists.
	if err = os.Link(name, dest); err != nil {
		return "", err
	}
	dir, err := os.Open(root)
	if err != nil {
		return "", err
	}
	defer dir.Close()
	if err = dir.Sync(); err != nil {
		return "", err
	}
	return dest, nil
}

func uniqueJSON(raw []byte) error {
	d := json.NewDecoder(bytes.NewReader(raw))
	var walk func() error
	walk = func() error {
		tok, err := d.Token()
		if err != nil {
			return err
		}
		delim, ok := tok.(json.Delim)
		if !ok {
			return nil
		}
		switch delim {
		case '{':
			seen := map[string]bool{}
			for d.More() {
				k, e := d.Token()
				if e != nil {
					return e
				}
				s, ok := k.(string)
				if !ok || seen[s] {
					return errors.New("duplicate JSON key")
				}
				allowed := map[string]bool{"schema": true, "repository": true, "tag": true, "sequence": true, "minApkCode": true, "protocol": true, "instrumentAbi": true, "artifacts": true, "component": true, "version": true, "asset": true, "url": true, "size": true, "sha256": true}
				if !allowed[s] {
					return errors.New("unknown or incorrectly cased JSON key")
				}
				seen[s] = true
				if e = walk(); e != nil {
					return e
				}
			}
		case '[':
			for d.More() {
				if err := walk(); err != nil {
					return err
				}
			}
		default:
			return fmt.Errorf("unexpected delimiter %v", delim)
		}
		_, err = d.Token()
		return err
	}
	if err := walk(); err != nil {
		return err
	}
	if _, err := d.Token(); err != io.EOF {
		return errors.New("trailing JSON")
	}
	return nil
}
