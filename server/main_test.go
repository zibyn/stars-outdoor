package main

import (
	"context"
	"errors"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func okDB(context.Context) (string, error)   { return "3.5 USE_GEOS=1", nil }
func downDB(context.Context) (string, error) { return "", errors.New("refused") }

func get(h http.Handler, path string, hdr ...string) *httptest.ResponseRecorder {
	r := httptest.NewRequest("GET", path, nil)
	for i := 0; i < len(hdr); i += 2 {
		r.Header.Set(hdr[i], hdr[i+1])
	}
	w := httptest.NewRecorder()
	h.ServeHTTP(w, r)
	return w
}

func TestHealth(t *testing.T) {
	if w := get(newHandler(5, 100, okDB, nil, nil, nil), "/v1/health"); w.Code != 200 || !strings.Contains(w.Body.String(), `"postgis":"3.5 USE_GEOS=1"`) {
		t.Fatalf("up: %d %s", w.Code, w.Body)
	}
	if w := get(newHandler(5, 100, downDB, nil, nil, nil), "/v1/health"); w.Code != 503 {
		t.Fatalf("down: %d", w.Code)
	}
}

func TestVersion(t *testing.T) {
	w := get(newHandler(5, 100, okDB, nil, nil, nil), "/v1/version", "X-Client-Version", "1")
	if w.Code != 200 || strings.TrimSpace(w.Body.String()) != `{"api":"v1","minClientVersion":5}` {
		t.Fatalf("%d %s", w.Code, w.Body)
	}
}

func TestOldClientGetsUpgradeRequiredOnlyOnFeatureRoutes(t *testing.T) {
	mux := routes(5, okDB, nil, nil, nil)
	mux.HandleFunc("GET /v1/feature", func(w http.ResponseWriter, r *http.Request) {})
	h := withMiddleware(mux, 5, 100)
	cases := []struct {
		path, ver string
		want      int
	}{
		{"/v1/feature", "4", 426},
		{"/v1/feature", "junk", 426},
		{"/v1/feature", "5", 200},
		{"/v1/feature", "", 200}, // curl, monitoring
		{"/v1/health", "4", 200},
		{"/v1/version", "4", 200},
	}
	for _, c := range cases {
		hdr := []string{"X-Device-Id", "d"}
		if c.ver != "" {
			hdr = append(hdr, "X-Client-Version", c.ver)
		}
		if w := get(h, c.path, hdr...); w.Code != c.want {
			t.Errorf("%s v%q: got %d want %d", c.path, c.ver, w.Code, c.want)
		}
	}
}

func TestRateLimitPerDevice(t *testing.T) {
	h := newHandler(0, 3, okDB, nil, nil, nil)
	for i := 0; i < 3; i++ {
		if w := get(h, "/v1/version", "X-Device-Id", "a"); w.Code != 200 {
			t.Fatalf("req %d: %d", i, w.Code)
		}
	}
	if w := get(h, "/v1/version", "X-Device-Id", "a"); w.Code != 429 {
		t.Fatalf("4th: %d", w.Code)
	}
	if w := get(h, "/v1/version", "X-Device-Id", "b"); w.Code != 200 {
		t.Fatalf("other device: %d", w.Code)
	}
	// Rotating the device ID from one IP: capped at 3*ipShare in total (5 used above).
	var n int
	for i := 0; i < 100; i++ {
		if get(h, "/v1/version", "X-Device-Id", fmt.Sprint("r", i)).Code == 200 {
			n++
		}
	}
	if n != 3*ipShare-5 {
		t.Fatalf("rotating IDs got %d through", n)
	}
}
