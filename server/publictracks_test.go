package main

import (
	"context"
	"encoding/json"
	"fmt"
	"math"
	"net/http"
	"strings"
	"testing"

	"github.com/paulmach/orb"
	"github.com/paulmach/orb/geo"
	"github.com/paulmach/orb/maptile"

	"stars-outdoor/server/api"
)

// A track going north from (34, 108) in 10 m steps for 1 km, in two segments.
func northward() []api.SyncPoint {
	var ps []api.SyncPoint
	for i := range 101 {
		ps = append(ps, api.SyncPoint{Lat: 34 + float64(i)*10/111195, Lon: 108, S: i / 50})
	}
	return ps
}

func TestPublicLineHidesTheEnds(t *testing.T) {
	ps := northward()
	start, end := orb.Point{108, 34}, orb.Point{108, ps[len(ps)-1].Lat}
	line := publicLine(ps, api.WGS84)
	kept := 0.0
	for _, l := range line {
		kept += geo.LengthHaversine(l)
		for _, p := range l {
			if geo.DistanceHaversine(p, start) < privacyRadiusM || geo.DistanceHaversine(p, end) < privacyRadiusM {
				t.Fatalf("point %v within %d m of an end", p, privacyRadiusM)
			}
		}
	}
	// 1 km minus 2 × ~200 m, less the 10 m gap where the two segments stay apart.
	if kept < 560 || kept > 600 || len(line) != 2 {
		t.Fatalf("%.0f m in %d lines", kept, len(line))
	}
}

func TestPublicLineLoopHidesEverythingNearTheStart(t *testing.T) {
	// Out 1 km and back: start and end coincide, so the whole 400 m disc around them goes.
	out := northward()
	var ps []api.SyncPoint
	for _, p := range out {
		ps = append(ps, api.SyncPoint{Lat: p.Lat, Lon: p.Lon})
	}
	for i := len(out) - 1; i >= 0; i-- {
		ps = append(ps, api.SyncPoint{Lat: out[i].Lat, Lon: 108.00001})
	}
	for _, l := range publicLine(ps, api.WGS84) {
		for _, p := range l {
			if geo.DistanceHaversine(p, orb.Point{108, 34}) < privacyRadiusM {
				t.Fatalf("point %v near the start", p)
			}
		}
	}
	if len(publicLine(ps[:30], api.WGS84)) != 0 {
		t.Fatal("a track shorter than the hidden ends shows nothing")
	}
}

func TestPublicLineSparseSegmentsStayOutOfTheDisc(t *testing.T) {
	// A planned route of four points: its last leg passes ~90 m east of the start, to an end far south.
	ps := []api.SyncPoint{{Lat: 34, Lon: 108}, {Lat: 34.01, Lon: 108}, {Lat: 34.01, Lon: 108.001}, {Lat: 33.98, Lon: 108.001}}
	start := orb.Point{108, 34}
	lines := publicLine(ps, api.WGS84)
	if len(lines) < 2 {
		t.Fatalf("the last leg should break around the start: %d lines", len(lines))
	}
	for _, l := range lines {
		for i := 1; i < len(l); i++ {
			for f := 0.0; f <= 1; f += 0.05 { // along each drawn line, not only at its points
				q := orb.Point{l[i-1][0] + (l[i][0]-l[i-1][0])*f, l[i-1][1] + (l[i][1]-l[i-1][1])*f}
				if d := geo.DistanceHaversine(q, start); d < privacyRadiusM {
					t.Fatalf("line passes %.0f m from the start", d)
				}
			}
		}
	}
}

func TestPublicLineIsWGS84(t *testing.T) {
	// Five points 0.005° (~550 m) apart: the ends go, the middle three stay.
	across := func(lat, lon float64) (ps []api.SyncPoint) {
		for i := -2; i <= 2; i++ {
			ps = append(ps, api.SyncPoint{Lat: lat + float64(i)*0.005, Lon: lon})
		}
		return
	}
	lat, lon := 39.90923, 116.397428 // Tiananmen
	gLat, gLon := wgs84ToGcj02(lat, lon)
	has := func(line orb.MultiLineString, lon, lat float64) bool {
		for _, l := range line {
			for _, p := range l {
				if math.Abs(p[0]-lon) < 1e-6 && math.Abs(p[1]-lat) < 1e-6 {
					return true
				}
			}
		}
		return false
	}
	if line := publicLine(across(gLat, gLon), api.GCJ02); len(line) != 1 || !has(line, lon, lat) {
		t.Fatalf("GCJ-02 not corrected: %v", line)
	}
	// Abroad nothing was ever shifted.
	if !has(publicLine(across(35.36, 138.73), api.BD09), 138.73, 35.36) {
		t.Fatal("Fuji moved")
	}
}

// tileBytes is the size of the public-tracks tile at z 14 over p.
func tileBytes(t *testing.T, h http.Handler, p orb.Point) int {
	t.Helper()
	tile := maptile.At(p, 14)
	w := do(h, "GET", fmt.Sprintf("/v1/tiles/public-tracks/%d/%d/%d", tile.Z, tile.X, tile.Y), "", "")
	if w.Code != 200 || w.Header().Get("Content-Type") != "application/vnd.mapbox-vector-tile" {
		t.Fatalf("tile: %d %s", w.Code, w.Body)
	}
	return w.Body.Len()
}

