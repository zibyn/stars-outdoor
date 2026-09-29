package main

// 地形 online (spec §3.1, issue #53): tiles of the China PMTiles the offline packages are clipped from,
// read from the private bucket by go-pmtiles, which caches headers and directories (ADR 0001).

import (
	"bytes"
	"context"
	"fmt"
	"io"
	"log"

	"github.com/protomaps/go-pmtiles/pmtiles"
	"gocloud.dev/blob"

	"stars-outdoor/server/api"
)

// terrainExt is each layer's tile type, as go-pmtiles wants it in the path (build-data.sh makes them).
var terrainExt = map[api.GetTerrainTileParamsLayer]string{api.Basemap: "mvt", api.Dem: "webp", api.Contours: "mvt"}

func newTerrain(bucket *blob.Bucket) *pmtiles.Server {
	// ponytail: 64 MB of directories covers the three archives' roots and hot leaves; raise it if the
	// bucket reads per tile climb.
	s, err := pmtiles.NewServerWithBucket(pmtiles.BucketAdapter{Bucket: bucket}, "", log.New(io.Discard, "", 0), 64, "")
	if err != nil {
		panic(err) // NewServerWithBucket never fails
	}
	s.Start()
	return s
}

func (o *offline) GetTerrainTile(ctx context.Context, req api.GetTerrainTileRequestObject) (api.GetTerrainTileResponseObject, error) {
	z, x, y := req.Z, req.X, req.Y
	if !req.Layer.Valid() || z < 0 || z > 15 || x < 0 || y < 0 || x >= 1<<z || y >= 1<<z {
		return api.GetTerrainTile400JSONResponse{Error: api.ErrorCodeInvalidRequest}, nil
	}
	status, headers, data := o.terrain.Get(ctx, fmt.Sprintf("/%s/%d/%d/%d.%s", req.Layer, z, x, y, terrainExt[req.Layer]))
	switch {
	// go-pmtiles tells a missing archive from a missing tile only by the 404's body.
	case status == 404 && string(data) == "Archive not found":
		log.Printf("terrain: %s.pmtiles unreadable", req.Layer)
		return api.GetTerrainTile503JSONResponse{DataUnavailableJSONResponse: api.DataUnavailableJSONResponse{Error: api.ErrorCodeDataUnavailable}}, nil
	case status == 204 || status == 404 || ctx.Err() != nil: // no tile there, a zoom outside the archive's, or the app gave up
		return api.GetTerrainTile204Response{}, nil
	case status != 200:
		return nil, fmt.Errorf("terrain %s/%d/%d/%d: %d %s", req.Layer, z, x, y, status, data)
	}
	day := "public, max-age=86400"
	resHeaders := api.GetTerrainTile200ResponseHeaders{CacheControl: &day}
	if enc := headers["Content-Encoding"]; enc != "" {
		resHeaders.ContentEncoding = &enc
	}
	if req.Layer == api.Dem {
		return api.GetTerrainTile200ImagewebpResponse{Body: bytes.NewReader(data), ContentLength: int64(len(data)), Headers: resHeaders}, nil
	}
	return api.GetTerrainTile200ApplicationvndMapboxVectorTileResponse{Body: bytes.NewReader(data), ContentLength: int64(len(data)), Headers: resHeaders}, nil
}
