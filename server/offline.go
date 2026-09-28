package main

// Offline packages (spec §2.3, ADR 0001): clip the quarterly China PMTiles on object storage to a
// viewport or a track corridor with go-pmtiles, cache the clips in the same bucket, hand out signed URLs.

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"math"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/protomaps/go-pmtiles/pmtiles"
	"gocloud.dev/blob"
	"gocloud.dev/gcerrors"
	"golang.org/x/sync/singleflight"
)

// Uploaded by scripts/upload-data.sh; a package holds one clip of each, under the same names.
var sourceFiles = []string{"basemap.pmtiles", "dem.pmtiles", "contours.pmtiles"}

const (
	maxAreaKm2     = 100 * 100 // §2.3: one package ≤ about 100 × 100 km
	trackBufferM   = 2000      // corridor half-width for 沿此轨迹下载
	maxTrackPoints = 5000      // the app thins tracks before sending
	// ponytail: a signed URL can be re-fetched until it expires, so egress can exceed the quota within
	// that window; proxy downloads or use one-shot tokens if that gets abused.
	urlTTL = 15 * time.Minute
)

// region is the clip area as PostGIS sees it: GeoJSON polygon, geodesic area, bbox (w, s, e, n).
type region struct {
	GeoJSON string
	AreaKm2 float64
	Bbox    [4]float64
}

type pkgFile struct {
	Name  string `json:"name"`
	URL   string `json:"url"`
	Bytes int64  `json:"bytes"`
}

type pkgResponse struct {
	Version string    `json:"version"`
	Bytes   int64     `json:"bytes"`
	Files   []pkgFile `json:"files"`
}

type offline struct {
	bucket  *blob.Bucket
	region  func(ctx context.Context, geom string, bufferM float64) (region, error)
	extract func(ctx context.Context, src, regionFile, out string) error
	quota   int64
	// ponytail: daily byte quotas in memory, reset on restart (single instance, §3.2); a table if restarts get abused.
	devices, ips *limiter
	clips        chan struct{} // bounds concurrent extracts on the small VPS
	inflight     singleflight.Group
}

func newOffline(bucket *blob.Bucket, region func(context.Context, string, float64) (region, error), extract func(context.Context, string, string, string) error, quota int64) *offline {
	return &offline{
		bucket: bucket, region: region, extract: extract, quota: quota,
		devices: &limiter{max: quota, period: 86400}, ips: &limiter{max: quota * ipShare, period: 86400},
		clips: make(chan struct{}, 2),
	}
}

func (o *offline) register(mux *http.ServeMux) {
	mux.HandleFunc("GET /v1/offline/version", func(w http.ResponseWriter, r *http.Request) {
		version, _, err := o.sources(r.Context())
		if err != nil {
			log.Printf("offline version: %v", err)
			writeJSON(w, http.StatusServiceUnavailable, map[string]any{"error": "data_unavailable"})
			return
		}
		writeJSON(w, http.StatusOK, map[string]any{"version": version})
	})
	mux.HandleFunc("POST /v1/offline/packages", o.packages)
}