// northTrack is northward() as pushed JSON, published or not.
func northTrack(public bool) string {
	var pts []string
	for _, p := range northward() {
		pts = append(pts, fmt.Sprintf(`{"t":0,"lat":%g,"lon":%g,"s":%d}`, p.Lat, p.Lon, p.S))
	}
	return fmt.Sprintf(`{"tracks":[{"id":"%s","startedAt":1,"endedAt":2,"planned":false,"public":%t,"points":[%s]}],"waypoints":[]}`, trackID, public, strings.Join(pts, ","))
}

const qinlingRegion = `{"type":"Polygon","coordinates":[[[107.9,33.9],[108.1,33.9],[108.1,34.1],[107.9,34.1],[107.9,33.9]]]}`

// Acceptance (#48): nothing within 200 m of a 公开轨迹's start comes back, and after withdrawing it the tiles no longer show it.
func TestPublicTrackTilesHideTheStartAndForgetWithdrawn(t *testing.T) {
	h, db, _, _ := syncServer(t)
	a := login(t, h, "13800138000")
	mid := orb.Point{108, 34.0045}
	push(t, h, a, northTrack(true))
	if n := tileBytes(t, h, mid); n == 0 {
		t.Fatal("empty tile")
	}
	// The tiles draw what is stored: its nearest bit to the start is 200 m off (10 m steps).
	var near float64
	if err := db.QueryRow(context.Background(), "SELECT ST_Distance(geom::geography, ST_Point(108, 34, 4326)::geography) FROM public_tracks").Scan(&near); err != nil || near < privacyRadiusM || near > privacyRadiusM+10 {
		t.Fatalf("nearest to the start: %.1f m, %v", near, err)
	}
	if s := pull(t, h, a, 0); !s.Tracks[0].Public {
		t.Fatalf("not public: %+v", s.Tracks[0])
	}
	// In an offline package's snapshot too.
	fc, err := postgisSnapshot(db)(context.Background(), snapshotFile, qinlingRegion)
	if err != nil || strings.Count(string(fc), `"Feature"`) != 1 {
		t.Fatalf("snapshot: %s %v", fc, err)
	}

	push(t, h, a, `{"tracks":[{"id":"`+trackID+`","public":false}],"waypoints":[]}`)
	if n := tileBytes(t, h, mid); n != 0 {
		t.Fatalf("withdrawn, still %d bytes", n)
	}
	if s := pull(t, h, a, 0); s.Tracks[0].Public {
		t.Fatal("still public")
	}
	// Deleting a public one withdraws it as well.
	push(t, h, a, `{"tracks":[{"id":"`+trackID+`","public":true}],"waypoints":[]}`)
	push(t, h, a, `{"tracks":[{"id":"`+trackID+`","deleted":true}],"waypoints":[]}`)
	push(t, h, a, `{"tracks":[{"id":"`+trackID+`","public":true}],"waypoints":[]}`)
	if n := tileBytes(t, h, mid); n != 0 {
		t.Fatalf("deleted, still %d bytes", n)
	}
}

func TestPublicTrackTileZooms(t *testing.T) {
	h, _, _, _ := syncServer(t)
	for _, path := range []string{"/v1/tiles/public-tracks/10/0/0", "/v1/tiles/public-tracks/17/0/0", "/v1/tiles/public-tracks/11/2048/0"} {
		if w := do(h, "GET", path, "", ""); w.Code != 400 {
			t.Fatalf("%s: %d", path, w.Code)
		}
	}
}

// nearby is the /nearby-tracks answer's features.
func nearby(t *testing.T, h http.Handler, lat, lon, radius float64) []map[string]any {
	t.Helper()
	w := do(h, "GET", fmt.Sprintf("/v1/nearby-tracks?lat=%g&lon=%g&radius=%g", lat, lon, radius), "", "")
	var fc struct{ Features []map[string]any }
	if w.Code != 200 || w.Header().Get("Content-Type") != "application/geo+json" || json.Unmarshal(w.Body.Bytes(), &fc) != nil {
		t.Fatalf("nearby-tracks: %d %s", w.Code, w.Body)
	}
	return fc.Features
}

// publicNear is how many 公开轨迹 a tap finds; each carries its kind and nothing else, no author.
func publicNear(t *testing.T, h http.Handler, lat, lon, radius float64) (n int) {
	t.Helper()
	for _, f := range nearby(t, h, lat, lon, radius) {
		if props := f["properties"].(map[string]any); props["kind"] == "public" {
			if len(props) != 1 {
				t.Fatalf("公开轨迹 with more than its kind: %v", props)
			}
			n++
		}
	}
	return n
}

// 经过这里的轨迹 (§2.8): a tap finds the 公开轨迹 passing within the radius, but never near its hidden ends.
func TestPublicTracksNearAPoint(t *testing.T) {
	h, _, _, _ := syncServer(t)
	a := login(t, h, "13800138000")
	push(t, h, a, northTrack(true))
	east := 108 + 92/(111195*math.Cos(34*math.Pi/180)) // 92 m east of the line
	for _, c := range []struct {
		lat, lon, radius float64
		want             int
	}{
		{34.0045, 108, 20, 1},
		{34.0045, east, 50, 0},
		{34.0045, east, 100, 1},
		{34, 108, 150, 0}, // the hidden start
	} {
		if n := publicNear(t, h, c.lat, c.lon, c.radius); n != c.want {
			t.Errorf("%+v: %d", c, n)
		}
	}
	for _, q := range []string{"lat=34&lon=108&radius=501", "lat=34&lon=108&radius=0", "lat=91&lon=108&radius=10", "lat=34&lon=181&radius=10"} {
		if w := do(h, "GET", "/v1/nearby-tracks?"+q, "", ""); w.Code != 400 {
			t.Errorf("%s: %d", q, w.Code)
		}
	}
}
