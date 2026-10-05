// Stars Trail API (spec §3.2): one Go service in front of PostgreSQL/PostGIS. Contract: openapi.yaml,
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
	"path/filepath"
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
	// ponytail: the schema is created at startup, migrations inline (IF EXISTS / IF NOT EXISTS); a tool once they pile up.
	if _, err := db.Exec(context.Background(), usersSchema+teamsSchema+syncSchema+publicTracksSchema); err != nil {
		log.Fatalf("schema: %v", err)
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
	pm, pl, gj := pmtilesExtract(bucketURL), placesExtract(bucket, os.TempDir()), geojsonExtract(bucket, os.TempDir())
	extract := func(ctx context.Context, src, regionFile, out string) error {
		switch filepath.Ext(src) {
		case ".sqlite":
			return pl(ctx, src, regionFile, out)
		case ".geojson":
			return gj(ctx, src, regionFile, out)
		}
		return pm(ctx, src, regionFile, out)
	}
	off := newOffline(bucket, postgisRegion(db), extract, postgisSnapshot(db))
	tdt := &tianditu{key: os.Getenv("TIANDITU_KEY"), upstream: "https://t{s}.tianditu.gov.cn", client: &http.Client{Timeout: 10 * time.Second}}
	qw, err := loadQWeather(os.Getenv("QWEATHER_HOST"), os.Getenv("QWEATHER_PROJECT_ID"), os.Getenv("QWEATHER_KEY_ID"), os.Getenv("QWEATHER_PRIVATE_KEY_PATH"))
	if err != nil {
		log.Fatalf("qweather: %v", err)
	}
	wx := newWeather(qw, "https://api.open-meteo.com", &http.Client{Timeout: 10 * time.Second}, weatherCellsPerDay)
	// ponytail: the public Photon instance (fair use only, issue #18); PHOTON_URL points at a self-hosted one later.
	srch := &search{photon: env("PHOTON_URL", "https://photon.komoot.io"), tianditu: "https://api.tianditu.gov.cn", key: tdt.key, client: &http.Client{Timeout: 10 * time.Second}}
	sms := &aliyunSMS{endpoint: "https://dypnsapi.aliyuncs.com", keyID: os.Getenv("SMS_ACCESS_KEY_ID"), secret: os.Getenv("SMS_ACCESS_KEY_SECRET"),
		signName: os.Getenv("SMS_SIGN_NAME"), template: os.Getenv("SMS_TEMPLATE_CODE"), client: &http.Client{Timeout: 10 * time.Second}}
	acct := newAccounts(sms, pgUsers{db})
	if acct.testLogins = parseTestLogins(os.Getenv("TEST_LOGINS")); len(acct.testLogins) > 0 {
		log.Printf("TEST_LOGINS: %d test numbers log in with a fixed code; never set this on a public server", len(acct.testLogins))
	}
	// 队伍对话's photos on local disk (§3.2), backed up with the database (deploy/README.md).
	images := env("IMAGES_DIR", "images")
	if err := os.MkdirAll(images, 0o755); err != nil {
		log.Fatal(err)
	}
	tm := newTeams(pgTeams{db}, images)
	go func() {
		for {
			if err := tm.pruneImages(context.Background(), time.Now().AddDate(0, 0, -180)); err != nil {
				log.Printf("prune images: %v", err)
			}
			time.Sleep(24 * time.Hour)
		}
	}()
	addr := ":" + env("PORT", "8080")
	srv := &http.Server{
		Addr:              addr,
		Handler:           newHandler(envInt("MIN_CLIENT_VERSION", 1), postgis, off, tdt, wx, srch, acct, tm, newCloud(db, images, 1<<30)), // §2.12: 1 GB of photos each
		ReadHeaderTimeout: 10 * time.Second,
		IdleTimeout:       2 * time.Minute,
	}
	log.Printf("listening on %s", addr)
	log.Fatal(srv.ListenAndServe())
}

func newHandler(minClient int, postgis func(context.Context) (string, error), off *offline, tdt *tianditu, wx *weather, srch *search, acct *accounts, tm *teams, cl *cloud) http.Handler {
	return withMiddleware(routes(minClient, postgis, off, tdt, wx, srch, acct, tm, cl), minClient)
}

