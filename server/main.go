// Stars Outdoor API (spec §3.2): one Go service in front of PostgreSQL/PostGIS. Contract: openapi.yaml.
package main

import (
	"context"
	"encoding/json"
	"log"
	"net"
	"net/http"
	"os"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
)

func main() {
	// Empty DATABASE_URL: pgx reads PGHOST / PGUSER / PGPASSWORD / PGDATABASE instead.
	db, err := pgxpool.New(context.Background(), os.Getenv("DATABASE_URL"))
	if err != nil {
		log.Fatal(err)
	}
	postgis := func(ctx context.Context) (v string, err error) {
		ctx, cancel := context.WithTimeout(ctx, 2*time.Second)
		defer cancel()
		err = db.QueryRow(ctx, "SELECT postgis_lib_version()").Scan(&v)
		return
	}
	addr := ":" + env("PORT", "8080")
	srv := &http.Server{
		Addr:              addr,
		Handler:           newHandler(envInt("MIN_CLIENT_VERSION", 1), envInt("RATE_LIMIT_PER_MIN", 120), postgis),
		ReadHeaderTimeout: 10 * time.Second,
		IdleTimeout:       2 * time.Minute,
	}
	log.Printf("listening on %s", addr)
	log.Fatal(srv.ListenAndServe())
}

func newHandler(minClient, perMin int, postgis func(context.Context) (string, error)) http.Handler {
	return withMiddleware(routes(minClient, postgis), minClient, perMin)
}

func routes(minClient int, postgis func(context.Context) (string, error)) *http.ServeMux {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /v1/version", func(w http.ResponseWriter, r *http.Request) {
		writeJSON(w, http.StatusOK, map[string]any{"api": "v1", "minClientVersion": minClient})
	})
	mux.HandleFunc("GET /v1/health", func(w http.ResponseWriter, r *http.Request) {
		v, err := postgis(r.Context())
		if err != nil {
			log.Printf("health: %v", err)
			writeJSON(w, http.StatusServiceUnavailable, map[string]any{"status": "db unavailable"})
			return
		}
		writeJSON(w, http.StatusOK, map[string]any{"status": "ok", "postgis": v})
	})
	return mux
}

// withMiddleware adds per-device rate limiting and the minimum-client-version gate.
func withMiddleware(next http.Handler, minClient, perMin int) http.Handler {
	devices, ips := &limiter{max: perMin}, &limiter{max: perMin * ipShare}
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		host, _, _ := net.SplitHostPort(r.RemoteAddr)
		if !ips.allow(host) || !devices.allow(host+" "+r.Header.Get("X-Device-Id")) {
			writeJSON(w, http.StatusTooManyRequests, map[string]any{"error": "rate_limited"})
			return
		}
		// Too-old clients lose only the online features; health and version stay reachable so the
		// app can tell "update required" apart from "server down". No header (curl, monitoring) passes.
		if v := r.Header.Get("X-Client-Version"); v != "" && r.URL.Path != "/v1/health" && r.URL.Path != "/v1/version" {
			if n, err := strconv.Atoi(v); err != nil || n < minClient {
				writeJSON(w, http.StatusUpgradeRequired, map[string]any{"error": "client_outdated", "minClientVersion": minClient})
				return
			}
		}
		next.ServeHTTP(w, r)
	})
}

// X-Device-Id is anonymous and client-chosen, so rotating it is capped by a looser per-IP limit
// (loose because carrier NAT puts many phones behind one IP). No device ID = one device per IP.
// ponytail: RemoteAddr only. Behind a reverse proxy every client shares the proxy's IP: read
// X-Forwarded-For from the trusted proxy before adding one (deploy/README.md).
const ipShare = 10

// ponytail: fixed one-minute window, one limit for every route, in memory (single instance, §3.2).
// Per-route limits (weather proxy) go here when that proxy lands.
type limiter struct {
	mu     sync.Mutex
	max    int
	window int64
	n      map[string]int
}

func (l *limiter) allow(key string) bool {
	l.mu.Lock()
	defer l.mu.Unlock()
	if w := time.Now().Unix() / 60; w != l.window || l.n == nil {
		l.window, l.n = w, map[string]int{}
	}
	l.n[key]++
	return l.n[key] <= l.max
}

func writeJSON(w http.ResponseWriter, code int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(code)
	json.NewEncoder(w).Encode(v)
}

func env(k, def string) string {
	if v := strings.TrimSpace(os.Getenv(k)); v != "" {
		return v
	}
	return def
}

func envInt(k string, def int) int {
	n, err := strconv.Atoi(env(k, strconv.Itoa(def)))
	if err != nil {
		log.Fatalf("%s: %v", k, err)
	}
	return n
}
