package update

import (
	"bytes"
	"context"
	"crypto/rsa"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
)

const releaseAPI = "https://api.github.com/repos/" + Repository + "/releases/latest"
const maxReleaseJSON = 256 * 1024
const maxReleaseAssets = 128
const maxSignature = 1024 // raw RSA signature, pinned key at most 8192 bits

// Discover verifies the latest stable release's manifest using the caller's
// locally provisioned key. GitHub is only a discovery/distribution service:
// no key, hash, or installation decision is trusted from its API response.
// Replay/version policy is enforced separately by Plan and persistent Store.
func (d *Downloader) Discover(ctx context.Context, pinned *rsa.PublicKey) (VerifiedManifest, error) {
	if d == nil || !d.enabled || d.transport == nil {
		return VerifiedManifest{}, errors.New("network downloader disabled")
	}
	if pinned == nil || pinned.N == nil || pinned.N.BitLen() < 2048 || pinned.N.BitLen() > 8192 || pinned.N.Sign() <= 0 || pinned.E < 3 || pinned.E > 2147483647 || pinned.E%2 == 0 {
		return VerifiedManifest{}, errors.New("invalid local release key")
	}
	ctx, cancel := context.WithTimeout(ctx, fetchTimeout)
	defer cancel()
	raw, err := d.fetchSmall(ctx, releaseAPI, maxReleaseJSON, true)
	if err != nil {
		return VerifiedManifest{}, err
	}
	tag, sizes, err := parseRelease(raw)
	if err != nil {
		return VerifiedManifest{}, err
	}
	base := "https://github.com/" + Repository + "/releases/download/" + tag + "/"
	manifest, err := d.fetchSmall(ctx, base+"manifest.json", MaxManifest, false)
	if err != nil {
		return VerifiedManifest{}, err
	}
	if int64(len(manifest)) != sizes["manifest.json"] {
		return VerifiedManifest{}, errors.New("manifest API size mismatch")
	}
	signature, err := d.fetchSmall(ctx, base+"manifest.sig", maxSignature, false)
	if err != nil {
		return VerifiedManifest{}, err
	}
	if int64(len(signature)) != sizes["manifest.sig"] || len(signature) != pinned.Size() {
		return VerifiedManifest{}, errors.New("signature size mismatch")
	}
	v, err := Verify(manifest, signature, pinned)
	if err != nil {
		return VerifiedManifest{}, err
	}
	if v.manifest.Tag != tag {
		return VerifiedManifest{}, errors.New("signed manifest does not match release tag")
	}
	return v, nil
}

// API redirects are forbidden; release assets use the existing CDN whitelist.
// Each metadata body is read at most limit+1 bytes, including unknown lengths.
func (d *Downloader) fetchSmall(ctx context.Context, rawURL string, limit int64, api bool) ([]byte, error) {
	u, err := url.Parse(rawURL)
	if err != nil {
		return nil, err
	}
	if api {
		if rawURL != releaseAPI {
			return nil, errors.New("unapproved API endpoint")
		}
	} else if err = validFetchURL(u, true); err != nil {
		return nil, err
	}
	client := http.Client{Transport: d.transport, CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}
	for redirects := 0; ; redirects++ {
		req, err := http.NewRequestWithContext(ctx, http.MethodGet, u.String(), nil)
		if err != nil {
			return nil, err
		}
		req.Header.Set("Accept-Encoding", "identity")
		req.Header.Set("User-Agent", "p7-updatecore")
		if api {
			req.Header.Set("Accept", "application/vnd.github+json")
			req.Header.Set("X-GitHub-Api-Version", "2022-11-28")
		}
		resp, err := client.Do(req)
		if err != nil {
			return nil, err
		}
		if resp.StatusCode >= 300 && resp.StatusCode < 400 {
			next, e := resp.Location()
			resp.Body.Close()
			if api || redirects >= maxRedirects {
				return nil, errors.New("metadata redirect denied")
			}
			if e != nil {
				return nil, e
			}
			if e = validFetchURL(next, false); e != nil {
				return nil, e
			}
			u = next
			continue
		}
		defer resp.Body.Close()
		if resp.StatusCode != http.StatusOK {
			return nil, fmt.Errorf("metadata HTTP status %d", resp.StatusCode)
		}
		if enc := resp.Header.Get("Content-Encoding"); enc != "" && enc != "identity" {
			return nil, errors.New("encoded metadata rejected")
		}
		if resp.ContentLength > limit {
			return nil, errors.New("metadata body too large")
		}
		data, err := io.ReadAll(io.LimitReader(resp.Body, limit+1))
		if err != nil {
			return nil, err
		}
		if len(data) == 0 || int64(len(data)) > limit {
			return nil, errors.New("metadata body size")
		}
		return data, nil
	}
}

