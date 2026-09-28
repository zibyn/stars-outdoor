package main

import (
	"context"
	"database/sql"
	"encoding/json"
	"math"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/paulmach/orb"
	"github.com/paulmach/orb/geojson"
	"github.com/protomaps/go-pmtiles/pmtiles"
	"gocloud.dev/blob/fileblob"
	_ "modernc.org/sqlite"

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
	snapshot := func(ctx context.Context, region string) ([]byte, error) {
		return []byte(`{"type":"FeatureCollection","features":[]}`), nil
	}
	o := newOffline(b, region, extract, snapshot, quota)
	return withMiddleware(routes(1, okDB, o, nil, nil, nil, nil, nil, nil), 1, 1000), &calls
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
	// The six clips, and the 公开轨迹 snapshot.
	if len(second.Files) != 7 || second.Files[6].Name != snapshotFile || second.Bytes != 6042 || second.Version == "" || !strings.HasPrefix(second.Files[0].Url, "https://s3.test/") {
		t.Fatalf("%+v", second)
	}
	// A viewport a few hundred metres off snaps to the same package.
	if w := post(h, `{"bbox":[107.702,33.903,107.898,34.097]}`, "a"); w.Code != 200 || *calls != len(sourceFiles) {
		t.Fatalf("nearby: %d, %d extracts", w.Code, *calls)
	}
}

func TestTrackCorridor(t *testing.T) {
	h, calls := testOffline(t, 1<<30)
	if w := post(h, `{"track":[[107.7,33.9],[107.8,34.0],[107.9,34.0]]}`, "a"); w.Code != 200 || *calls != len(sourceFiles) {
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
	h, _ := testOffline(t, 7000) // each package is 6042 bytes
	if w := post(h, qinling, "a"); w.Code != 200 {
		t.Fatalf("first: %d", w.Code)
	}
	w := post(h, qinling, "a")
	if w.Code != 429 || errorOf(w) != "daily_quota_exceeded" || !strings.Contains(w.Body.String(), `"quotaBytes":7000`) {
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

func TestPlacesAreClippedToTheRegion(t *testing.T) {
	ctx := context.Background()
	b, err := fileblob.OpenBucket(t.TempDir(), nil)
	if err != nil {
		t.Fatal(err)
	}
	src := filepath.Join(t.TempDir(), "src.sqlite")
	db, err := sql.Open("sqlite", src)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := db.Exec(`CREATE TABLE places (name TEXT, name_zh TEXT, name_en TEXT, kind TEXT, lon REAL, lat REAL, ele REAL, importance REAL, detail TEXT);
		INSERT INTO places VALUES ('拔仙台', NULL, NULL, 'peak', 107.7653, 33.9551, 3771.2, 0.3, '陕西省 宝鸡市'),
		                          ('玉皇顶', NULL, NULL, 'peak', 117.1033, 36.257, 1532.7, 0.3, '山东省 泰安市')`); err != nil {
		t.Fatal(err)
	}
	db.Close()
	data, _ := os.ReadFile(src)
	if err := b.WriteAll(ctx, placesFile, data, nil); err != nil {
		t.Fatal(err)
	}
	regionFile := filepath.Join(t.TempDir(), "region.geojson")
	if err := os.WriteFile(regionFile, []byte(`{"type":"Polygon","coordinates":[[[107.7,33.9],[107.9,33.9],[107.9,34.1],[107.7,34.1],[107.7,33.9]]]}`), 0o600); err != nil {
		t.Fatal(err)
	}
	out := filepath.Join(t.TempDir(), placesFile)
	if err := placesExtract(b, t.TempDir())(ctx, placesFile, regionFile, out); err != nil {
		t.Fatal(err)
	}
	db, err = sql.Open("sqlite", out)
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	var names []string
	rows, err := db.Query("SELECT name || ' ' || kind || ' ' || ele || ' ' || detail FROM places")
	if err != nil {
		t.Fatal(err)
	}
	for rows.Next() {
		var n string
		if err := rows.Scan(&n); err != nil {
			t.Fatal(err)
		}
		names = append(names, n)
	}
	if strings.Join(names, ",") != "拔仙台 peak 3771.2 陕西省 宝鸡市" {
		t.Fatalf("%v", names)
	}
}

// 徒步线路 and 平台轨迹 go into a package whole, if they reach into the region's extent.
func TestRoutesAreClippedToTheRegion(t *testing.T) {
	ctx := context.Background()
	b, err := fileblob.OpenBucket(t.TempDir(), nil)
	if err != nil {
		t.Fatal(err)
	}
	src := `{"type":"FeatureCollection","features":[
		{"type":"Feature","properties":{"name":"太白山穿越"},"geometry":{"type":"MultiLineString","coordinates":[[[107.5,33.95],[107.75,33.96]]]}},
		{"type":"Feature","properties":{"name":"泰山十八盘"},"geometry":{"type":"LineString","coordinates":[[117.1,36.2],[117.1,36.25]]}}]}`
	if err := b.WriteAll(ctx, routesFile, []byte(src), nil); err != nil {
		t.Fatal(err)
	}
	regionFile := filepath.Join(t.TempDir(), "region.geojson")
	if err := os.WriteFile(regionFile, []byte(`{"type":"Polygon","coordinates":[[[107.7,33.9],[107.9,33.9],[107.9,34.1],[107.7,34.1],[107.7,33.9]]]}`), 0o600); err != nil {
		t.Fatal(err)
	}
	out := filepath.Join(t.TempDir(), routesFile)
	if err := geojsonExtract(b, t.TempDir())(ctx, routesFile, regionFile, out); err != nil {
		t.Fatal(err)
	}
	data, _ := os.ReadFile(out)
	fc, err := geojson.UnmarshalFeatureCollection(data)
	if err != nil || len(fc.Features) != 1 || fc.Features[0].Properties["name"] != "太白山穿越" || len(fc.Features[0].Geometry.(orb.MultiLineString)[0]) != 2 {
		t.Fatalf("%s %v", data, err)
	}
}

// The 关于 page's link (ODbL): the extraction script uploaded with the data.
func TestOsmExtractScript(t *testing.T) {
	b, err := fileblob.OpenBucket(t.TempDir(), nil)
	if err != nil {
		t.Fatal(err)
	}
	h := routes(1, okDB, newOffline(b, nil, nil, nil, 0), nil, nil, nil, nil, nil, nil)
	if w := get(h, "/v1/data/osm-extract"); w.Code != 503 {
		t.Fatalf("not uploaded: %d", w.Code)
	}
	if err := b.WriteAll(context.Background(), osmExtractFile, []byte("osmium tags-filter r/route=hiking,foot"), nil); err != nil {
		t.Fatal(err)
	}
	if w := get(h, "/v1/data/osm-extract"); w.Code != 200 || !strings.HasPrefix(w.Header().Get("Content-Type"), "text/plain") || !strings.Contains(w.Body.String(), "route=hiking") {
		t.Fatalf("%d %s", w.Code, w.Body)
	}
}
