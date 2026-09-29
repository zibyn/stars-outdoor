package main

// 公开轨迹 (spec §2.8, ADR 0002): a synced 轨迹 its owner has made public, drawn as a layer of its own and
// never merged with, snapped to or corrected by OSM. What is shown is the track read in WGS-84 with
// everything within privacyRadiusM of its start and its end cut away; it is worked out once, on publishing
// (and when the 坐标纠偏 changes), and kept in public_tracks for the tiles and the offline snapshot.
// A promoted one (platformtracks.go) is left out of all three: its 平台轨迹 copy stands in for it.

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"math"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/paulmach/orb"
	"github.com/paulmach/orb/encoding/wkt"
	"github.com/paulmach/orb/geo"

	"stars-outdoor/server/api"
)

// A row is a 公开轨迹; its geom may be empty (a track shorter than its hidden ends).
const publicTracksSchema = `
CREATE EXTENSION IF NOT EXISTS postgis;
CREATE TABLE IF NOT EXISTS public_tracks (
	user_id bigint NOT NULL,
	id text NOT NULL,
	geom geometry(MultiLineString, 4326) NOT NULL,
	PRIMARY KEY (user_id, id),
	FOREIGN KEY (user_id, id) REFERENCES sync_tracks ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS public_tracks_geom ON public_tracks USING gist (geom);`

// publish applies a pushed change to whether the (not deleted) track is a 公开轨迹: public set publishes
// or withdraws it; otherwise a public one is redrawn, its 坐标纠偏 having changed.
func publish(ctx context.Context, tx pgx.Tx, user int64, id string, public *bool) error {
	if public != nil && !*public {
		_, err := tx.Exec(ctx, "DELETE FROM public_tracks WHERE user_id = $1 AND id = $2", user, id)
		return err
	}
	var points []api.SyncPoint
	var datum api.Datum
	err := tx.QueryRow(ctx, `SELECT points, datum FROM sync_tracks t WHERE user_id = $1 AND id = $2
		AND ($3 OR EXISTS (SELECT 1 FROM public_tracks p WHERE p.user_id = t.user_id AND p.id = t.id))`, user, id, public != nil).Scan(&points, &datum)
	if errors.Is(err, pgx.ErrNoRows) { // not public: nothing to redraw
		return nil
	} else if err != nil {
		return err
	}
	q := "UPDATE public_tracks SET geom = ST_GeomFromText($3, 4326) WHERE user_id = $1 AND id = $2"
	if public != nil {
		q = `INSERT INTO public_tracks (user_id, id, geom) VALUES ($1, $2, ST_GeomFromText($3, 4326))
			ON CONFLICT (user_id, id) DO UPDATE SET geom = excluded.geom`
	}
	_, err = tx.Exec(ctx, q, user, id, wkt.MarshalString(publicLine(points, datum)))
	return err
}

// GetPublicTracksTile needs no login (§2.8: anyone may look).
func (s *server) GetPublicTracksTile(ctx context.Context, req api.GetPublicTracksTileRequestObject) (api.GetPublicTracksTileResponseObject, error) {
	tile, err := s.vectorTile(ctx, "public_tracks", "shown_public_tracks", "", 11, req.Z, req.X, req.Y)
	if err != nil {
		return nil, err
	} else if tile == nil {
		return api.GetPublicTracksTile400JSONResponse{Error: api.ErrorCodeInvalidRequest}, nil
	}
	return api.GetPublicTracksTile200ApplicationvndMapboxVectorTileResponse{Body: tile, ContentLength: int64(tile.Len()),
		Headers: api.GetPublicTracksTile200ResponseHeaders{CacheControl: &tileCache}}, nil
}

// tileCache is short, so a withdrawn track soon leaves the phones' caches too.
var tileCache = "public, max-age=60"

// vectorTile is table's tracks with their columns (after geom, comma-separated) as a Mapbox Vector Tile
// of one layer, or nil if the tile is outside minZoom–16.
func (s *server) vectorTile(ctx context.Context, layer, table, columns string, minZoom, z, x, y int) (*bytes.Reader, error) {
	if z < minZoom || z > 16 || x < 0 || y < 0 || x >= 1<<z || y >= 1<<z {
		return nil, nil
	}
	if columns != "" {
		columns = ", " + columns
	}
	// The 64-unit buffer (of 4096) keeps lines from ending visibly at tile edges.
	q := fmt.Sprintf(`SELECT coalesce(ST_AsMVT(t, '%s'), '') FROM (
		SELECT ST_AsMVTGeom(ST_Transform(geom, 3857), ST_TileEnvelope($1, $2, $3)) AS geom%s FROM %s
		WHERE geom && ST_Transform(ST_TileEnvelope($1, $2, $3, margin => 64.0 / 4096), 4326)) t
		WHERE geom IS NOT NULL`, layer, columns, table)
	var tile []byte
	if err := s.cloud.db.QueryRow(ctx, q, z, x, y).Scan(&tile); err != nil {
		return nil, err
	}
	return bytes.NewReader(tile), nil
}

