package update

import (
	"bytes"
	"context"
	"crypto/rand"
	"crypto/rsa"
	"encoding/json"
	"net/http"
	"os"
	"path/filepath"
	"testing"
)

func TestEndToEndBenchDownloadReceiveTrialAndRecovery(t *testing.T) {
	root, k, s, next := setupMultiStore(t)
	sender := t.TempDir()
	os.Chmod(sender, 0700)
	requests := 0
	downloader := &Downloader{enabled: true, transport: offlineTransport(func(r *http.Request) (*http.Response, error) {
		requests++
		// Fixture assets share URLs in helper; identify signed sizes isn't enough.
		// Distinct URLs are built below for each component.
		return response(200, []byte(filepath.Base(r.URL.Path)+"2"), ""), nil
	})}
	// Give each component a distinct signed URL and payload for actual HTTP route.
	m := *next.manifest
	m.Artifacts = append([]Artifact(nil), m.Artifacts...)
	for i := range m.Artifacts {
		m.Artifacts[i].Asset = m.Artifacts[i].Component
		m.Artifacts[i].URL = "https://github.com/" + Repository + "/releases/download/" + m.Tag + "/" + m.Artifacts[i].Asset
	}
	next = signManifest(t, k, m)
	// Remove pre-seeded next generation from fixture receiver: only good remains.
	for _, a := range next.manifest.Artifacts {
		os.Remove(filepath.Join(root, a.SHA256+".verified"))
	}
	result, e := StageRelease(context.Background(), downloader, sender, s, next, compatible())
	if e != nil || result.Downloaded != 3 || result.Transferred != 3 || requests != 3 {
		t.Fatal(result, e, requests)
	}
	result, e = StageRelease(context.Background(), NewDownloader(false), sender, s, next, compatible())
	if e != nil || result.ReceiverCached != 3 || requests != 3 {
		t.Fatal(result, e)
	}
	trial, e := s.SimulateTrial(next, compatible(), "bench-boot")
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
		t.Fatal("stale confirmation after restart")
	}
	state, e := s.Recover()
	if e != nil {
		t.Fatal(e)
	}
	for _, a := range state.Components {
		if a.Version != 1 {
			t.Fatal("mixed recovery", state)
		}
	}
	if _, e = StageRelease(context.Background(), downloader, sender, s, next, compatible()); e == nil {
		t.Fatal("replay downloaded")
	}
	if requests != 3 {
		t.Fatal("network before replay rejection")
	}
}
func TestReceiverRejectsHostCacheTamper(t *testing.T) {
	root, _, s, next := setupStore(t)
	defer s.Close()
	sender := t.TempDir()
	os.Chmod(sender, 0700)
	a := VerifiedArtifact{artifact: next.manifest.Artifacts[0], verified: true}
	os.Remove(filepath.Join(root, a.artifact.SHA256+".verified"))
	os.WriteFile(filepath.Join(sender, a.artifact.SHA256+".verified"), []byte("BAD"), 0600)
	if Cached(sender, a) {
		t.Fatal("tampered cache accepted")
	}
	if _, e := StageRelease(context.Background(), NewDownloader(false), sender, s, next, compatible()); e == nil {
		t.Fatal("tampered transfer")
	}
	state, e := s.Recover()
	if e != nil || state.Components["instrument"].Version != 1 {
		t.Fatal(state, e)
	}
}
func TestOfflineImportRejectsSymlinkAndCorrupt(t *testing.T) {
	_, _, s, next := setupStore(t)
	defer s.Close()
	source := t.TempDir()
	dest := t.TempDir()
	asset := next.manifest.Artifacts[0].Asset
	target := filepath.Join(t.TempDir(), "target")
	os.WriteFile(target, []byte("new"), 0600)
	os.Symlink(target, filepath.Join(source, asset))
	if e := ImportBundle(dest, source, next, compatible()); e == nil {
		t.Fatal("symlink input")
	}
	os.Remove(filepath.Join(source, asset))
	os.WriteFile(filepath.Join(source, asset), []byte("BAD"), 0600)
	if e := ImportBundle(dest, source, next, compatible()); e == nil {
		t.Fatal("corrupt input")
	}
	os.WriteFile(filepath.Join(source, asset), []byte("new"), 0600)
	if e := ImportBundle(dest, source, next, compatible()); e != nil {
		t.Fatal(e)
	}
	got, _ := os.ReadFile(filepath.Join(dest, next.manifest.Artifacts[0].SHA256+".verified"))
	if !bytes.Equal(got, []byte("new")) {
		t.Fatal(got)
	}
}

