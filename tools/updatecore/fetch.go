package update

import (
	"context"
	"crypto/tls"
	"errors"
	"fmt"
	"net"
	"net/http"
	"net/url"
	"strings"
	"time"
)

const fetchTimeout = 2 * time.Minute
const maxRedirects = 3

// Downloader is opt-in and has no credential, cookie, proxy, or vehicle access.
// The zero value is disabled. Enable only after platform TLS and networking
// policy have been qualified. Package tests substitute an offline transport.
type Downloader struct {
	enabled   bool
	transport http.RoundTripper
}

func NewDownloader(enableNetwork bool) *Downloader {
	return &Downloader{enabled: enableNetwork, transport: &http.Transport{
		Proxy: nil, DisableCompression: true, MaxResponseHeaderBytes: 32 * 1024,
		ResponseHeaderTimeout: 15 * time.Second, TLSHandshakeTimeout: 10 * time.Second,
		TLSClientConfig: &tls.Config{MinVersion: tls.VersionTLS12},
		DialContext:     publicDial,
	}}
}

// Fetch stages only artifacts already selected from a verified manifest.
// It does not install, execute, resume partial files, or accept release keys.
// Total body bytes read are bounded by signed size+1; redirects have no body
// reads and at most four requests are sent. A deadline bounds the whole fetch.
func (d *Downloader) Fetch(ctx context.Context, root string, a VerifiedArtifact) (string, error) {
	if d == nil || !d.enabled || d.transport == nil {
		return "", errors.New("network downloader disabled")
	}
	if !a.verified {
		return "", errors.New("unverified artifact")
	}
	u, err := url.Parse(a.artifact.URL)
	if err != nil || validFetchURL(u, true) != nil {
		return "", errors.New("invalid initial release URL")
	}
	ctx, cancel := context.WithTimeout(ctx, fetchTimeout)
	defer cancel()
	client := http.Client{Transport: d.transport, CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}
	for redirects := 0; ; redirects++ {
		req, err := http.NewRequestWithContext(ctx, http.MethodGet, u.String(), nil)
		if err != nil {
			return "", err
		}
		req.Header.Set("Accept-Encoding", "identity")
		resp, err := client.Do(req)
		if err != nil {
			return "", err
		}
		if resp.StatusCode == 301 || resp.StatusCode == 302 || resp.StatusCode == 303 || resp.StatusCode == 307 || resp.StatusCode == 308 {
			next, e := resp.Location()
			resp.Body.Close()
			if e != nil {
				return "", e
			}
			if redirects >= maxRedirects {
				return "", errors.New("redirect budget exceeded")
			}
			if e = validFetchURL(next, false); e != nil {
				return "", e
			}
			u = next
			continue
		}
		defer resp.Body.Close()
		if resp.StatusCode != http.StatusOK {
			return "", fmt.Errorf("artifact HTTP status %d", resp.StatusCode)
		}
		if resp.Header.Get("Content-Encoding") != "" && resp.Header.Get("Content-Encoding") != "identity" {
			return "", errors.New("encoded response rejected")
		}
		if resp.ContentLength >= 0 && resp.ContentLength != a.artifact.Size {
			return "", errors.New("content length differs from signed size")
		}
		return Stage(root, a, resp.Body)
	}
}
func validFetchURL(u *url.URL, initial bool) error {
	if u == nil || u.Scheme != "https" || u.User != nil || u.Port() != "" || u.Fragment != "" || u.Opaque != "" {
		return errors.New("unsafe artifact URL")
	}
	h := u.Hostname()
	if u.Host != h {
		return errors.New("noncanonical host")
	}
	if initial {
		if h != "github.com" || u.RawQuery != "" || !strings.HasPrefix(u.Path, "/"+Repository+"/releases/download/") {
			return errors.New("not a pinned GitHub release")
		}
	} else if h != "release-assets.githubusercontent.com" && h != "objects.githubusercontent.com" {
		return errors.New("redirect host not allowed")
	}
	return nil
}

// Resolve and dial the checked IP itself, not its hostname, avoiding a second
// DNS lookup after validation. Reject all answers if any is non-public.
func publicDial(ctx context.Context, network, address string) (net.Conn, error) {
	host, port, err := net.SplitHostPort(address)
	if err != nil {
		return nil, err
	}
	if port != "443" {
		return nil, errors.New("non-HTTPS port")
	}
	if host != "api.github.com" && host != "github.com" && host != "release-assets.githubusercontent.com" && host != "objects.githubusercontent.com" {
		return nil, errors.New("dial host not allowed")
	}
	ips, err := net.DefaultResolver.LookupIPAddr(ctx, host)
	if err != nil {
		return nil, err
	}
	if len(ips) == 0 {
		return nil, errors.New("empty DNS response")
	}
	for _, ip := range ips {
		if !publicIP(ip.IP) {
			return nil, errors.New("non-public DNS answer")
		}
	}
	dialer := net.Dialer{Timeout: 10 * time.Second}
	var last error
	for _, ip := range ips {
		c, e := dialer.DialContext(ctx, network, net.JoinHostPort(ip.IP.String(), port))
		if e == nil {
			return c, nil
		}
		last = e
	}
	return nil, last
}
func publicIP(ip net.IP) bool {
	if !ip.IsGlobalUnicast() || ip.IsPrivate() || ip.IsLoopback() || ip.IsLinkLocalUnicast() {
		return false
	}
	for _, s := range []string{"0.0.0.0/8", "100.64.0.0/10", "192.0.0.0/24", "192.0.2.0/24", "198.18.0.0/15", "198.51.100.0/24", "203.0.113.0/24", "240.0.0.0/4", "2001:db8::/32"} {
		_, block, _ := net.ParseCIDR(s)
		if block.Contains(ip) {
			return false
		}
	}
	return true
}
