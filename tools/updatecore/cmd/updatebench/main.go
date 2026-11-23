// updatebench is an off-car laboratory tool. It never executes downloaded
// artifacts, touches FTP, switches vehicle paths, or enables production OTA.
package main

import (
	"context"
	"crypto/rsa"
	"crypto/x509"
	"encoding/json"
	"encoding/pem"
	"errors"
	"flag"
	"fmt"
	"io"
	"os"
	update "p7-update-core"
	"path/filepath"
)

const marker = "P7 OFF-CAR BENCH v1\n"

func main() {
	if e := run(os.Args[1:], os.Stdout); e != nil {
		fmt.Fprintln(os.Stderr, e)
		os.Exit(1)
	}
}
func bounded(path string, max int64) ([]byte, error) {
	f, e := os.Open(path)
	if e != nil {
		return nil, e
	}
	defer f.Close()
	b, e := io.ReadAll(io.LimitReader(f, max+1))
	if e != nil {
		return nil, e
	}
	if int64(len(b)) > max {
		return nil, errors.New("input exceeds limit")
	}
	return b, nil
}
func publicPin(raw []byte) (*rsa.PublicKey, error) {
	block, rest := pem.Decode(raw)
	if block == nil || len(rest) != 0 || block.Type != "PUBLIC KEY" {
		return nil, errors.New("expected one PKIX PUBLIC KEY, never private material")
	}
	p, e := x509.ParsePKIXPublicKey(block.Bytes)
	if e != nil {
		return nil, e
	}
	k, ok := p.(*rsa.PublicKey)
	if !ok || k.N == nil || k.N.BitLen() < 2048 {
		return nil, errors.New("RSA >=2048 public pin required")
	}
	return k, nil
}
func run(args []string, out io.Writer) error {
	if len(args) == 0 {
		return errors.New("usage: updatebench init|stage|stage-latest|status|simulate-cycle|recover-generation --root NEW_PRIVATE_BENCH_DIR [flags]")
	}
	command := args[0]
	switch command {
	case "init", "stage", "stage-latest", "status", "simulate-cycle", "recover-generation":
	default:
		return errors.New("unknown command")
	}
	f := flag.NewFlagSet(command, flag.ContinueOnError)
	root := f.String("root", "", "off-car bench directory")
	pin := f.String("pin", "", "public key PEM (init only)")
	manifest := f.String("manifest", "", "signed manifest file")
	signature := f.String("signature", "", "detached binary signature file")
	assets := f.String("assets", "", "local artifact input directory (no execution)")
	apk := f.Int("apk-code", 0, "explicit compatible APK code for lab profile")
	boot := f.String("boot", "", "lab boot generation, not vehicle health")
	confirm := f.Bool("confirm-lab", false, "simulate healthy confirmation in this process; NEVER a real health check")
	network := f.Bool("network", false, "allow bounded GitHub artifact downloads; default disabled")
	if e := f.Parse(args[1:]); e != nil {
		return e
	}
	if *root == "" || f.NArg() != 0 {
		return errors.New("root required; no positional arguments")
	}
	if command != "init" && *pin != "" {
		return errors.New("pin replacement is not supported; use provisioned bench trust root")
	}
	device := update.Device{Target: "cluster-linux-armv7", APKCode: *apk, Protocol: 1, InstrumentABI: "linux-armv7"}
	receiver := filepath.Join(*root, "receiver")
	sender := filepath.Join(*root, "sender")
	if command == "init" {
		if *pin == "" || *manifest == "" || *signature == "" || *assets == "" || *network {
			return errors.New("init requires local pin/manifest/signature/assets; network forbidden")
		}
		raw, e := bounded(*pin, 16*1024)
		if e != nil {
			return e
		}
		key, e := publicPin(raw)
		if e != nil {
			return e
		}
		v, e := readVerified(*manifest, *signature, key)
		if e != nil {
			return e
		}
		if _, e = update.Plan(v, device); e != nil {
			return e
		}
		// Refuse all existing roots. Never initialize over a vehicle or failed store.
		if e = os.Mkdir(*root, 0700); e != nil {
			return e
		}
		for _, p := range []string{receiver, sender} {
			if e = os.Mkdir(p, 0700); e != nil {
				return e
			}
		}
		if e = os.WriteFile(filepath.Join(*root, "pin.pem"), raw, 0600); e != nil {
			return e
		}
		if e = update.ImportBundle(receiver, *assets, v, device); e != nil {
			return e
		}
		s, e := update.BootstrapStore(receiver, key, v)
		if e != nil {
			return e
		}
		defer s.Close()
		if e = os.WriteFile(filepath.Join(*root, "BENCH_ONLY"), []byte(marker), 0600); e != nil {
			return e
		}
		return json.NewEncoder(out).Encode(map[string]string{"status": "off-car baseline provisioned", "productionActivation": "disabled"})
	}
	mark, e := bounded(filepath.Join(*root, "BENCH_ONLY"), 128)
	if e != nil || string(mark) != marker {
		return errors.New("not an initialized off-car bench")
	}
	raw, e := bounded(filepath.Join(*root, "pin.pem"), 16*1024)
	if e != nil {
		return e
	}
	key, e := publicPin(raw)
	if e != nil {
		return e
	}
	s, e := update.OpenStore(receiver, key)
	if e != nil {
		return e
	}
	defer s.Close()
	switch command {
	case "recover-generation":
		destination := filepath.Join(*root, "generations")
		if e = os.Mkdir(destination, 0700); e != nil && !os.IsExist(e) {
			return e
		}
		generation, e := s.MaterializeRecovery(destination)
		if e != nil {
			return e
		}
		return json.NewEncoder(out).Encode(generation)
	case "status":
		state, e := s.Recover()
		if e != nil {
			return e
		}
		return json.NewEncoder(out).Encode(state)
	case "stage", "stage-latest":
		downloader := update.NewDownloader(*network)
		var v update.VerifiedManifest
		if command == "stage-latest" {
			if !*network || *assets != "" {
				return errors.New("stage-latest requires --network and no local asset override")
			}
			v, e = downloader.Discover(context.Background(), key)
		} else {
			v, e = readVerified(*manifest, *signature, key)
		}
		if e != nil {
			return e
		}
		// Check receiver replay/compatibility before any import/download.
		if _, e = s.Prepare(v, device); e != nil {
			return e
		}
		if *assets != "" {
			if e = update.ImportBundle(sender, *assets, v, device); e != nil {
				return e
			}
		}
		result, e := update.StageRelease(context.Background(), downloader, sender, s, v, device)
		if e != nil {
			return e
		}
		return json.NewEncoder(out).Encode(result)
	case "simulate-cycle":
		v, e := readVerified(*manifest, *signature, key)
		if e != nil {
			return e
		}
		state, e := s.SimulateTrial(v, device, *boot)
		if e != nil {
			return e
		}
		if *confirm {
			if e = s.SimulateConfirm(state.Nonce, state.Boot); e != nil {
				return e
			}
			state, e = s.Recover()
			if e != nil {
				return e
			}
		}
		return json.NewEncoder(out).Encode(state)
	default:
		return errors.New("unknown command")
	}
}
func readVerified(manifest, signature string, key *rsa.PublicKey) (update.VerifiedManifest, error) {
	raw, e := bounded(manifest, update.MaxManifest)
	if e != nil {
		return update.VerifiedManifest{}, e
	}
	sig, e := bounded(signature, 4096)
	if e != nil {
		return update.VerifiedManifest{}, e
	}
	return update.Verify(raw, sig, key)
}
