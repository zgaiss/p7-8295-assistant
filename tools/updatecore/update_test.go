package update

import (
	"bytes"
	"crypto"
	"crypto/rand"
	"crypto/rsa"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"os"
	"path/filepath"
	"testing"
)

func artifact(data []byte) Artifact {
	h := sha256.Sum256(data)
	return Artifact{Component: "instrument", Version: 2, Asset: "p7assist", URL: "https://github.com/" + Repository + "/releases/download/v2.12.4/p7assist", Size: int64(len(data)), SHA256: hex.EncodeToString(h[:])}
}
func sample() Manifest {
	return Manifest{Schema: 1, Repository: Repository, Tag: "v2.12.4", Sequence: 2, MinAPK: 101, Protocol: 1, InstrumentABI: "linux-armv7", Artifacts: []Artifact{artifact([]byte("test fixture, not executable"))}}
}
func signed(t *testing.T, k *rsa.PrivateKey, raw []byte) []byte {
	t.Helper()
	h := sha256.Sum256(raw)
	sig, e := rsa.SignPKCS1v15(rand.Reader, k, crypto.SHA256, h[:])
	if e != nil {
		t.Fatal(e)
	}
	return sig
}
func TestManifestSignatureAndConstraints(t *testing.T) {
	k, e := rsa.GenerateKey(rand.Reader, 2048)
	if e != nil {
		t.Fatal(e)
	}
	m := sample()
	raw, _ := json.Marshal(m)
	sig := signed(t, k, raw)
	if _, e := Verify(raw, sig, &k.PublicKey); e != nil {
		t.Fatal(e)
	}
	altered := append([]byte(nil), raw...)
	altered[len(altered)-2] ^= 1
	if _, e := Verify(altered, sig, &k.PublicKey); e == nil {
		t.Fatal("tamper accepted")
	}
	if _, e := Verify(raw, sig, nil); e == nil {
		t.Fatal("missing pin accepted")
	}
	for _, tc := range []struct {
		name string
		edit func(*Manifest)
	}{
		{"foreign repository", func(m *Manifest) { m.Repository = "other/repo" }},
		{"http", func(m *Manifest) { m.Artifacts[0].URL = "http://github.com/" }},
		{"path traversal", func(m *Manifest) { m.Artifacts[0].Asset = "../p7assist" }},
		{"arbitrary destination", func(m *Manifest) { m.Artifacts[0].Component = "../../system/bin/x" }},
		{"oversize", func(m *Manifest) { m.Artifacts[0].Size = MaxArtifact + 1 }},
		{"duplicate component", func(m *Manifest) { m.Artifacts = append(m.Artifacts, m.Artifacts[0]) }},
		{"wrong architecture", func(m *Manifest) { m.InstrumentABI = "arm64" }},
		{"unsupported protocol", func(m *Manifest) { m.Protocol = 7 }},
	} {
		t.Run(tc.name, func(t *testing.T) {
			m := sample()
			tc.edit(&m)
			b, _ := json.Marshal(m)
			if _, e := Verify(b, signed(t, k, b), &k.PublicKey); e == nil {
				t.Fatal("accepted")
			}
		})
	}
	for _, b := range [][]byte{[]byte(`{"schema":1,"schema":1}`), []byte(`{"unknown":1}`), append(append([]byte(nil), raw...), []byte(` {}`)...)} {
		if _, e := Verify(b, signed(t, k, b), &k.PublicKey); e == nil {
			t.Fatal("ambiguous JSON accepted")
		}
	}
}
func TestComponentIncrementalPlan(t *testing.T) {
	m := sample()
	d := Device{Target: "cluster-linux-armv7", APKCode: 101, Protocol: 1, InstrumentABI: "linux-armv7", LastSequence: 1, Installed: map[string]Installed{}}
	p, e := Plan(verified(t, m), d)
	if e != nil || len(p) != 1 {
		t.Fatalf("%v %v", p, e)
	}
	d.Installed["instrument"] = Installed{Version: 1, SHA256: m.Artifacts[0].SHA256}
	p, e = Plan(verified(t, m), d)
	if e != nil || len(p) != 0 {
		t.Fatal("identical payload downloaded")
	}
	d.Installed["instrument"] = Installed{Version: 2, SHA256: "different"}
	if _, e = Plan(verified(t, m), d); e == nil {
		t.Fatal("version collision accepted")
	}
	d.Installed["instrument"] = Installed{Version: 3}
	if _, e = Plan(verified(t, m), d); e == nil {
		t.Fatal("component downgrade accepted")
	}
	d.Installed = nil
	d.LastSequence = 2
	if _, e = Plan(verified(t, m), d); e == nil {
		t.Fatal("replay accepted")
	}
	d.LastSequence = 1
	d.APKCode = 100
	if _, e = Plan(verified(t, m), d); e == nil {
		t.Fatal("missing bootstrap APK accepted")
	}
}

type brokenReader struct{}