func parseRelease(raw []byte) (string, map[string]int64, error) {
	if len(raw) > maxReleaseJSON {
		return "", nil, errors.New("release JSON size")
	}
	if err := uniqueReleaseJSON(raw); err != nil {
		return "", nil, err
	}
	var root map[string]json.RawMessage
	if err := json.Unmarshal(raw, &root); err != nil {
		return "", nil, err
	}
	if err := exactKeys(root, []string{"tag_name", "draft", "prerelease", "assets"}); err != nil {
		return "", nil, err
	}
	var tag string
	var draft, pre bool
	var assets []map[string]json.RawMessage
	if json.Unmarshal(root["tag_name"], &tag) != nil || !tagPattern.MatchString(tag) || json.Unmarshal(root["draft"], &draft) != nil || string(root["draft"]) != "false" || json.Unmarshal(root["prerelease"], &pre) != nil || string(root["prerelease"]) != "false" || json.Unmarshal(root["assets"], &assets) != nil || len(assets) > maxReleaseAssets {
		return "", nil, errors.New("release is not a valid stable release")
	}
	sizes := map[string]int64{}
	for _, a := range assets {
		if err := exactKeys(a, []string{"name", "size", "browser_download_url", "state"}); err != nil {
			return "", nil, err
		}
		var name, state, download string
		var size int64
		if json.Unmarshal(a["name"], &name) != nil {
			return "", nil, errors.New("asset name")
		}
		if name != "manifest.json" && name != "manifest.sig" {
			continue
		}
		if _, ok := sizes[name]; ok {
			return "", nil, errors.New("duplicate manifest asset")
		}
		max := int64(MaxManifest)
		if name == "manifest.sig" {
			max = maxSignature
		}
		if json.Unmarshal(a["size"], &size) != nil || size <= 0 || size > max || json.Unmarshal(a["state"], &state) != nil || state != "uploaded" || json.Unmarshal(a["browser_download_url"], &download) != nil || download != "https://github.com/"+Repository+"/releases/download/"+tag+"/"+name {
			return "", nil, errors.New("invalid manifest asset")
		}
		sizes[name] = size
	}
	if len(sizes) != 2 {
		return "", nil, errors.New("manifest.json and manifest.sig assets required")
	}
	return tag, sizes, nil
}
func exactKeys(m map[string]json.RawMessage, required []string) error {
	for _, want := range required {
		if _, ok := m[want]; !ok {
			return errors.New("missing exact release field")
		}
		for got := range m {
			if got != want && strings.EqualFold(got, want) {
				return errors.New("case-aliased release field")
			}
		}
	}
	return nil
}

// GitHub adds fields over time, so unknown fields are ignored, but duplicate
// keys and excessive nesting are rejected before interpreting security fields.
func uniqueReleaseJSON(raw []byte) error {
	d := json.NewDecoder(bytes.NewReader(raw))
	var walk func(int) error
	walk = func(depth int) error {
		if depth > 32 {
			return errors.New("release JSON nesting")
		}
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
				key, e := d.Token()
				if e != nil {
					return e
				}
				s, ok := key.(string)
				if !ok || seen[s] {
					return errors.New("duplicate release JSON key")
				}
				seen[s] = true
				if e = walk(depth + 1); e != nil {
					return e
				}
			}
		case '[':
			for d.More() {
				if err := walk(depth + 1); err != nil {
					return err
				}
			}
		default:
			return errors.New("unexpected JSON delimiter")
		}
		_, err = d.Token()
		return err
	}
	if err := walk(0); err != nil {
		return err
	}
	if _, err := d.Token(); err != io.EOF {
		return errors.New("trailing release JSON")
	}
	return nil
}
