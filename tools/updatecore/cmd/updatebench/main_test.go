package main

import (
	"bytes"
	"crypto"
	"crypto/rand"
	"crypto/rsa"
	"crypto/sha256"
	"crypto/x509"
	"encoding/hex"
	"encoding/json"
	"encoding/pem"
	"os"
	update "p7-update-core"
	"path/filepath"
	"strings"
	"testing"
)

func TestCLIOffCarLifecycle(t *testing.T) {
	temp := t.TempDir()
	root := filepath.Join(temp, "bench")
	key, e := rsa.GenerateKey(rand.Reader, 2048)
	if e != nil {
		t.Fatal(e)
	}
	der, _ := x509.MarshalPKIXPublicKey(&key.PublicKey)
	pin := filepath.Join(temp, "public.pem")
	os.WriteFile(pin, pem.EncodeToMemory(&pem.Block{Type: "PUBLIC KEY", Bytes: der}), 0600)
	fixture := func(seq uint64) (string, string, string) {
		dir := filepath.Join(temp, string(rune('0'+seq)))
		os.Mkdir(dir, 0700)
		data := []byte("not executable fixture " + string(rune('0'+seq)))
		sum := sha256.Sum256(data)
		m := update.Manifest{Schema: 1, Repository: update.Repository, Tag: "v2.12.4", Sequence: seq, MinAPK: 101, Protocol: 1, InstrumentABI: "linux-armv7", Artifacts: []update.Artifact{{Component: "instrument", Version: seq, Asset: "p7assist", URL: "https://github.com/" + update.Repository + "/releases/download/v2.12.4/p7assist", Size: int64(len(data)), SHA256: hex.EncodeToString(sum[:])}}}
		raw, _ := json.Marshal(m)
		h := sha256.Sum256(raw)
		sig, e := rsa.SignPKCS1v15(rand.Reader, key, crypto.SHA256, h[:])
		if e != nil {
			t.Fatal(e)
		}
		manifest, signature := filepath.Join(dir, "manifest.json"), filepath.Join(dir, "manifest.sig")
		os.WriteFile(manifest, raw, 0600)
		os.WriteFile(signature, sig, 0600)
		os.WriteFile(filepath.Join(dir, "p7assist"), data, 0600)
		return manifest, signature, dir
	}
	call := func(args ...string) string {
		t.Helper()
		var out bytes.Buffer
		if e := run(args, &out); e != nil {
			t.Fatal(args, e)
		}
		return out.String()
	}
	m1, s1, a1 := fixture(1)
	call("init", "--root", root, "--pin", pin, "--manifest", m1, "--signature", s1, "--assets", a1, "--apk-code", "101")
	if e := run([]string{"init", "--root", root, "--pin", pin, "--manifest", m1, "--signature", s1, "--assets", a1, "--apk-code", "101"}, &bytes.Buffer{}); e == nil {
		t.Fatal("reinitialized existing bench")
	}
	m2, s2, a2 := fixture(2)
	out := call("stage", "--root", root, "--manifest", m2, "--signature", s2, "--assets", a2, "--apk-code", "101")
	if !strings.Contains(out, "\"Transferred\":1") {
		t.Fatal(out)
	}
	call("simulate-cycle", "--root", root, "--manifest", m2, "--signature", s2, "--apk-code", "101", "--boot", "b1")
	out = call("status", "--root", root)
	if !strings.Contains(out, "\"version\":1") || !strings.Contains(out, "\"SequenceFloor\":2") {
		t.Fatal("did not rollback", out)
	}
	m3, s3, a3 := fixture(3)
	call("stage", "--root", root, "--manifest", m3, "--signature", s3, "--assets", a3, "--apk-code", "101")
	call("simulate-cycle", "--root", root, "--manifest", m3, "--signature", s3, "--apk-code", "101", "--boot", "b2", "--confirm-lab")
	out = call("status", "--root", root)
	if !strings.Contains(out, "\"version\":3") {
		t.Fatal(out)
	}
	out = call("recover-generation", "--root", root)
	if !strings.Contains(out, "\"Sequence\":3") {
		t.Fatal(out)
	}

	if e := run([]string{"stage-latest", "--root", root, "--apk-code", "101"}, &bytes.Buffer{}); e == nil {
		t.Fatal("network enabled by default")
	}
}
func TestCLIRejectsPrivatePin(t *testing.T) {
	k, _ := rsa.GenerateKey(rand.Reader, 2048)
	raw := pem.EncodeToMemory(&pem.Block{Type: "RSA PRIVATE KEY", Bytes: x509.MarshalPKCS1PrivateKey(k)})
	if _, e := publicPin(raw); e == nil {
		t.Fatal("private signing material accepted")
	}
}
