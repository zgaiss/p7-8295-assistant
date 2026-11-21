package update

import (
	"bytes"
	"context"
	"errors"
	"io"
	"net"
	"net/http"
	"net/url"
	"os"
	"testing"
)

type offlineTransport func(*http.Request) (*http.Response, error)

func (f offlineTransport) RoundTrip(r *http.Request) (*http.Response, error) { return f(r) }
func response(code int, body []byte, location string) *http.Response {
	h := make(http.Header)
	if location != "" {
		h.Set("Location", location)
	}
	return &http.Response{StatusCode: code, Header: h, Body: io.NopCloser(bytes.NewReader(body)), ContentLength: int64(len(body))}
}
func TestDownloaderDisabled(t *testing.T) {
	for _, d := range []*Downloader{nil, {}, NewDownloader(false)} {
		if _, e := d.Fetch(context.Background(), t.TempDir(), VerifiedArtifact{}); e == nil {
			t.Fatal("enabled by default")
		}
	}
}
func TestOfflineFetchAndRedirect(t *testing.T) {
	data := []byte("safe test bytes")
	a := planned(t, artifact(data))
	calls := 0
	d := &Downloader{enabled: true, transport: offlineTransport(func(r *http.Request) (*http.Response, error) {
		calls++
		if _, ok := r.Context().Deadline(); !ok {
			t.Fatal("no deadline")
		}
		if r.Header.Get("Accept-Encoding") != "identity" {
			t.Fatal("compression requested")
		}
		if calls == 1 {
			return response(302, nil, "https://release-assets.githubusercontent.com/asset?token=opaque"), nil
		}
		return response(200, data, ""), nil
	})}
	path, e := d.Fetch(context.Background(), t.TempDir(), a)
	if e != nil {
		t.Fatal(e)
	}
	got, e := os.ReadFile(path)
	if e != nil || !bytes.Equal(got, data) || calls != 2 {
		t.Fatalf("%v calls %d", e, calls)
	}
}
func TestFetchFailures(t *testing.T) {
	data := []byte("safe test bytes")
	a := planned(t, artifact(data))
	for _, tc := range []struct {
		name string
		fn   offlineTransport
	}{
		{"http redirect", func(*http.Request) (*http.Response, error) {
			return response(302, nil, "http://release-assets.githubusercontent.com/a"), nil
		}},
		{"private redirect", func(*http.Request) (*http.Response, error) { return response(302, nil, "https://127.0.0.1/a"), nil }},
		{"foreign redirect", func(*http.Request) (*http.Response, error) {
			return response(302, nil, "https://attacker.example/a"), nil
		}},
		{"too many redirects", func(*http.Request) (*http.Response, error) {
			return response(302, nil, "https://objects.githubusercontent.com/a"), nil
		}},
		{"status", func(*http.Request) (*http.Response, error) { return response(500, data, ""), nil }},
		{"short body", func(*http.Request) (*http.Response, error) {
			r := response(200, data[:2], "")
			r.ContentLength = -1
			return r, nil
		}},
		{"long body", func(*http.Request) (*http.Response, error) {
			r := response(200, append(data, 1), "")
			r.ContentLength = -1
			return r, nil
		}},
		{"bad hash", func(*http.Request) (*http.Response, error) {
			return response(200, bytes.Repeat([]byte{'x'}, len(data)), ""), nil
		}},
		{"header length", func(*http.Request) (*http.Response, error) {
			r := response(200, data, "")
			r.ContentLength = 1
			return r, nil
		}},
		{"encoding", func(*http.Request) (*http.Response, error) {
			r := response(200, data, "")
			r.Header.Set("Content-Encoding", "gzip")
			return r, nil
		}},
		{"transport", func(*http.Request) (*http.Response, error) { return nil, errors.New("interrupted") }},
	} {
		t.Run(tc.name, func(t *testing.T) {
			root := t.TempDir()
			calls := 0
			d := &Downloader{enabled: true, transport: offlineTransport(func(r *http.Request) (*http.Response, error) { calls++; return tc.fn(r) })}
			if _, e := d.Fetch(context.Background(), root, a); e == nil {
				t.Fatal("accepted")
			}
			if calls > 4 {
				t.Fatal("request budget exceeded")
			}
			entries, _ := os.ReadDir(root)
			if len(entries) != 0 {
				t.Fatal("partial cache")
			}
		})
	}
}
func TestURLAndIPPolicies(t *testing.T) {
	for _, s := range []string{"https://objects.githubusercontent.com:443/a", "https://user@objects.githubusercontent.com/a", "https://objects.githubusercontent.com.evil/a", "https://objects.githubusercontent.com/a#fragment", "https://[::1]/a", "https://OBJECTS.githubusercontent.com/a"} {
		u, e := url.Parse(s)
		if e == nil && validFetchURL(u, false) == nil {
			t.Fatalf("accepted %s", s)
		}
	}
	for _, s := range []string{"127.0.0.1", "10.0.0.1", "172.16.1.1", "192.168.1.1", "169.254.169.254", "::1", "fc00::1", "100.64.0.1", "198.18.0.1", "2001:db8::1"} {
		if publicIP(net.ParseIP(s)) {
			t.Fatalf("accepted %s", s)
		}
	}
	if !publicIP(net.ParseIP("140.82.112.3")) {
		t.Fatal("public rejected")
	}
}
func TestUnverifiedNeverRequests(t *testing.T) {
	d := &Downloader{enabled: true, transport: offlineTransport(func(*http.Request) (*http.Response, error) { t.Fatal("network reached"); return nil, nil })}
	if _, e := d.Fetch(context.Background(), t.TempDir(), VerifiedArtifact{}); e == nil {
		t.Fatal("unverified accepted")
	}
}

type endlessBody struct {
	read   int
	closed bool
}

func (b *endlessBody) Read(p []byte) (int, error) {
	for i := range p {
		p[i] = 'x'
	}
	b.read += len(p)
	return len(p), nil
}
func (b *endlessBody) Close() error { b.closed = true; return nil }
func TestFetchReadBudget(t *testing.T) {
	data := []byte("fixture")
	a := planned(t, artifact(data))
	redirectBody := &endlessBody{}
	body := &endlessBody{}
	calls := 0
	d := &Downloader{enabled: true, transport: offlineTransport(func(*http.Request) (*http.Response, error) {
		calls++
		if calls == 1 {
			return &http.Response{StatusCode: 302, Header: http.Header{"Location": []string{"https://objects.githubusercontent.com/a"}}, Body: redirectBody, ContentLength: -1}, nil
		}
		return &http.Response{StatusCode: 200, Header: make(http.Header), Body: body, ContentLength: -1}, nil
	})}
	if _, e := d.Fetch(context.Background(), t.TempDir(), a); e == nil {
		t.Fatal("oversized body accepted")
	}
	if body.read != len(data)+1 || !body.closed || redirectBody.read != 0 || !redirectBody.closed {
		t.Fatalf("body %+v redirect %+v", body, redirectBody)
	}
}
