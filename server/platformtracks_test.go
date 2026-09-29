package main

import (
	"context"
	"fmt"
	"net/http"
	"strings"
	"testing"

	"github.com/paulmach/orb"
	"github.com/paulmach/orb/maptile"
)

// platformTileBytes is the size of the platform-tracks tile at z 14 over p.
func platformTileBytes(t *testing.T, h http.Handler, p orb.Point) int {
	t.Helper()
	tile := maptile.At(p, 14)
	w := do(h, "GET", fmt.Sprintf("/v1/tiles/platform-tracks/%d/%d/%d", tile.Z, tile.X, tile.Y), "", "")
	if w.Code != 200 || w.Header().Get("Content-Type") != "application/vnd.mapbox-vector-tile" {
		t.Fatalf("tile: %d %s", w.Code, w.Body)
	}
	return w.Body.Len()
}

// A 平台轨迹 is in the tiles and a package's snapshot, with its name and credit.
func TestPlatformTrackTilesAndSnapshot(t *testing.T) {
	h, db, _, _ := syncServer(t)
	ctx := context.Background()
	if _, err := db.Exec(ctx, `INSERT INTO platform_tracks (name, source, geom)
		VALUES ('鳌太线 第1段', '山友 阿明', ST_Multi(ST_GeomFromGeoJSON('{"type":"LineString","coordinates":[[108,34],[108,34.01]]}')))`); err != nil {
		t.Fatal(err)
	}
	if n := platformTileBytes(t, h, orb.Point{108, 34.005}); n == 0 {
		t.Fatal("empty tile")
	}
	fc, err := postgisSnapshot(db)(ctx, platformFile, qinlingRegion)
	if err != nil || strings.Count(string(fc), `"Feature"`) != 1 || !strings.Contains(string(fc), "山友 阿明") || !strings.Contains(string(fc), "鳌太线 第1段") {
		t.Fatalf("snapshot: %s %v", fc, err)
	}
	for _, path := range []string{"/v1/tiles/platform-tracks/7/0/0", "/v1/tiles/platform-tracks/17/0/0", "/v1/tiles/platform-tracks/8/256/0"} {
		if w := do(h, "GET", path, "", ""); w.Code != 400 {
			t.Fatalf("%s: %d", path, w.Code)
		}
	}
}

// 晋升 (ADR 0005): the copy is a 平台轨迹 credited to its author, the 公开轨迹 leaves the public layer and
// snapshot, and the copy stays when the author deletes the original.
func TestPromotedPublicTrack(t *testing.T) {
	h, db, _, _ := syncServer(t)
	ctx := context.Background()
	a := login(t, h, "13800138000")
	push(t, h, a, northTrack(true))
	mid := orb.Point{108, 34.0045}
	var id int64
	if err := db.QueryRow(ctx, "SELECT promote_track(user_id, id, '山友 阿明') FROM public_tracks").Scan(&id); err != nil {
		t.Fatal(err)
	}
	if _, err := db.Exec(ctx, "SELECT promote_track(user_id, id, 'x') FROM public_tracks"); err == nil {
		t.Fatal("promoted twice")
	}
	if _, err := db.Exec(ctx, "SELECT promote_track(1, 'nope', 'x')"); err == nil {
		t.Fatal("promoted a track that isn't there")
	}
	if n := tileBytes(t, h, mid); n != 0 {
		t.Fatalf("promoted, still %d bytes of 公开轨迹", n)
	}
	if n := features(t, h, 34.0045, 108, 20); n != 0 {
		t.Fatalf("promoted, still tapped as a 公开轨迹: %d", n)
	}
	if fc, err := postgisSnapshot(db)(ctx, snapshotFile, qinlingRegion); err != nil || strings.Contains(string(fc), `"Feature"`) {
		t.Fatalf("public snapshot: %s %v", fc, err)
	}
	// Still public to its owner.
	if s := pull(t, h, a, 0); !s.Tracks[0].Public {
		t.Fatal("no longer public")
	}
	if n := platformTileBytes(t, h, mid); n == 0 {
		t.Fatal("no 平台轨迹")
	}
	// Withdrawn, then deleted, by its author: the copy stays.
	push(t, h, a, `{"tracks":[{"id":"`+trackID+`","public":false}],"waypoints":[]}`)
	if n := platformTileBytes(t, h, mid); n == 0 {
		t.Fatal("withdrawn, and the 平台轨迹 went too")
	}
	push(t, h, a, `{"tracks":[{"id":"`+trackID+`","deleted":true}],"waypoints":[]}`)
	fc, err := postgisSnapshot(db)(ctx, platformFile, qinlingRegion)
	if err != nil || strings.Count(string(fc), `"Feature"`) != 1 || !strings.Contains(string(fc), "山友 阿明") {
		t.Fatalf("platform snapshot after deletion: %s %v", fc, err)
	}
}

// 经过这里的轨迹 online: a tap finds the 平台轨迹 within the radius, with its name and credit, until 下架.
func TestPlatformTracksNearAPoint(t *testing.T) {
	h, db, _, _ := syncServer(t)
	ctx := context.Background()
	var id int64
	if err := db.QueryRow(ctx, `INSERT INTO platform_tracks (name, source, geom)
		VALUES ('鳌太线 第1段', '山友 阿明', ST_Multi(ST_GeomFromGeoJSON('{"type":"LineString","coordinates":[[108,34],[108,34.01]]}'))) RETURNING id`).Scan(&id); err != nil {
		t.Fatal(err)
	}
	near := func(lat, lon, radius float64) string {
		t.Helper()
		w := do(h, "GET", fmt.Sprintf("/v1/platform-tracks?lat=%g&lon=%g&radius=%g", lat, lon, radius), "", "")
		if w.Code != 200 || w.Header().Get("Content-Type") != "application/geo+json" {
			t.Fatalf("platform-tracks: %d %s", w.Code, w.Body)
		}
		return w.Body.String()
	}
	if b := near(34.005, 108, 20); strings.Count(b, `"Feature"`) != 1 || !strings.Contains(b, "山友 阿明") || !strings.Contains(b, "鳌太线 第1段") {
		t.Fatalf("near: %s", b)
	}
	if b := near(34.005, 108.01, 100); strings.Contains(b, `"Feature"`) {
		t.Fatalf("~900 m off: %s", b)
	}
	if _, err := db.Exec(ctx, "DELETE FROM platform_tracks WHERE id = $1", id); err != nil {
		t.Fatal(err)
	}
	if b := near(34.005, 108, 20); strings.Contains(b, `"Feature"`) {
		t.Fatalf("下架, still found: %s", b)
	}
	if w := do(h, "GET", "/v1/platform-tracks?lat=34&lon=108&radius=501", "", ""); w.Code != 400 {
		t.Fatalf("radius 501: %d", w.Code)
	}
}
