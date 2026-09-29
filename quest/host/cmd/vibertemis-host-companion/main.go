// Command vibertemis-host-companion is the per-user Windows
// companion for the Vibertemis Quest 3 client.
//
// Phase 1 contract: bound to a narrow control plane only. No
// codec auto-mutation, no automatic ALVR config edits without
// a paired authenticated /start_pcvr, no ALVR Dashboard
// detection that fails open. The companion serves:
//
//	GET  /capabilities   - public, codec enum + pyrowave=false
//	GET  /status         - public, live SteamVR/Dashboard state
//	POST /start_pcvr     - HMAC-authenticated, narrow contract
//
// The state directory, ALVR session path, and Steam exe path
// are operator-supplied via flags (no implicit assumptions).
package main

import (
	"context"
	"crypto/tls"
	"errors"
	"flag"
	"fmt"
	"log"
	"net"
	"os"
	"os/signal"
	"path/filepath"
	"strings"
	"syscall"
	"time"

	"github.com/vibertemis/quest-codec-control/host/internal/alvr"
	"github.com/vibertemis/quest-codec-control/host/internal/pairing"
	"github.com/vibertemis/quest-codec-control/host/internal/server"
	"github.com/vibertemis/quest-codec-control/host/internal/state"
	"github.com/vibertemis/quest-codec-control/host/internal/steamvr"
)

func main() {
	var (
		listenAddr    = flag.String("listen", "127.0.0.1:28540", "listen address")
		advertiseAddr = flag.String("advertise", "", "advertised HTTPS endpoint host:port for the pairing export (default: derived from -listen)")
		stateDir      = flag.String("state-dir", "", "companion state directory (default: per-user)")
		alvrSession   = flag.String("alvr-session", "", "absolute path to ALVR session.json (required)")
		steamPath     = flag.String("steam-path", "", "absolute path to Steam.exe (optional; URL dispatch used otherwise)")
		maxSkew       = flag.Duration("max-skew", 30*time.Second, "max allowed request clock skew")
		showExport    = flag.Bool("show-export", false, "print the pairing export to stdout and exit")
	)
	flag.Parse()

	if *alvrSession == "" {
		log.Fatalf("-alvr-session is required (absolute path to ALVR session.json)")
	}
	if !filepath.IsAbs(*alvrSession) {
		log.Fatalf("-alvr-session must be an absolute path: %s", *alvrSession)
	}
	if *stateDir == "" {
		dir, err := pairing.DefaultStateDir()
		if err != nil {
			log.Fatalf("state dir: %v", err)
		}
		*stateDir = dir
	}
	// EnsureDir is called BEFORE any secret material is
	// written so the directory's private DACL/chmod is in place
	// when the state.json lands.
	if err := pairing.EnsureDir(*stateDir); err != nil {
		log.Fatalf("ensure state dir: %v", err)
	}
	st, err := pairing.Load(*stateDir)
	if err != nil {
		if err != pairing.ErrStateMissing {
			log.Fatalf("load state: %v", err)
		}
		log.Printf("no pairing state; generating fresh cert+token")
		st, err = pairing.Generate()
		if err != nil {
			log.Fatalf("generate state: %v", err)
		}
		if err := pairing.Save(*stateDir, st); err != nil {
			log.Fatalf("save state: %v", err)
		}
	}
	if *advertiseAddr == "" {
		*advertiseAddr = *listenAddr
	}
	if err := validateAdvertisedAddr(*advertiseAddr); err != nil {
		log.Fatalf("-advertise: %v", err)
	}
	if *showExport {
		exp := st.Export(*advertiseAddr)
		fmt.Printf("%s\n", mustJSON(exp))
		return
	}

	tlsCert, err := tls.X509KeyPair([]byte(st.CertPEM), []byte(st.KeyPEM))
	if err != nil {
		log.Fatalf("load cert: %v", err)
	}

	gate := steamvr.NewGate()
	adapter := alvr.NewAdapter(*alvrSession, gate)
	launcher := steamvr.New(steamvr.Options{
		Scanner:       gate,
		Launch:        defaultLaunch,
		ProbeTimeout:  5 * time.Second,
		LaunchTimeout: 5 * time.Second,
	})
	if *steamPath != "" {
		if _, err := os.Stat(*steamPath); err != nil {
			log.Fatalf("steam-path not accessible: %v", err)
		}
	}
	nonceLRU := state.NewNonceLRU(1024, *maxSkew, time.Now)
	// IPLimiter (DOS guard) is high so /status / /capabilities
	// polling is not blocked; ActionLimiter is the strict
	// per-IP start budget (3/30s) that the start handler
	// consumes ONLY after HMAC verifies.
	ipLimiter := state.NewRateLimiterFactory(100, 30*time.Second, 1024, time.Now)
	actionLimiter := state.NewRateLimiterFactory(3, 30*time.Second, 1024, time.Now)

	srv, err := server.New(server.Deps{
		Token:         st.Token,
		Cert:          tlsCert,
		Adapter:       adapter,
		Launcher:      launcher,
		NonceLRU:      nonceLRU,
		IPLimiter:     ipLimiter,
		ActionLimiter: actionLimiter,
		SteamPath:     *steamPath,
		MaxSkew:       *maxSkew,
	})
	if err != nil {
		log.Fatalf("server: %v", err)
	}

	ctx, cancel := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer cancel()

	log.Printf("vibertemis-host-companion listening on https://%s", *listenAddr)
	log.Printf("  alvr session: %s", *alvrSession)
	log.Printf("  state dir   : %s", *stateDir)
	if *steamPath != "" {
		log.Printf("  steam path  : %s", *steamPath)
	} else {
		log.Printf("  steam path  : (URL dispatch)")
	}
	if err := srv.ListenAndServe(ctx, *listenAddr); err != nil {
		log.Fatalf("serve: %v", err)
	}
}

// validateAdvertisedAddr refuses wildcard / loopback addresses
// in the pairing export because the headset cannot dial those
// (Codex #19). The operator must supply a routable host:port.
func validateAdvertisedAddr(addr string) error {
	host, _, err := net.SplitHostPort(addr)
	if err != nil {
		return fmt.Errorf("bad -advertise host:port: %w", err)
	}
	if host == "" {
		return errors.New("bad -advertise: empty host")
	}
	// Reject loopback in any form, IPv4-mapped IPv6, and
	// unspecified addresses.
	if ip := net.ParseIP(host); ip != nil {
		if ip.IsLoopback() || ip.IsUnspecified() || ip.IsLinkLocalUnicast() {
			return errors.New("bad -advertise: loopback / unspecified / link-local not reachable by a headset; supply a routable LAN address, e.g. -advertise 192.168.1.42:28540")
		}
		return nil
	}
	// Hostname: refuse obvious "localhost".
	if strings.EqualFold(host, "localhost") || strings.EqualFold(host, "ip6-localhost") || strings.EqualFold(host, "ip6-loopback") {
		return errors.New("bad -advertise: localhost not reachable by a headset; supply a routable LAN address")
	}
	return nil
}

// defaultLaunch spawns the command and releases the process
// handle so the Go runtime does not retain it. Used for the
// Steam path; the URL dispatch uses its own launch fn.
func defaultLaunch(path string, args ...string) error {
	return spawnAndDetach(path, args...)
}

func mustJSON(v interface{}) string {
	b, err := jsonMarshalIndent(v)
	if err != nil {
		log.Fatalf("marshal: %v", err)
	}
	return string(b)
}
