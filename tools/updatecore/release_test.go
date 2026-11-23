package update

import (
	"bytes"
	"context"
	"crypto/rand"
	"crypto/rsa"
	"encoding/json"
	"net/http"
	"testing"
)

func releaseFixture(tag string, manifest, sig []byte) map[string]any {
	assets := []any{}
	for name, data := range map[string][]byte{"manifest.json": manifest, "manifest.sig": sig} {
		assets = append(assets, map[string]any{"name": name, "size": len(data), "state": "uploaded", "browser_download_url": "https://github.com/" + Repository + "/releases/download/" + tag + "/" + name})
	}
	return map[string]any{"tag_name": tag, "draft": false, "prerelease": false, "assets": assets, "unknown_future_field": "ignored"}
}
func discoveryFixture(t *testing.T) (*rsa.PrivateKey, []byte, []byte, map[string]any) {
	t.Helper()
	k, e := rsa.GenerateKey(rand.Reader, 2048)
	if e != nil {
		t.Fatal(e)
	}
	raw, e := json.Marshal(sample())
	if e != nil {
		t.Fatal(e)
	}
	sig := signed(t, k, raw)
	return k, raw, sig, releaseFixture(sample().Tag, raw, sig)
}
func fixtureDownloader(release, manifest, sig []byte, calls *int) *Downloader {
	return &Downloader{enabled: true, transport: offlineTransport(func(r *http.Request) (*http.Response, error) {
		*calls++
		switch r.URL.String() {
		case releaseAPI:
			return response(200, release, ""), nil
		case "https://github.com/" + Repository + "/releases/download/v2.12.4/manifest.json":
			return response(200, manifest, ""), nil
		case "https://github.com/" + Repository + "/releases/download/v2.12.4/manifest.sig":
			return response(200, sig, ""), nil
		default:
			return response(404, nil, ""), nil
		}
	})}
}
func TestDiscoverVerifiedStableRelease(t *testing.T) {
	k, m, s, r := discoveryFixture(t)
	raw, _ := json.Marshal(r)
	calls := 0
	v, e := fixtureDownloader(raw, m, s, &calls).Discover(context.Background(), &k.PublicKey)
	if e != nil || v.manifest == nil || v.manifest.Tag != "v2.12.4" || calls != 3 {
		t.Fatalf("%v calls %d", e, calls)
	}
	// A release-provided key field does not substitute the pinned key.
	other, e := rsa.GenerateKey(rand.Reader, 2048)
	if e != nil {
		t.Fatal(e)
	}
	r["public_key"] = "untrusted"
	raw, _ = json.Marshal(r)
	if _, e := fixtureDownloader(raw, m, s, &calls).Discover(context.Background(), &other.PublicKey); e == nil {
		t.Fatal("wrong local key accepted")
	}
}
func TestDiscoverRejectReleaseMetadata(t *testing.T) {
	_, m, s, base := discoveryFixture(t)
	raw, _ := json.Marshal(base)
	for _, tc := range []struct {
		name string
		edit func(map[string]any)
	}{
		{"draft", func(r map[string]any) { r["draft"] = true }},
		{"prerelease", func(r map[string]any) { r["prerelease"] = true }},
		{"unstable tag", func(r map[string]any) { r["tag_name"] = "v2.12.4-rc1" }},
		{"missing false", func(r map[string]any) { delete(r, "draft") }},
		{"null false", func(r map[string]any) { r["draft"] = nil }},
		{"case alias", func(r map[string]any) { r["Draft"] = true }},
		{"missing assets", func(r map[string]any) { r["assets"] = []any{} }},
		{"oversize", func(r map[string]any) { r["assets"].([]any)[0].(map[string]any)["size"] = MaxManifest + 1 }},
		{"external URL", func(r map[string]any) {
			r["assets"].([]any)[0].(map[string]any)["browser_download_url"] = "https://attacker.example/key"
		}},
		{"duplicate asset", func(r map[string]any) { r["assets"] = append(r["assets"].([]any), r["assets"].([]any)[0]) }},
		{"not uploaded", func(r map[string]any) { r["assets"].([]any)[0].(map[string]any)["state"] = "new" }},
	} {
		t.Run(tc.name, func(t *testing.T) {
			var r map[string]any
			json.Unmarshal(raw, &r)
			tc.edit(r)
			b, _ := json.Marshal(r)
			if _, _, e := parseRelease(b); e == nil {
				t.Fatal("metadata accepted")
			}
		})
	}
	for _, b := range [][]byte{[]byte(`{"draft":false,"draft":true}`), append(raw, []byte(` {}`)...), bytes.Repeat([]byte{'x'}, maxReleaseJSON+1)} {
		if _, _, e := parseRelease(b); e == nil {
			t.Fatal("ambiguous/oversize accepted")
		}
	}
	// Keep fixtures used to check that generated release assets are complete.
	if _, _, e := parseRelease(m); e == nil || len(s) == 0 {
		t.Fatal("invalid fixture handling")
	}
}
func TestDiscoverSignatureAndTag(t *testing.T) {
	k, m, s, r := discoveryFixture(t)
	for _, tc := range []struct {
		name                string
		manifest, signature []byte
	}{
		{"tamper", append(append([]byte(nil), m...), byte(' ')), s},
		{"short signature", m, s[:10]},
	} {
		t.Run(tc.name, func(t *testing.T) {
			r = releaseFixture("v2.12.4", tc.manifest, tc.signature)
			b, _ := json.Marshal(r)
			calls := 0
			if _, e := fixtureDownloader(b, tc.manifest, tc.signature, &calls).Discover(context.Background(), &k.PublicKey); e == nil {
				t.Fatal("accepted")
			}
		})
	}
	other := sample()
	other.Tag = "v2.12.5"
	other.Artifacts[0].URL = "https://github.com/" + Repository + "/releases/download/v2.12.5/p7assist"
	m, _ = json.Marshal(other)
	s = signed(t, k, m)
	r = releaseFixture("v2.12.4", m, s)
	b, _ := json.Marshal(r)
	calls := 0
	if _, e := fixtureDownloader(b, m, s, &calls).Discover(context.Background(), &k.PublicKey); e == nil {
		t.Fatal("signed cross-tag manifest accepted")
	}
}
func TestDiscoveryDisabledAndBudget(t *testing.T) {
	k, _, _, _ := discoveryFixture(t)
	if _, e := NewDownloader(false).Discover(context.Background(), &k.PublicKey); e == nil {
		t.Fatal("default enabled")
	}
	for _, tc := range []struct {
		name string
		resp *http.Response
	}{
		{"API redirect", response(302, nil, "https://objects.githubusercontent.com/a")},
		{"rate limited", response(429, []byte("wait"), "")},
		{"API oversized", response(200, bytes.Repeat([]byte{'x'}, maxReleaseJSON+1), "")},
	} {
		t.Run(tc.name, func(t *testing.T) {
			calls := 0
			d := &Downloader{enabled: true, transport: offlineTransport(func(*http.Request) (*http.Response, error) { calls++; return tc.resp, nil })}
			if _, e := d.Discover(context.Background(), &k.PublicKey); e == nil || calls != 1 {
				t.Fatal("API failure not bounded")
			}
		})
	}
	body := &endlessBody{}
	d := &Downloader{enabled: true, transport: offlineTransport(func(*http.Request) (*http.Response, error) {
		return &http.Response{StatusCode: 200, Header: make(http.Header), Body: body, ContentLength: -1}, nil
	})}
	if _, e := d.Discover(context.Background(), &k.PublicKey); e == nil || body.read != maxReleaseJSON+1 || !body.closed {
		t.Fatal("unbounded metadata")
	}
}

