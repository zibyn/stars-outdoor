package main

// 天地图 through the server (spec §2.2): the app's 卫星 and 标准 cards load tiles from here, so the key
// stays in deploy/.env and never ships in the APK.

import (
	"context"
	"fmt"
	"log"
	"net/http"
	"strings"

	"stars-outdoor/server/api"
)

type tianditu struct {
	key      string
	upstream string // "https://t{s}.tianditu.gov.cn"; {s} spreads tiles over 天地图's hosts t0–t7
	client   *http.Client
}

// ponytail: every tile is a round trip to 天地图, counted against the key's daily quota; the app's
// MapLibre cache (Cache-Control) absorbs repeats. Cache on the server if the quota gets tight.
func (s *server) GetTiandituTile(ctx context.Context, req api.GetTiandituTileRequestObject) (api.GetTiandituTileResponseObject, error) {
	z, x, y := req.Z, req.X, req.Y
	if !req.Layer.Valid() || z < 1 || z > 18 || x < 0 || y < 0 || x >= 1<<z || y >= 1<<z {
		return api.GetTiandituTile400JSONResponse{Error: api.ErrorCodeInvalidRequest}, nil
	}
	unavailable := api.GetTiandituTile503JSONResponse{DataUnavailableJSONResponse: api.DataUnavailableJSONResponse{Error: api.ErrorCodeDataUnavailable}}
	t := s.tianditu
	if t == nil || t.key == "" {
		log.Printf("tianditu: TIANDITU_KEY not set")
		return unavailable, nil
	}
	// The `_w` services are Web Mercator; TILEROW/TILECOL count like XYZ y/x.
	u := strings.Replace(t.upstream, "{s}", fmt.Sprint((x+y)%8), 1) +
		fmt.Sprintf("/%s_w/wmts?SERVICE=WMTS&REQUEST=GetTile&VERSION=1.0.0&LAYER=%[1]s&STYLE=default&TILEMATRIXSET=w&FORMAT=tiles&TILEMATRIX=%d&TILEROW=%d&TILECOL=%d&tk=%s",
			req.Layer, z, y, x, t.key)
	// Go's own User-Agent: 天地图 refuses a 服务器端 key from anything that looks like a browser (301013).
	r, err := http.NewRequestWithContext(ctx, "GET", u, nil)
	if err != nil {
		return nil, fmt.Errorf("tianditu: %s", redact(t.key, err.Error()))
	}
	res, err := t.client.Do(r)
	if err != nil {
		log.Printf("tianditu %s/%d/%d/%d: %s", req.Layer, z, x, y, redact(t.key, err.Error()))
		return unavailable, nil
	}
	// A bad key or an exceeded quota comes back as a 200 XML/HTML page, not an image.
	if ct := res.Header.Get("Content-Type"); res.StatusCode != 200 || !strings.HasPrefix(ct, "image/") {
		res.Body.Close()
		log.Printf("tianditu %s/%d/%d/%d: %d %s", req.Layer, z, x, y, res.StatusCode, ct)
		return unavailable, nil
	}
	week := "public, max-age=604800"
	return api.GetTiandituTile200ImageResponse{Body: res.Body, ContentType: res.Header.Get("Content-Type"), ContentLength: max(res.ContentLength, 0), Headers: api.GetTiandituTile200ResponseHeaders{CacheControl: &week}}, nil
}

// redact keeps the key out of logs: request errors quote the URL, and the URL carries tk.
func redact(key, s string) string {
	return strings.ReplaceAll(s, key, "***")
}
