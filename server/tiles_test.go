package main

import (
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"testing"
)

// fakeTianditu answers like 天地图: a PNG for a good key, a 200 XML error page otherwise.
func fakeTianditu(t *testing.T, got *url.URL) *httptest.Server {
	up := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		*got = *r.URL
		if r.URL.Query().Get("tk") != "KEY" {
			w.Header().Set("Content-Type", "text/xml")
			w.Write([]byte("<ows:ExceptionReport>权限类型错误</ows:ExceptionReport>"))
			return
		}
		w.Header().Set("Content-Type", "image/png")
		w.Write([]byte("PNG"))
	}))
	t.Cleanup(up.Close)
	return up
}

func tilesHandler(key, upstream string, perMin int) http.Handler {
	return withMiddleware(routes(1, okDB, nil, &tianditu{key: key, upstream: upstream, client: http.DefaultClient}, nil, nil, nil, nil), 1, perMin)
}

func TestTiandituTileIsProxiedWithTheServersKey(t *testing.T) {
	var got url.URL
	up := fakeTianditu(t, &got)
	w := get(tilesHandler("KEY", up.URL, 100), "/v1/tiles/tianditu/img/3/5/2", "X-Client-Version", "1")
	if w.Code != 200 || w.Body.String() != "PNG" || w.Header().Get("Content-Type") != "image/png" || !strings.Contains(w.Header().Get("Cache-Control"), "max-age") {
		t.Fatalf("%d %q %v", w.Code, w.Body, w.Header())
	}
	q := got.Query()
	if got.Path != "/img_w/wmts" || q.Get("LAYER") != "img" || q.Get("TILEMATRIXSET") != "w" || q.Get("TILEMATRIX") != "3" || q.Get("TILECOL") != "5" || q.Get("TILEROW") != "2" {
		t.Fatalf("upstream got %s", got.String())
	}
}

func TestTiandituTileOutsideTheGridIsInvalid(t *testing.T) {
	var got url.URL
	h := tilesHandler("KEY", fakeTianditu(t, &got).URL, 100)
	for _, p := range []string{"/foo/3/1/1", "/img/0/0/0", "/img/19/0/0", "/img/3/8/0", "/img/3/0/8", "/img/3/-1/0", "/img/3/a/0"} {
		if w := get(h, "/v1/tiles/tianditu"+p); w.Code != 400 || !strings.Contains(w.Body.String(), "invalid_request") {
			t.Errorf("%s: %d %s", p, w.Code, w.Body)
		}
	}
}

func TestTiandituFailureIsDataUnavailableAndHidesTheKey(t *testing.T) {
	var got url.URL
	up := fakeTianditu(t, &got)
	for _, key := range []string{"", "WRONG"} {
		w := get(tilesHandler(key, up.URL, 100), "/v1/tiles/tianditu/vec/1/0/0")
		if w.Code != 503 || !strings.Contains(w.Body.String(), "data_unavailable") || strings.Contains(w.Body.String(), "权限") {
			t.Errorf("key %q: %d %s", key, w.Code, w.Body)
		}
	}
	// Unreachable: the logged error names the URL, which carries the key.
	if msg := redact("SECRET", "Get \"https://t0/x?tk=SECRET\": refused"); strings.Contains(msg, "SECRET") {
		t.Errorf("key leaked: %s", msg)
	}
}

func TestTilesHaveTheirOwnLargerRateLimit(t *testing.T) {
	var got url.URL
	h := tilesHandler("KEY", fakeTianditu(t, &got).URL, 1)
	for i := range 10 {
		if w := get(h, "/v1/tiles/tianditu/cva/1/0/0", "X-Device-Id", "d"); w.Code != 200 {
			t.Fatalf("tile %d: %d", i, w.Code)
		}
	}
	if w := get(h, "/v1/tiles/tianditu/cva/1/0/0", "X-Device-Id", "d"); w.Code != 429 {
		t.Fatalf("tile 11: %d", w.Code)
	}
	if w := get(h, "/v1/version", "X-Device-Id", "d"); w.Code != 200 {
		t.Fatalf("other routes: %d", w.Code)
	}
}