// snapshotQueries cut a package's copy of the 公开轨迹 or 平台轨迹 crossing a region ($1, GeoJSON) from PostGIS.
// Whole tracks, not clipped: they are public anyway.
var snapshotQueries = map[string]string{
	snapshotFile: fmt.Sprintf(featureCollection, "'{}'::json", "shown_public_tracks"),
	platformFile: fmt.Sprintf(featureCollection, "json_build_object('name', name, 'source', source)", "platform_tracks"),
}

const featureCollection = `SELECT json_build_object('type', 'FeatureCollection', 'features', coalesce(json_agg(
		json_build_object('type', 'Feature', 'properties', %s, 'geometry', ST_AsGeoJSON(geom)::json)), '[]'))
	FROM %s WHERE ST_Intersects(geom, ST_SetSRID(ST_GeomFromGeoJSON($1), 4326)) AND NOT ST_IsEmpty(geom)`

// postgisSnapshot is a package's copy of the 公开轨迹 or 平台轨迹 (by its file name) crossing a region
// (GeoJSON, WGS-84), as a FeatureCollection.
// ponytail: GeoJSON, a few MB where many people walk; PMTiles (tippecanoe) if snapshots get heavy.
func postgisSnapshot(db *pgxpool.Pool) func(ctx context.Context, file, region string) ([]byte, error) {
	return func(ctx context.Context, file, region string) (fc []byte, err error) {
		err = db.QueryRow(ctx, snapshotQueries[file], region).Scan(&fc)
		return
	}
}

// privacyRadiusM hides where a 公开轨迹 starts and ends (home, the car).
const privacyRadiusM = 200

// densifyM: kept points are ≥ 202 m from a hidden centre, so a chord of 10 m between two stays ≥ 201.9 m off.
const densifyM = 10

// publicLine is what of a track's points may be shown: in WGS-84, one line per segment, split wherever
// a point falls within privacyRadiusM of the first or last point, and without pieces of a single point.
// Points are first filled in to at most densifyM apart, so no line between two kept points (a sparse
// planned track) can cut through the hidden discs.
func publicLine(points []api.SyncPoint, datum api.Datum) orb.MultiLineString {
	if len(points) == 0 {
		return nil
	}
	ps := make([]orb.Point, len(points))
	for i, p := range points {
		lat, lon := toWGS84(datum, p.Lat, p.Lon)
		ps[i] = orb.Point{lon, lat}
	}
	start, end := ps[0], ps[len(ps)-1]
	// Haversine is on a sphere, up to 0.5% off the spheroid PostGIS and the phones measure on.
	cut := privacyRadiusM * 1.01
	hidden := func(p orb.Point) bool {
		return geo.DistanceHaversine(p, start) < cut || geo.DistanceHaversine(p, end) < cut
	}
	var out orb.MultiLineString
	var cur orb.LineString
	flush := func() {
		if len(cur) >= 2 {
			out = append(out, cur)
		}
		cur = nil
	}
	for i, p := range ps {
		if i > 0 && points[i].S != points[i-1].S {
			flush()
		} else if i > 0 {
			prev := ps[i-1]
			for k, n := 1, int(math.Ceil(geo.DistanceHaversine(prev, p)/densifyM))-1; k <= n; k++ {
				f := float64(k) / float64(n+1)
				if q := (orb.Point{prev[0] + (p[0]-prev[0])*f, prev[1] + (p[1]-prev[1])*f}); !hidden(q) {
					cur = append(cur, q)
				} else {
					flush()
				}
			}
		}
		if hidden(p) {
			flush()
			continue
		}
		cur = append(cur, p)
	}
	flush()
	return out
}