func TestSmallMetadataRedirectPolicy(t *testing.T) {
	initial := "https://github.com/" + Repository + "/releases/download/v2.12.4/manifest.json"
	for _, target := range []string{"https://objects.githubusercontent.com/a", "https://release-assets.githubusercontent.com/a?token=signed", "http://objects.githubusercontent.com/a", "https://evil.example/a", "https://objects.githubusercontent.com:443/a"} {
		t.Run(target, func(t *testing.T) {
			calls := 0
			d := &Downloader{enabled: true, transport: offlineTransport(func(*http.Request) (*http.Response, error) {
				calls++
				if calls == 1 {
					return response(302, nil, target), nil
				}
				return response(200, []byte("{}"), ""), nil
			})}
			b, e := d.fetchSmall(context.Background(), initial, 64, false)
			good := target == "https://objects.githubusercontent.com/a" || target == "https://release-assets.githubusercontent.com/a?token=signed"
			if good && (e != nil || string(b) != "{}" || calls != 2) {
				t.Fatalf("allowed failed %v", e)
			}
			if !good && (e == nil || calls != 1) {
				t.Fatal("unsafe redirect accepted")
			}
		})
	}
	calls := 0
	d := &Downloader{enabled: true, transport: offlineTransport(func(*http.Request) (*http.Response, error) {
		calls++
		return response(302, nil, "https://objects.githubusercontent.com/a"), nil
	})}
	if _, e := d.fetchSmall(context.Background(), initial, 64, false); e == nil || calls != 4 {
		t.Fatal("redirect budget")
	}
}

func FuzzReleaseMetadata(f *testing.F) {
	for _, seed := range []string{"{}", "{\"draft\":false}", "{\"Draft\":false,\"draft\":true}", "[]", "{} {}"} {
		f.Add([]byte(seed))
	}
	f.Fuzz(func(t *testing.T, raw []byte) {
		if len(raw) > maxReleaseJSON {
			t.Skip()
		}
		_, _, _ = parseRelease(raw)
	})
}
