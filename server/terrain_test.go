package main

import (
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/protomaps/go-pmtiles/pmtiles"
	"gocloud.dev/blob/fileblob"
)

// writeArchive writes a PMTiles archive of z 0–1 holding one tile, at 1/0/0.
func writeArchive(t *testing.T, path string, typ pmtiles.TileType, tc pmtiles.Compression, tile string) {
	dir := pmtiles.SerializeEntries([]pmtiles.EntryV3{{TileID: pmtiles.ZxyToID(1, 0, 0), Offset: 0, Length: uint32(len(tile)), RunLength: 1}}, pmtiles.NoCompression)
	h := pmtiles.HeaderV3{
		SpecVersion: 3, RootOffset: pmtiles.HeaderV3LenBytes, RootLength: uint64(len(dir)),
		MetadataOffset: pmtiles.HeaderV3LenBytes + uint64(len(dir)), TileDataOffset: pmtiles.HeaderV3LenBytes + uint64(len(dir)), TileDataLength: uint64(len(tile)),
		AddressedTilesCount: 1, TileEntriesCount: 1, TileContentsCount: 1, Clustered: true,
		InternalCompression: pmtiles.NoCompression, TileCompression: tc, TileType: typ, MinZoom: 0, MaxZoom: 1,
	}
	b := append(append(pmtiles.SerializeHeader(h), dir...), tile...)
	if err := os.WriteFile(path, b, 0o600); err != nil {
		t.Fatal(err)
	}
}

func TestTerrainTiles(t *testing.T) {
	d := t.TempDir()
	writeArchive(t, filepath.Join(d, "basemap.pmtiles"), pmtiles.Mvt, pmtiles.Gzip, "MVT")
	writeArchive(t, filepath.Join(d, "dem.pmtiles"), pmtiles.Webp, pmtiles.NoCompression, "WEBP")
	b, err := fileblob.OpenBucket(d, nil)
	if err != nil {
		t.Fatal(err)
	}
	h := routes(1, okDB, newOffline(b, nil, nil, nil, 0), nil, nil, nil, nil, nil, nil)

	w := get(h, "/v1/tiles/terrain/basemap/1/0/0")
	if w.Code != 200 || w.Body.String() != "MVT" || w.Header().Get("Content-Type") != "application/vnd.mapbox-vector-tile" ||
		w.Header().Get("Content-Encoding") != "gzip" || !strings.Contains(w.Header().Get("Cache-Control"), "max-age") {
		t.Fatalf("basemap: %d %q %v", w.Code, w.Body, w.Header())
	}
	if w := get(h, "/v1/tiles/terrain/dem/1/0/0"); w.Code != 200 || w.Body.String() != "WEBP" || w.Header().Get("Content-Type") != "image/webp" || w.Header().Get("Content-Encoding") != "" {
		t.Fatalf("dem: %d %q %v", w.Code, w.Body, w.Header())
	}
	if w := get(h, "/v1/tiles/terrain/basemap/1/1/1"); w.Code != 204 {
		t.Errorf("no tile: %d %s", w.Code, w.Body)
	}
	if w := get(h, "/v1/tiles/terrain/basemap/2/0/0"); w.Code != 204 {
		t.Errorf("past maxzoom: %d %s", w.Code, w.Body)
	}
	for _, p := range []string{"/foo/1/0/0", "/basemap/16/0/0", "/basemap/1/2/0", "/basemap/1/0/2", "/basemap/1/-1/0", "/basemap/-1/0/0", "/basemap/1/a/0"} {
		if w := get(h, "/v1/tiles/terrain"+p); w.Code != 400 || !strings.Contains(w.Body.String(), "invalid_request") {
			t.Errorf("%s: %d %s", p, w.Code, w.Body)
		}
	}
	if w := get(h, "/v1/tiles/terrain/contours/1/0/0"); w.Code != 503 || !strings.Contains(w.Body.String(), "data_unavailable") {
		t.Errorf("no archive: %d %s", w.Code, w.Body)
	}
}