// toWGS84 is 坐标纠偏 (§2.6) as the app does it (Datum.kt): only coordinates inside China were ever shifted.
func toWGS84(d api.Datum, lat, lon float64) (float64, float64) {
	// ponytail: bounding box, as every GCJ-02 implementation uses; a China polygon if border tracks shift wrongly.
	if d == api.WGS84 || lon < 72.004 || lon > 137.8347 || lat < 0.8293 || lat > 55.8271 {
		return lat, lon
	}
	if d == api.BD09 {
		x, y := lon-0.0065, lat-0.006
		z := math.Sqrt(x*x+y*y) - 0.00002*math.Sin(y*math.Pi*3000/180)
		theta := math.Atan2(y, x) - 0.000003*math.Cos(x*math.Pi*3000/180)
		lat, lon = z*math.Sin(theta), z*math.Cos(theta)
	}
	// GCJ-02 has no closed-form inverse; a few fixed-point steps converge well below a millimetre.
	wLat, wLon := lat, lon
	for range 5 {
		gLat, gLon := wgs84ToGcj02(wLat, wLon)
		wLat, wLon = wLat+lat-gLat, wLon+lon-gLon
	}
	return wLat, wLon
}

func wgs84ToGcj02(lat, lon float64) (float64, float64) {
	const a, ee = 6378245.0, 0.00669342162296594323
	x, y := lon-105, lat-35
	dLat := -100 + 2*x + 3*y + 0.2*y*y + 0.1*x*y + 0.2*math.Sqrt(math.Abs(x)) +
		(20*math.Sin(6*x*math.Pi)+20*math.Sin(2*x*math.Pi))*2/3 +
		(20*math.Sin(y*math.Pi)+40*math.Sin(y/3*math.Pi))*2/3 +
		(160*math.Sin(y/12*math.Pi)+320*math.Sin(y*math.Pi/30))*2/3
	dLon := 300 + x + 2*y + 0.1*x*x + 0.1*x*y + 0.1*math.Sqrt(math.Abs(x)) +
		(20*math.Sin(6*x*math.Pi)+20*math.Sin(2*x*math.Pi))*2/3 +
		(20*math.Sin(x*math.Pi)+40*math.Sin(x/3*math.Pi))*2/3 +
		(150*math.Sin(x/12*math.Pi)+300*math.Sin(x/30*math.Pi))*2/3
	rad := lat / 180 * math.Pi
	magic := 1 - ee*math.Sin(rad)*math.Sin(rad)
	dLat = dLat * 180 / ((a * (1 - ee)) / (magic * math.Sqrt(magic)) * math.Pi)
	dLon = dLon * 180 / (a / math.Sqrt(magic) * math.Cos(rad) * math.Pi)
	return lat + dLat, lon + dLon
}

// GetPublicTracks is 经过这里的轨迹 (§2.8): the 公开轨迹 passing near a tap, whole, as in a package's snapshot. No login.
func (s *server) GetPublicTracks(ctx context.Context, req api.GetPublicTracksRequestObject) (api.GetPublicTracksResponseObject, error) {
	p := req.Params
	fc, ok, err := s.tracksNear(ctx, "'{}'::json", "shown_public_tracks", p.Lat, p.Lon, p.Radius)
	if !ok {
		return api.GetPublicTracks400JSONResponse{Error: api.ErrorCodeInvalidRequest}, err
	}
	return api.GetPublicTracks200ApplicationGeoPlusJSONResponse(fc), err
}

// tracksNear is table's tracks (with properties, a SQL json expression) passing within radius metres of
// lat, lon, at most 20, whole; !ok if the point or radius is out of range.
func (s *server) tracksNear(ctx context.Context, properties, table string, lat, lon, radius float64) (fc api.FeatureCollection, ok bool, err error) {
	if lat < -90 || lat > 90 || lon < -180 || lon > 180 || radius < 1 || radius > 500 {
		return fc, false, nil
	}
	// The && on the buffer's box uses the index; ST_DWithin on geography measures in metres.
	q := fmt.Sprintf(`SELECT coalesce(json_agg(json_build_object('type', 'Feature', 'properties', %s, 'geometry', ST_AsGeoJSON(geom)::json)), '[]')
		FROM (SELECT * FROM %s, (SELECT ST_Point($2, $1, 4326)::geography AS here) h
			WHERE geom && ST_Buffer(here, $3)::geometry AND ST_DWithin(geom::geography, here, $3) LIMIT 20) t`, properties, table)
	fc.Type = api.FeatureCollectionTypeFeatureCollection
	return fc, true, s.cloud.db.QueryRow(ctx, q, lat, lon, radius).Scan(&fc.Features)
}