func (o *offline) packages(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Bbox  []float64    `json:"bbox"`
		Track [][2]float64 `json:"track"`
	}
	if json.NewDecoder(http.MaxBytesReader(w, r.Body, 1<<20)).Decode(&req) != nil {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "invalid_region"})
		return
	}
	geom, buffer, ok := requestGeometry(req.Bbox, req.Track)
	if !ok {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "invalid_region"})
		return
	}
	// Charged per package handed out, cached or not: the cost is object-storage egress. The device ID is
	// anonymous and rotatable, so the caller's IP gets a looser cap too (as in the rate limiter).
	ip, _, _ := net.SplitHostPort(r.RemoteAddr)
	device := ip
	if id := r.Header.Get("X-Device-Id"); id != "" {
		device = "id " + id
	}
	quotaExceeded := func() {
		writeJSON(w, http.StatusTooManyRequests, map[string]any{"error": "daily_quota_exceeded", "quotaBytes": o.quota})
	}
	if !o.devices.fits(device, 1) || !o.ips.fits(ip, 1) { // used up: don't clip for nothing
		quotaExceeded()
		return
	}
	ctx := r.Context()
	version, bounds, err := o.sources(ctx)
	if err != nil {
		log.Printf("offline sources: %v", err)
		writeJSON(w, http.StatusServiceUnavailable, map[string]any{"error": "data_unavailable"})
		return
	}
	reg, err := o.region(ctx, geom, buffer)
	if err != nil {
		log.Printf("offline region: %v", err)
		writeJSON(w, http.StatusInternalServerError, map[string]any{"error": "internal"})
		return
	}
	// Area rather than extent: the clip's cost follows the tiles it covers, so a long thin corridor is fine.
	if reg.AreaKm2 > maxAreaKm2 {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "region_too_large", "maxAreaKm2": maxAreaKm2})
		return
	}
	// ponytail: "China" is the data's bbox, which also covers neighbours (Korea, Mongolia, north Vietnam);
	// ST_Intersects with a China outline once one is loaded into PostGIS.
	b := reg.Bbox
	if b[2] < bounds[0] || b[0] > bounds[2] || b[3] < bounds[1] || b[1] > bounds[3] {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "region_unsupported"})
		return
	}
	sum := sha256.Sum256(fmt.Appendf(nil, "%s %g", geom, buffer))
	prefix := "packages/" + version + "/" + hex.EncodeToString(sum[:8]) + "/"
	// Two phones asking for the same area at once share one clip; it outlives a caller that hangs up.
	v, err, _ := o.inflight.Do(prefix, func() (any, error) { return o.clip(context.WithoutCancel(ctx), prefix, reg) })
	if err != nil {
		log.Printf("offline clip %s: %v", prefix, err)
		writeJSON(w, http.StatusInternalServerError, map[string]any{"error": "internal"})
		return
	}
	res := pkgResponse{Version: version}
	for _, f := range v.([]pkgFile) {
		res.Bytes += f.Bytes
		if f.URL, err = o.bucket.SignedURL(ctx, prefix+f.Name, &blob.SignedURLOptions{Expiry: urlTTL}); err != nil {
			log.Printf("offline sign: %v", err)
			writeJSON(w, http.StatusInternalServerError, map[string]any{"error": "internal"})
			return
		}
		res.Files = append(res.Files, f)
	}
	// ponytail: fits-then-take across two limiters isn't atomic; two racing requests may overshoot by a package.
	if !o.devices.fits(device, res.Bytes) || !o.ips.fits(ip, res.Bytes) {
		quotaExceeded()
		return
	}
	o.devices.take(device, res.Bytes)
	o.ips.take(ip, res.Bytes)
	writeJSON(w, http.StatusOK, res)
}

// requestGeometry turns a bbox or a track into GeoJSON plus buffer (m); ok is false if it's malformed.
// A bbox snaps outward to 0.01° (~1 km) so nearby viewports share one cached package.
func requestGeometry(bbox []float64, track [][2]float64) (geom string, bufferM float64, ok bool) {
	valid := func(lon, lat float64) bool { return lon >= -180 && lon <= 180 && lat >= -90 && lat <= 90 }
	switch {
	case bbox != nil:
		if len(bbox) != 4 || !valid(bbox[0], bbox[1]) || !valid(bbox[2], bbox[3]) || bbox[0] >= bbox[2] || bbox[1] >= bbox[3] {
			return "", 0, false
		}
		down := func(x float64) float64 { return math.Floor(x*100+1e-6) / 100 }
		up := func(x float64) float64 { return math.Ceil(x*100-1e-6) / 100 }
		w, s, e, n := down(bbox[0]), down(bbox[1]), up(bbox[2]), up(bbox[3])
		return fmt.Sprintf(`{"type":"Polygon","coordinates":[[[%g,%g],[%g,%g],[%g,%g],[%g,%g],[%g,%g]]]}`, w, s, e, s, e, n, w, n, w, s), 0, true
	case track != nil:
		if len(track) < 2 || len(track) > maxTrackPoints {
			return "", 0, false
		}
		for _, p := range track {
			if !valid(p[0], p[1]) {
				return "", 0, false
			}
		}
		coords, _ := json.Marshal(track)
		return `{"type":"LineString","coordinates":` + string(coords) + `}`, trackBufferM, true
	}
	return "", 0, false
}