func (brokenReader) Read([]byte) (int, error) { return 0, errors.New("network interrupted") }
func TestStageFailureAtomicity(t *testing.T) {
	data := []byte("candidate bytes")
	a := planned(t, artifact(data))
	for _, tc := range []struct {
		name string
		r    io.Reader
	}{
		{"truncated", bytes.NewReader(data[:3])}, {"oversized", bytes.NewReader(append(append([]byte(nil), data...), 1))},
		{"same size corruption", bytes.NewReader(bytes.Repeat([]byte{'x'}, len(data)))}, {"interrupted", brokenReader{}},
	} {
		t.Run(tc.name, func(t *testing.T) {
			root := t.TempDir()
			if _, e := Stage(root, a, tc.r); e == nil {
				t.Fatal("accepted")
			}
			entries, _ := os.ReadDir(root)
			if len(entries) != 0 {
				t.Fatal("partial artifact left behind")
			}
		})
	}
	root := t.TempDir()
	path, e := Stage(root, a, bytes.NewReader(data))
	if e != nil {
		t.Fatal(e)
	}
	got, _ := os.ReadFile(path)
	if !bytes.Equal(got, data) {
		t.Fatal("wrong bytes")
	}
	if _, e = Stage(root, a, bytes.NewReader(data)); e == nil {
		t.Fatal("overwrote existing cache")
	}
	symlinkRoot := t.TempDir()
	target := filepath.Join(t.TempDir(), "original")
	os.WriteFile(target, []byte("keep"), 0600)
	if e = os.Symlink(target, filepath.Join(symlinkRoot, a.Metadata().SHA256+".verified")); e != nil {
		t.Fatal(e)
	}
	if _, e = Stage(symlinkRoot, a, bytes.NewReader(data)); e == nil {
		t.Fatal("followed symlink")
	}
	got, _ = os.ReadFile(target)
	if string(got) != "keep" {
		t.Fatal("target overwritten")
	}
}

func TestMultiComponentIncrementalPlan(t *testing.T) {
	m := sample()
	font := artifact([]byte("unchanged font"))
	font.Component = "font"
	font.Asset = "font.ttf"
	font.URL = "https://github.com/" + Repository + "/releases/download/" + m.Tag + "/" + font.Asset
	m.Artifacts = append(m.Artifacts, font)
	d := Device{Target: "cluster-linux-armv7", APKCode: 101, Protocol: 1, InstrumentABI: "linux-armv7", LastSequence: 1, Installed: map[string]Installed{"font": {Version: font.Version, SHA256: font.SHA256}}}
	p, e := Plan(verified(t, m), d)
	if e != nil || len(p) != 1 || p[0].Metadata().Component != "instrument" {
		t.Fatalf("unexpected plan: %+v %v", p, e)
	}
}

func verified(t *testing.T, m Manifest) VerifiedManifest {
	t.Helper()
	k, e := rsa.GenerateKey(rand.Reader, 2048)
	if e != nil {
		t.Fatal(e)
	}
	b, e := json.Marshal(m)
	if e != nil {
		t.Fatal(e)
	}
	v, e := Verify(b, signed(t, k, b), &k.PublicKey)
	if e != nil {
		t.Fatal(e)
	}
	return v
}
func planned(t *testing.T, a Artifact) VerifiedArtifact {
	t.Helper()
	m := sample()
	m.Artifacts = []Artifact{a}
	p, e := Plan(verified(t, m), Device{Target: "cluster-linux-armv7", APKCode: 101, Protocol: 1, InstrumentABI: "linux-armv7"})
	if e != nil || len(p) != 1 {
		t.Fatalf("plan %v", e)
	}
	return p[0]
}
func TestVerifiedCapabilityAndStrictKeys(t *testing.T) {
	if _, e := Plan(VerifiedManifest{}, Device{}); e == nil {
		t.Fatal("zero manifest accepted")
	}
	root := t.TempDir()
	if _, e := Stage(root, VerifiedArtifact{}, bytes.NewReader(nil)); e == nil {
		t.Fatal("zero artifact accepted")
	}
	data := []byte("candidate")
	a := planned(t, artifact(data))
	detached := a.Metadata()
	detached.Size = 1
	detached.SHA256 = "bad"
	if _, e := Stage(root, a, bytes.NewReader(data)); e != nil {
		t.Fatal(e)
	}
	k, e := rsa.GenerateKey(rand.Reader, 2048)
	if e != nil {
		t.Fatal(e)
	}
	raw, _ := json.Marshal(sample())
	sig := signed(t, k, raw)
	for _, pin := range []*rsa.PublicKey{nil, {}, {N: k.N, E: 0}, {N: k.N, E: 2}} {
		if _, e := Verify(raw, sig, pin); e == nil {
			t.Fatal("invalid pin accepted")
		}
	}
	for _, pair := range [][2]string{{"schema", "Schema"}, {"sha256", "SHA256"}, {"minApkCode", "minapkcode"}} {
		b := bytes.Replace(raw, []byte("\""+pair[0]+"\""), []byte("\""+pair[1]+"\""), 1)
		if _, e := Verify(b, signed(t, k, b), &k.PublicKey); e == nil {
			t.Fatal("case alias accepted")
		}
	}
}

func TestStageRejectsWritableDirectory(t *testing.T) {
	root := t.TempDir()
	if e := os.Chmod(root, 0777); e != nil {
		t.Fatal(e)
	}
	data := []byte("candidate")
	a := planned(t, artifact(data))
	if _, e := Stage(root, a, bytes.NewReader(data)); e == nil {
		t.Fatal("untrusted directory accepted")
	}
}

func FuzzUniqueJSON(f *testing.F) {
	for _, seed := range []string{"{}", "{\"schema\":1,\"schema\":2}", "{\"Schema\":1}", "{\"artifacts\":[{\"component\":\"instrument\"}]}", "[[[[]]]]", "{} {}"} {
		f.Add([]byte(seed))
	}
	f.Fuzz(func(t *testing.T, raw []byte) {
		if len(raw) > MaxManifest {
			t.Skip()
		}
		_ = uniqueJSON(raw)
	})
}