// server implements the generated api.StrictServerInterface; the offline routes come with *offline.
// off, tianditu, weather, search, accounts, teams and cloud may be nil in tests that don't touch them.
type server struct {
	minClient int
	postgis   func(context.Context) (string, error)
	*offline
	tianditu *tianditu
	weather  *weather
	search   *search
	accounts *accounts
	teams    *teams
	cloud    *cloud
}

// routes mounts the generated handlers. Errors outside the handlers' typed responses: unparseable
// requests are invalid_request, and a handler's returned error is logged and answered as internal,
// so failure details never reach clients (ADR 0004).
func routes(minClient int, postgis func(context.Context) (string, error), off *offline, tdt *tianditu, wx *weather, srch *search, acct *accounts, tm *teams, cl *cloud) *http.ServeMux {
	mux := http.NewServeMux()
	invalid := func(w http.ResponseWriter, r *http.Request, err error) {
		writeJSON(w, http.StatusBadRequest, api.Error{Error: api.ErrorCodeInvalidRequest})
	}
	strict := api.NewStrictHandlerWithOptions(&server{minClient, postgis, off, tdt, wx, srch, acct, tm, cl}, []api.StrictMiddlewareFunc{withRequest}, api.StrictHTTPServerOptions{
		RequestErrorHandlerFunc: invalid,
		ResponseErrorHandlerFunc: func(w http.ResponseWriter, r *http.Request, err error) {
			log.Printf("%s %s: %v", r.Method, r.URL.Path, err)
			writeJSON(w, http.StatusInternalServerError, api.Error{Error: api.ErrorCodeInternal})
		},
	})
	var users userStore // nil only in tests that hit no bearerAuth route
	if acct != nil {
		users = acct.users
	}
	api.HandlerWithOptions(strict, api.StdHTTPServerOptions{BaseURL: "/v1", BaseRouter: mux, ErrorHandlerFunc: invalid, Middlewares: []api.MiddlewareFunc{bearer(users)}})
	return mux
}

type requestKey struct{}

// requestOf is the request a strict handler serves (withRequest), for the WebSocket upgrade.
func requestOf(ctx context.Context) *http.Request {
	r, _ := ctx.Value(requestKey{}).(*http.Request)
	return r
}

// withRequest passes the *http.Request on to strict handlers, which otherwise only see its parsed parts.
func withRequest(f api.StrictHandlerFunc, _ string) api.StrictHandlerFunc {
	return func(ctx context.Context, w http.ResponseWriter, r *http.Request, req any) (any, error) {
		return f(context.WithValue(ctx, requestKey{}, r), w, r, req)
	}
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

// withMiddleware adds the minimum-client-version gate and a 1 MB body cap (32 MB for sync pushes), and
// passes the caller's IP on to handlers (clientIP).
// ponytail: no request limit before launch (ADR 0003); add one per device and IP with launch, before
// the search and 天地图 proxies' keys meet the public.
// ponytail: RemoteAddr only. Behind a reverse proxy every client shares the proxy's IP: read
// X-Forwarded-For from the trusted proxy before putting one in front (deploy/README.md).
func withMiddleware(next http.Handler, minClient int) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		host, _, _ := net.SplitHostPort(r.RemoteAddr)
		// Too-old clients lose only the online features; health and version stay reachable so the
		// app can tell "update required" apart from "server down". No header (curl, monitoring) passes.
		if v := r.Header.Get("X-Client-Version"); v != "" && r.URL.Path != "/v1/health" && r.URL.Path != "/v1/version" {
			if n, err := strconv.Atoi(v); err != nil || n < minClient {
				writeJSON(w, http.StatusUpgradeRequired, api.Error{Error: api.ErrorCodeClientOutdated, MinClientVersion: &minClient})
				return
			}
		}
		limit := int64(1 << 20)
		if r.URL.Path == "/v1/sync" { // a long track's points
			limit = 32 << 20
		}
		if strings.HasPrefix(r.URL.Path, "/v1/teams/") && strings.HasSuffix(r.URL.Path, "/track") { // a 队伍轨迹's, up to 50 000
			limit = 8 << 20
		}
		r.Body = http.MaxBytesReader(w, r.Body, limit)
		next.ServeHTTP(w, r.WithContext(context.WithValue(r.Context(), clientIPKey{}, host)))
	})
}

// ponytail: fixed windows (UTC-aligned), in memory (single instance, §3.2): the login limits and the
// weather proxy's daily cell quotas.
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