// ponytail: 3 HEADs + a range read per call; cache for a minute if package requests get busy.
// sources reports the data version (from the source files' ETags, so each upload-data.sh run starts
// a fresh cache prefix) and the area the data covers (the basemap's header bounds: China's bbox).
func (o *offline) sources(ctx context.Context) (version string, bounds [4]float64, err error) {
	h := sha256.New()
	for _, f := range sourceFiles {
		a, err := o.bucket.Attributes(ctx, f)
		if err != nil {
			return "", bounds, err
		}
		fmt.Fprintln(h, a.ETag)
	}
	rr, err := o.bucket.NewRangeReader(ctx, sourceFiles[0], 0, pmtiles.HeaderV3LenBytes, nil)
	if err != nil {
		return "", bounds, err
	}
	defer rr.Close()
	b, err := io.ReadAll(rr)
	if err != nil {
		return "", bounds, err
	}
	hd, err := pmtiles.DeserializeHeader(b)
	if err != nil {
		return "", bounds, err
	}
	bounds = [4]float64{float64(hd.MinLonE7) / 1e7, float64(hd.MinLatE7) / 1e7, float64(hd.MaxLonE7) / 1e7, float64(hd.MaxLatE7) / 1e7}
	return hex.EncodeToString(h.Sum(nil)[:4]), bounds, nil
}

// clip makes sure prefix holds a clip of every source file, extracting the missing ones.
func (o *offline) clip(ctx context.Context, prefix string, reg region) ([]pkgFile, error) {
	tmp, err := os.MkdirTemp("", "pkg")
	if err != nil {
		return nil, err
	}
	defer os.RemoveAll(tmp)
	regionFile := filepath.Join(tmp, "region.geojson")
	if err := os.WriteFile(regionFile, []byte(reg.GeoJSON), 0o600); err != nil {
		return nil, err
	}
	var files []pkgFile
	for _, f := range sourceFiles {
		a, err := o.bucket.Attributes(ctx, prefix+f)
		if gcerrors.Code(err) == gcerrors.NotFound {
			o.clips <- struct{}{}
			err = o.extractUpload(ctx, f, regionFile, filepath.Join(tmp, f), prefix+f)
			<-o.clips
			if err == nil {
				a, err = o.bucket.Attributes(ctx, prefix+f)
			}
		}
		if err != nil {
			return nil, err
		}
		files = append(files, pkgFile{Name: f, Bytes: a.Size})
	}
	return files, nil
}

func (o *offline) extractUpload(ctx context.Context, src, regionFile, out, key string) error {
	if err := o.extract(ctx, src, regionFile, out); err != nil {
		return err
	}
	f, err := os.Open(out)
	if err != nil {
		return err
	}
	defer f.Close()
	return o.bucket.Upload(ctx, key, f, &blob.WriterOptions{ContentType: "application/vnd.pmtiles"})
}

// pmtilesExtract range-reads src from the bucket and writes the clip to out.
func pmtilesExtract(bucketURL string) func(context.Context, string, string, string) error {
	logger := log.New(io.Discard, "", 0)
	return func(ctx context.Context, src, regionFile, out string) error {
		return pmtiles.Extract(ctx, logger, bucketURL, src, -1, -1, regionFile, "", out, 4, 0.05, false)
	}
}

// postgisRegion buffers a track into a corridor on the spheroid and measures the area.
func postgisRegion(db *pgxpool.Pool) func(context.Context, string, float64) (region, error) {
	const q = `WITH g AS (SELECT CASE WHEN $2::float8 > 0 THEN ST_Buffer(ST_GeomFromGeoJSON($1::text)::geography, $2::float8)::geometry
	                                  ELSE ST_GeomFromGeoJSON($1::text) END AS geom)
	           SELECT ST_AsGeoJSON(geom), ST_Area(geom::geography) / 1e6, ST_XMin(geom), ST_YMin(geom), ST_XMax(geom), ST_YMax(geom) FROM g`
	return func(ctx context.Context, geom string, bufferM float64) (r region, err error) {
		err = db.QueryRow(ctx, q, geom, bufferM).Scan(&r.GeoJSON, &r.AreaKm2, &r.Bbox[0], &r.Bbox[1], &r.Bbox[2], &r.Bbox[3])
		return
	}
}