func signManifest(t *testing.T, k *rsa.PrivateKey, m Manifest) VerifiedManifest {
	t.Helper()
	raw, _ := json.Marshal(m)
	v, e := Verify(raw, signed(t, k, raw), &k.PublicKey)
	if e != nil {
		t.Fatal(e)
	}
	return v
}

func TestGitHubDiscoveryThroughIncrementalReceiverCommit(t *testing.T) {
	root, k, s, _ := setupMultiStore(t)
	j, e := s.read()
	if e != nil {
		t.Fatal(e)
	}
	old, e := s.decode(j.Good)
	if e != nil {
		t.Fatal(e)
	}
	m := *old.manifest
	m.Sequence = 2
	m.Artifacts = append([]Artifact(nil), old.manifest.Artifacts...)
	data := []byte("instrument2")
	a := artifact(data)
	a.Component = "instrument"
	a.Version = 2
	m.Artifacts[0] = a
	v := signManifest(t, k, m)
	os.Remove(filepath.Join(root, a.SHA256+".verified"))
	release, _ := json.Marshal(releaseFixture(m.Tag, v.raw, v.signature))
	requests := 0
	payloads := 0
	d := &Downloader{enabled: true, transport: offlineTransport(func(r *http.Request) (*http.Response, error) {
		requests++
		switch r.URL.String() {
		case releaseAPI:
			return response(200, release, ""), nil
		case "https://github.com/" + Repository + "/releases/download/" + m.Tag + "/manifest.json":
			return response(200, v.raw, ""), nil
		case "https://github.com/" + Repository + "/releases/download/" + m.Tag + "/manifest.sig":
			return response(200, v.signature, ""), nil
		case a.URL:
			payloads++
			return response(200, data, ""), nil
		default:
			t.Fatal("unexpected asset fetch", r.URL)
			return nil, nil
		}
	})}
	discovered, e := d.Discover(context.Background(), &k.PublicKey)
	if e != nil {
		t.Fatal(e)
	}
	sender := t.TempDir()
	os.Chmod(sender, 0700)
	result, e := StageRelease(context.Background(), d, sender, s, discovered, compatible())
	if e != nil || result.Downloaded != 1 || result.Transferred != 1 || payloads != 1 || requests != 4 {
		t.Fatal(result, e, requests)
	}
	trial, e := s.SimulateTrial(discovered, compatible(), "bench-success")
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
	if e != nil {
		t.Fatal(e)
	}
	if state.Components["instrument"].Version != 2 || state.Components["player"].Version != 1 || state.Components["charge-pack"].Version != 1 {
		t.Fatal(state)
	}
}

func TestForeignPinRejectedBeforeDownloadOrCacheWrite(t *testing.T) {
	root, _, s, _ := setupStore(t)
	defer s.Close()
	foreign, e := rsa.GenerateKey(rand.Reader, 2048)
	if e != nil {
		t.Fatal(e)
	}
	m := sample()
	m.Sequence = 3
	m.Artifacts[0] = artifact([]byte("foreign candidate"))
	m.Artifacts[0].Version = 3
	v := signManifest(t, foreign, m)
	sender := t.TempDir()
	before, _ := os.ReadDir(root)
	requests := 0
	d := &Downloader{enabled: true, transport: offlineTransport(func(*http.Request) (*http.Response, error) {
		requests++
		return response(200, []byte("foreign candidate"), ""), nil
	})}
	if _, e := StageRelease(context.Background(), d, sender, s, v, compatible()); e == nil {
		t.Fatal("foreign root accepted")
	}
	after, _ := os.ReadDir(root)
	host, _ := os.ReadDir(sender)
	if requests != 0 || len(host) != 0 || len(after) != len(before) {
		t.Fatal("side effects before receiver pin validation", requests, len(host))
	}
}
