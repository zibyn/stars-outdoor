// Stars Outdoor API (spec §3.2): one Go service in front of PostgreSQL/PostGIS. Contract: openapi.yaml,
// from which api/ is generated (ADR 0004); handlers implement api.StrictServerInterface.
package main

//go:generate go tool oapi-codegen -config oapi-codegen.yaml openapi.yaml

import (
	"context"
	"encoding/json"
	"log"
	"net"
	"net/http"
	"net/url"
	"os"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
	"gocloud.dev/blob"
	_ "gocloud.dev/blob/s3blob"

	"stars-outdoor/server/api"
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
	// Object storage over the S3 API only (ADR 0003): RustFS takes path-style, OSS virtual-hosted.
	// Credentials: AWS_ACCESS_KEY_ID / AWS_SECRET_ACCESS_KEY. Signed download URLs carry S3_ENDPOINT's
	// host, so it must be an address the phones can reach, not a compose service name.
	q := url.Values{"endpoint": {os.Getenv("S3_ENDPOINT")}, "region": {env("S3_REGION", "us-east-1")}, "use_path_style": {env("S3_PATH_STYLE", "true")}}
	bucketURL := "s3://" + os.Getenv("S3_BUCKET") + "?" + q.Encode()
	bucket, err := blob.OpenBucket(context.Background(), bucketURL)
	if err != nil {
		log.Fatal(err)
	}
	off := newOffline(bucket, postgisRegion(db), pmtilesExtract(bucketURL), 1<<30) // §2.3: 1 GB per device per day
	addr := ":" + env("PORT", "8080")
	srv := &http.Server{
		Addr:              addr,
		Handler:           newHandler(envInt("MIN_CLIENT_VERSION", 1), envInt("RATE_LIMIT_PER_MIN", 120), postgis, off),
		ReadHeaderTimeout: 10 * time.Second,
		IdleTimeout:       2 * time.Minute,
	}
	log.Printf("listening on %s", addr)
	log.Fatal(srv.ListenAndServe())
}

func newHandler(minClient, perMin int, postgis func(context.Context) (string, error), off *offline) http.Handler {
	return withMiddleware(routes(minClient, postgis, off), minClient, perMin)
}

// server implements the generated api.StrictServerInterface; the offline routes come with *offline.
// off may be nil in tests that don't touch offline packages.
type server struct {
	minClient int
	postgis   func(context.Context) (string, error)
	*offline
}

// routes mounts the generated handlers. Errors outside the handlers' typed responses: unparseable
// requests are invalid_request, and a handler's returned error is logged and answered as internal,
// so failure details never reach clients (ADR 0004).
func routes(minClient int, postgis func(context.Context) (string, error), off *offline) *http.ServeMux {
	mux := http.NewServeMux()
	invalid := func(w http.ResponseWriter, r *http.Request, err error) {
		writeJSON(w, http.StatusBadRequest, api.Error{Error: api.ErrorCodeInvalidRequest})
	}
	strict := api.NewStrictHandlerWithOptions(&server{minClient, postgis, off}, nil, api.StrictHTTPServerOptions{
		RequestErrorHandlerFunc: invalid,
		ResponseErrorHandlerFunc: func(w http.ResponseWriter, r *http.Request, err error) {
			log.Printf("%s %s: %v", r.Method, r.URL.Path, err)
			writeJSON(w, http.StatusInternalServerError, api.Error{Error: api.ErrorCodeInternal})
		},
	})
	api.HandlerWithOptions(strict, api.StdHTTPServerOptions{BaseURL: "/v1", BaseRouter: mux, ErrorHandlerFunc: invalid})
	return mux
}

func (s *server) GetVersion(ctx context.Context, _ api.GetVersionRequestObject) (api.GetVersionResponseObject, error) {
	return api.GetVersion200JSONResponse{Api: api.V1, MinClientVersion: s.minClient}, nil
}

func (s *server) GetHealth(ctx context.Context, _ api.GetHealthRequestObject) (api.GetHealthResponseObject, error) {
	v, err := s.postgis(ctx)
	if err != nil {
		log.Printf("health: %v", err)
		return api.GetHealth503JSONResponse{Status: "db unavailable"}, nil
	}
	return api.GetHealth200JSONResponse{Status: api.Ok, Postgis: v}, nil
}

type clientIPKey struct{}

// clientIP is the caller's IP, as seen by withMiddleware.
func clientIP(ctx context.Context) string {
	ip, _ := ctx.Value(clientIPKey{}).(string)
	return ip
}

// withMiddleware adds per-device rate limiting, the minimum-client-version gate and a 1 MB body cap,
// and passes the caller's IP on to handlers (clientIP).
func withMiddleware(next http.Handler, minClient, perMin int) http.Handler {
	devices, ips := &limiter{max: int64(perMin), period: 60}, &limiter{max: int64(perMin * ipShare), period: 60}
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		host, _, _ := net.SplitHostPort(r.RemoteAddr)
		if !ips.take(host, 1) || !devices.take(host+" "+r.Header.Get("X-Device-Id"), 1) {
			writeJSON(w, http.StatusTooManyRequests, api.Error{Error: api.ErrorCodeRateLimited})
			return
		}
		// Too-old clients lose only the online features; health and version stay reachable so the
		// app can tell "update required" apart from "server down". No header (curl, monitoring) passes.
		if v := r.Header.Get("X-Client-Version"); v != "" && r.URL.Path != "/v1/health" && r.URL.Path != "/v1/version" {
			if n, err := strconv.Atoi(v); err != nil || n < minClient {
				writeJSON(w, http.StatusUpgradeRequired, api.Error{Error: api.ErrorCodeClientOutdated, MinClientVersion: &minClient})
				return
			}
		}
		r.Body = http.MaxBytesReader(w, r.Body, 1<<20)
		next.ServeHTTP(w, r.WithContext(context.WithValue(r.Context(), clientIPKey{}, host)))
	})
}

// X-Device-Id is anonymous and client-chosen, so rotating it is capped by a looser per-IP limit
// (loose because carrier NAT puts many phones behind one IP). No device ID = one device per IP.
// ponytail: RemoteAddr only. Behind a reverse proxy every client shares the proxy's IP: read
// X-Forwarded-For from the trusted proxy before adding one (deploy/README.md).
const ipShare = 10

// ponytail: fixed windows (UTC-aligned), in memory (single instance, §3.2): one request limit for every
// route, plus the offline packages' daily byte quotas. Per-route limits (weather proxy) go here when that proxy lands.
type limiter struct {
	mu     sync.Mutex
	max    int64
	period int64 // window length in seconds
	window int64
	n      map[string]int64
}

// take adds n to key's count in the current window, unless that would pass max.
func (l *limiter) take(key string, n int64) bool {
	l.mu.Lock()
	defer l.mu.Unlock()
	if !l.fitsLocked(key, n) {
		return false
	}
	l.n[key] += n
	return true
}

// fits reports whether take(key, n) would succeed now.
func (l *limiter) fits(key string, n int64) bool {
	l.mu.Lock()
	defer l.mu.Unlock()
	return l.fitsLocked(key, n)
}

func (l *limiter) fitsLocked(key string, n int64) bool {
	if w := time.Now().Unix() / l.period; w != l.window || l.n == nil {
		l.window, l.n = w, map[string]int64{}
	}
	return l.n[key]+n <= l.max
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
