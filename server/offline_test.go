package main

import (
	"context"
	"encoding/json"
	"math"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"strings"
	"testing"

	"github.com/paulmach/orb/geojson"
	"github.com/protomaps/go-pmtiles/pmtiles"
	"gocloud.dev/blob/fileblob"

	"stars-outdoor/server/api"
)

// A bucket holding stand-in source archives whose headers cover China's bbox, and an extractor that
// writes 1000 bytes per file and counts its calls. The region is the geometry's bbox (plus buffer).
func testOffline(t *testing.T, quota int64) (http.Handler, *int) {
	t.Helper()
	u, _ := url.Parse("https://s3.test/")
	b, err := fileblob.OpenBucket(t.TempDir(), &fileblob.Options{URLSigner: fileblob.NewURLSignerHMAC(u, []byte("k"))})
	if err != nil {
		t.Fatal(err)
	}
	header := pmtiles.SerializeHeader(pmtiles.HeaderV3{MinLonE7: 73.4e7, MinLatE7: 18e7, MaxLonE7: 135.1e7, MaxLatE7: 53.6e7})
	for _, f := range sourceFiles {
		if err := b.WriteAll(context.Background(), f, header, nil); err != nil {
			t.Fatal(err)
		}
	}
	calls := 0
	extract := func(ctx context.Context, src, regionFile, out string) error {
		calls++
		if _, err := os.Stat(regionFile); err != nil {
			t.Errorf("region file: %v", err)
		}
		return os.WriteFile(out, make([]byte, 1000), 0o644)
	}
	region := func(ctx context.Context, geom string, bufferM float64) (region, error) {
		g, err := geojson.UnmarshalGeometry([]byte(geom))
		if err != nil {
			return region{}, err
		}
		bd := g.Geometry().Bound()
		km := bufferM / 1000
		w := (bd.Max[0]-bd.Min[0])*111*math.Cos(bd.Center()[1]*math.Pi/180) + 2*km
		h := (bd.Max[1]-bd.Min[1])*111 + 2*km
		return region{GeoJSON: geom, AreaKm2: w * h, Bbox: [4]float64{bd.Min[0], bd.Min[1], bd.Max[0], bd.Max[1]}}, nil
	}
	o := newOffline(b, region, extract, quota)
	return withMiddleware(routes(1, okDB, o, nil), 1, 1000), &calls
}

func post(h http.Handler, body string, device string) *httptest.ResponseRecorder {
	r := httptest.NewRequest("POST", "/v1/offline/packages", strings.NewReader(body))
	r.Header.Set("X-Device-Id", device)
	w := httptest.NewRecorder()
	h.ServeHTTP(w, r)
	return w
}

func errorOf(w *httptest.ResponseRecorder) string {
	var e struct{ Error string }
	json.Unmarshal(w.Body.Bytes(), &e)
	return e.Error
}

const qinling = `{"bbox":[107.7,33.9,107.9,34.1]}`

func TestSecondRequestForSameRangeHitsCache(t *testing.T) {
	h, calls := testOffline(t, 1<<30)
	var first, second api.Package
	for i, out := range []*api.Package{&first, &second} {
		w := post(h, qinling, "a")
		if w.Code != 200 {
			t.Fatalf("req %d: %d %s", i, w.Code, w.Body)
		}
		json.Unmarshal(w.Body.Bytes(), out)
	}
	if *calls != len(sourceFiles) {
		t.Fatalf("extracted %d times, want %d (once per source file)", *calls, len(sourceFiles))
	}
	if len(second.Files) != 3 || second.Bytes != 3000 || second.Version == "" || !strings.HasPrefix(second.Files[0].Url, "https://s3.test/") {
		t.Fatalf("%+v", second)
	}
	// A viewport a few hundred metres off snaps to the same package.
	if w := post(h, `{"bbox":[107.702,33.903,107.898,34.097]}`, "a"); w.Code != 200 || *calls != 3 {
		t.Fatalf("nearby: %d, %d extracts", w.Code, *calls)
	}
}

func TestTrackCorridor(t *testing.T) {
	h, calls := testOffline(t, 1<<30)
	if w := post(h, `{"track":[[107.7,33.9],[107.8,34.0],[107.9,34.0]]}`, "a"); w.Code != 200 || *calls != 3 {
		t.Fatalf("%d %s", w.Code, w.Body)
	}
}

func TestLimitsAndUnsupportedRegions(t *testing.T) {
	h, _ := testOffline(t, 1<<30)
	cases := []struct{ body, want string }{
		{`{"bbox":[107,33,109,35]}`, "region_too_large"}, // ~185 × 222 km
		{`{"bbox":[2.2,48.8,2.4,48.9]}`, "region_unsupported"},
		{`{"bbox":[108,34,107,35]}`, "invalid_region"},
		{`{"bbox":[107,34,107.1]}`, "invalid_region"},
		{`{"track":[[107.7,33.9]]}`, "invalid_region"},
		{`{"track":[[107.7,95],[107.8,34]]}`, "invalid_region"},
		{`{}`, "invalid_region"},
		{`{"bbox":`, "invalid_request"},
		{`{"track":[[107.7,33.9,1],[107.8,34]]}`, "invalid_region"},
	}
	for _, c := range cases {
		if w := post(h, c.body, "a"); w.Code != 400 || errorOf(w) != c.want {
			t.Errorf("%s: %d %s, want %s", c.body, w.Code, w.Body, c.want)
		}
	}
}

func TestDailyQuotaPerDevice(t *testing.T) {
	h, _ := testOffline(t, 5000) // each package is 3000 bytes
	if w := post(h, qinling, "a"); w.Code != 200 {
		t.Fatalf("first: %d", w.Code)
	}
	w := post(h, qinling, "a")
	if w.Code != 429 || errorOf(w) != "daily_quota_exceeded" || !strings.Contains(w.Body.String(), `"quotaBytes":5000`) {
		t.Fatalf("second: %d %s", w.Code, w.Body)
	}
	if w := post(h, qinling, "b"); w.Code != 200 {
		t.Fatalf("other device: %d", w.Code)
	}
}

func TestDataVersion(t *testing.T) {
	h, _ := testOffline(t, 1<<30)
	w := get(h, "/v1/offline/version")
	var v struct{ Version string }
	json.Unmarshal(w.Body.Bytes(), &v)
	var p api.Package
	json.Unmarshal(post(h, qinling, "a").Body.Bytes(), &p)
	if w.Code != 200 || v.Version == "" || v.Version != p.Version {
		t.Fatalf("%d %s vs %q", w.Code, w.Body, p.Version)
	}
}
